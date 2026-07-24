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
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    var outputFile: File? = null
        private set

    val isRecording: Boolean get() = recording

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

        // USB 마이크 있으면 그걸, 없으면 차량 내장 통화 마이크로 폴백해서 연다.
        val opened = MicRouting.open(
            context, sampleRate, minBuf * 2,
            sourceOverride ?: settings.micSourceConst(), settings.preferUsbMic
        ) ?: return "마이크를 열 수 없습니다(장치·권한 확인)"
        val record = opened.record

        // 내장(통화) 마이크는 스피커 반주가 섞여 들어오므로 에코·잡음 제거를 켠다.
        if (forceEffects || settings.aecEnabled || opened.builtin) {
            val sid = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable())
                aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull()
            if (forceEffects || opened.builtin) {
                if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(sid)?.enabled = true }
                if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(sid)?.enabled = true }
            }
        }

        val writer = WavIo.Writer(outFile, sampleRate)
        outputFile = outFile
        record.startRecording()
        recording = true

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
                        Log.w(TAG, "read 오류 $n"); break
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                aec?.release(); aec = null
                writer.close()
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
