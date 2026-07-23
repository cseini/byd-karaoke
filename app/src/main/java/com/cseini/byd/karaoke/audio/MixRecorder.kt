package com.cseini.byd.karaoke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.cseini.byd.karaoke.data.SettingsStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 반주(ExoPlayer 오디오)와 마이크 목소리를 한 트랙으로 합성 녹음한다.
 *
 * 핵심: 반주 PCM 을 재생 시작(곡 0초)부터 시간순으로 담고(accompBuffer[k] = 곡 k/rate 초),
 * 녹음은 시작 시점의 재생 위치(startPosMs)에서 반주를 읽기 시작해 마이크와 함께 순차 진행한다.
 * ExoPlayer 의 디코딩 앞섬(버퍼)과 무관하게 정확히 정렬된다.
 *
 * 오디오 콜백(queueInput)에서는 모노 변환만 한다(언더런 방지, 리샘플은 믹스 시 인덱스로).
 */
@UnstableApi
class MixRecorder(
    private val context: Context,
    private val settings: SettingsStore,
) {
    companion object {
        private const val RATE = 44100
        private const val MAX_ACCOMP_RATE = 48000
        private const val MAX_SECONDS = 360
    }

    @Volatile private var recording = false
    private var worker: Thread? = null
    var outputFile: File? = null
        private set
    val isRecording: Boolean get() = recording

    // 반주 PCM(모노, accompRate 기준)을 곡 시간순으로 저장. 재생 시작(onConfigure) 때 리셋/할당.
    private var accompBuffer = ShortArray(0)
    private val accompWrite = AtomicInteger(0)
    @Volatile private var accompRate = 44100
    @Volatile private var accompCh = 2
    @Volatile private var accompPcm16 = true

    /** ExoPlayer 오디오 체인에 넣어 반주 PCM 을 재생 시작부터 담는다(패스스루). */
    val accompProcessor: AudioProcessor = object : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            accompRate = inputAudioFormat.sampleRate
            accompCh = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
            accompPcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
            // 새 재생 시작 → 반주 버퍼 리셋(곡 0초부터 다시 담는다).
            accompWrite.set(0)
            if (accompBuffer.size != MAX_ACCOMP_RATE * MAX_SECONDS) {
                accompBuffer = runCatching { ShortArray(MAX_ACCOMP_RATE * MAX_SECONDS) }.getOrDefault(ShortArray(0))
            }
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            if (accompBuffer.isNotEmpty()) appendAccomp(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        }
    }

    /** 오디오 콜백: 모노 변환만(할당·리샘플 없음). 16bit·float 모두 처리. */
    private fun appendAccomp(bb: ByteBuffer) {
        var w = accompWrite.get()
        val ch = accompCh
        val buf = accompBuffer
        val size = buf.size
        if (accompPcm16) {
            while (bb.remaining() >= 2 * ch && w < size) {
                var sum = 0
                for (c in 0 until ch) sum += bb.short.toInt()
                buf[w++] = (sum / ch).toShort()
            }
        } else {
            while (bb.remaining() >= 4 * ch && w < size) {
                var sum = 0f
                for (c in 0 until ch) sum += bb.float
                var v = (sum / ch * 32767f).toInt()
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                buf[w++] = v.toShort()
            }
        }
        accompWrite.set(w)
    }

    /** startPosMs = 녹음 시작 시점의 재생 위치(ms). 여기서부터 반주를 읽는다. */
    @SuppressLint("MissingPermission")
    fun start(outFile: File, startPosMs: Long, onLevel: ((Float) -> Unit)? = null): String? {
        if (recording) return "이미 녹음 중"
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "AudioRecord 버퍼 계산 실패($minBuf)"
        val record = try {
            AudioRecord(
                settings.micSourceConst(), RATE,
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

        val writer = WavIo.Writer(outFile, RATE)
        outputFile = outFile
        record.startRecording()
        recording = true

        worker = thread(name = "mix-recorder") {
            val buf = ShortArray(minBuf)
            val out = ShortArray(minBuf)
            // 녹음 시작 시점의 재생 위치에서 반주를 읽기 시작, 이후 연속 진행.
            var readPos = startPosMs * accompRate / 1000.0
            val step = accompRate.toDouble() / RATE
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        val wlimit = accompWrite.get()
                        for (i in 0 until n) {
                            val voice = buf[i].toInt()
                            val ai = readPos.toInt()
                            val acc = if (ai in 0 until wlimit) accompBuffer[ai].toInt() else 0
                            var m = voice + (acc * 6 / 10)
                            if (m > 32767) m = 32767 else if (m < -32768) m = -32768
                            out[i] = m.toShort()
                            readPos += step
                        }
                        writer.write(out, n)
                        onLevel?.let { cb ->
                            val f = FloatArray(n) { buf[it] / 32768f }
                            cb(PitchDetector.rmsDb(f))
                        }
                    } else if (n < 0) break
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                writer.close()
            }
        }
        return null
    }

    fun stop(): File? {
        if (!recording) return outputFile
        recording = false
        worker?.join(1500)
        worker = null
        return outputFile
    }

    fun debugInfo(): String = "반주 ${accompWrite.get()}샘플/${accompRate}Hz/pcm16=$accompPcm16"

    private fun findUsbInput(): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
}
