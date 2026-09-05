package com.cseini.byd.karaoke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.cseini.byd.karaoke.data.SettingsStore
import java.io.File
import kotlin.concurrent.thread

/**
 * 재사용 가능한 마이크 녹음기. 설정(소스/AEC/USB 라우팅)을 적용해 WAV 로 저장.
 * 자동 녹음(재생 중)·진단 공용. 채점은 저장된 WAV 를 ScoringEngine 에 넘겨 수행.
 * 음성검색은 sourceOverride(VOICE_COMMUNICATION)+forceEffects 로 에코·노이즈를 강하게 제거.
 */
class AudioRecorder(
    private val context: Context,
    private val settings: SettingsStore,
    val sampleRate: Int = 44100,
    private val sourceOverride: Int? = null,
    private val forceEffects: Boolean = false,
) {
    companion object { private const val TAG = "karaoke-rec" }

    @Volatile private var recording = false
    @Volatile private var currentSource: Int = -1      // 현재 녹음 중인 소스 저장 (진단용)
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    var outputFile: File? = null
        private set

    val isRecording: Boolean get() = recording
    fun getCurrentSource(): Int = currentSource    // 현재 사용 소스 조회 (finish() 등에서 호출)

    /**
     * 녹음 시작. onLevel(dBFS) 은 UI 레벨미터용(백그라운드 스레드에서 호출).
     * @return 실패 시 오류 메시지, 성공 시 null.
     */
    @SuppressLint("MissingPermission")
    fun start(outFile: File, onLevel: ((Float) -> Unit)? = null): String? {
        if (recording) return "이미 녹음 중"
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return "AudioRecord 버퍼 계산 실패($minBuf)"

        // USB 마이크 있으면 그걸, 없으면 차량 내장 통화 마이크로 폴백. 사용자가 소스를 강제했으면 그걸 우선.
        val opened = MicRouting.open(
            context, sampleRate, minBuf * 2,
            settings.forcedMicSource(),
            sourceOverride ?: android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            settings.preferUsbMic
        ) ?: return "마이크가 응답하지 않습니다. 마이크 전원을 껐다 켜거나 수신기를 다시 꽂아주세요."
        val record = opened.record

        // 내장(통화) 마이크는 스피커 반주가 섞여 들어오므로 에코·잡음 제거를 켠다.
        // 단 씨라이언7(BYD 가 USB 마이크 소유)에선 켜지 않는다 — 이 효과들은 차량 오디오 HAL 안에서 돌아
        // BYD 마이크 경로를 건드릴 수 있다(6.46 결정, 6.48 되돌리기 때 함께 풀렸던 것을 복원).
        val useFx = !opened.bydOwnsMic && (forceEffects || settings.aecEnabled || opened.builtin)
        if (useFx) {
            val sid = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable())
                aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull()
            if (forceEffects || opened.builtin) {
                if (NoiseSuppressor.isAvailable())
                    ns = runCatching { NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull()
                if (AutomaticGainControl.isAvailable())
                    agc = runCatching { AutomaticGainControl.create(sid)?.apply { enabled = true } }.getOrNull()
            }
        }

        val writer = WavIo.Writer(outFile, sampleRate)
        outputFile = outFile
        currentSource = opened.src    // 현재 사용 소스 저장 (finish()에서 음성검색 소스 로깅용)
        record.startRecording()
        recording = true

        // 진단: 실제로 어느 장치에 라우팅됐는지(USB vs 내장/BUS) — 무음 오인식·엉뚱소스 원인 판별용.
        runCatching {
            val rd = record.routedDevice
            val name = rd?.let { "${MicRouting.typeName(it.type)}/${it.productName}" } ?: "?"
            com.cseini.byd.karaoke.CrashLog.event(context, "rec routed=$name src=${opened.src} builtin=${opened.builtin} fx=$useFx")
        }

        worker = thread(name = "audio-recorder") {
            val buf = ShortArray(minBuf)
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        writer.write(buf, n)
                        onLevel?.let { cb ->
                            val f = FloatArray(n) { buf[it] / 32768f }
                            cb(PitchDetector.rmsDb(f))
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "read 오류 $n")
                        com.cseini.byd.karaoke.CrashLog.event(context, "rec read 오류 $n — 녹음 중단")
                        break
                    }
                }
            } catch (t: Throwable) {
                // 디스크 풀·SD 제거, 그리고 녹음 중 마이크가 시스템에 회수될 때의 AudioRecord 예외까지.
                // 워커 스레드의 uncaught 예외는 곧 앱 강제종료다 — 여기서 반드시 삼키고 정리만 한다.
                // (씨라이언7 실측: BYD 가 USB 마이크를 되가져가면 read 가 예외를 던지며 앱이 튕겼다)
                com.cseini.byd.karaoke.CrashLog.event(context, "rec 워커 중단 ${t::class.java.simpleName}: ${t.message}")
            } finally {
                recording = false   // 워커가 먼저 죽어도 다음 start() 가 '이미 녹음 중'으로 막히지 않게
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { aec?.release() }; aec = null
                runCatching { ns?.release() }; ns = null
                runCatching { agc?.release() }; agc = null
                runCatching { writer.close() }
            }
        }
        return null
    }

    /** 녹음 중지. 저장된 파일 반환. */
    fun stop(): File? {
        if (!recording) return outputFile
        recording = false
        worker?.join(1500)
        worker = null
        return outputFile
    }
}
