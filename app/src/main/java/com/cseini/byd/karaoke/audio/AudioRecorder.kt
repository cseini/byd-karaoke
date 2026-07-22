package com.cseini.byd.karaoke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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

        val record = try {
            AudioRecord(
                sourceOverride ?: settings.micSourceConst(), sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) {
            return "AudioRecord 생성 실패: ${e.message}"
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "AudioRecord 초기화 실패(소스 미지원 가능)"
        }

        if (settings.preferUsbMic) findUsbInput()?.let { record.setPreferredDevice(it) }
        if (forceEffects || settings.aecEnabled) {
            val sid = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable())
                aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull()
            if (forceEffects) {
                // 음성검색: 노이즈 억제 + 자동 게인까지 켜 에코·잡음을 최대한 제거.
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

    private fun findUsbInput(): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
}
