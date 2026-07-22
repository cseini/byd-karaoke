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
 * → 다시듣기는 이 파일 하나만 재생하므로 싱크가 어긋날 수 없다.
 *
 * 핵심: 반주 PCM 을 곡 시간순으로 통째로 담아두고, 마이크 녹음의 매 순간
 * ExoPlayer 의 실제 재생 위치(positionProvider)를 읽어 그 위치의 반주와 합친다.
 * 재생 시작 타이밍/버퍼 지터와 무관하게 항상 정확히 정렬된다(프리필 불필요).
 */
@UnstableApi
class MixRecorder(
    private val context: Context,
    private val settings: SettingsStore,
) {
    companion object {
        private const val RATE = 44100
        private const val MAX_SECONDS = 480   // 곡당 최대 8분치 반주 버퍼
    }

    @Volatile private var recording = false
    private var worker: Thread? = null
    var outputFile: File? = null
        private set
    val isRecording: Boolean get() = recording

    // 반주 PCM(모노 44.1k)을 곡 시간순으로 저장. 디코딩 순서 = 재생 시간순(0부터).
    private val accompBuffer = ShortArray(RATE * MAX_SECONDS)
    private val accompWrite = AtomicInteger(0)
    @Volatile private var accompRate = RATE
    @Volatile private var accompCh = 2
    @Volatile private var accompPcm16 = true

    /** 현재 재생 위치(ms) 제공자 — 반주를 이 위치에 맞춰 믹스한다. */
    var positionProvider: (() -> Long)? = null

    /** StreamPlayer 의 ExoPlayer 오디오 체인에 넣어 반주 PCM 을 시간순으로 담는다(패스스루). */
    val accompProcessor: AudioProcessor = object : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            accompRate = inputAudioFormat.sampleRate
            accompCh = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2
            accompPcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            if (recording && accompPcm16) appendAccomp(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        }
    }

    private fun appendAccomp(bb: ByteBuffer) {
        val mono = ArrayList<Short>(bb.remaining() / 2)
        while (bb.remaining() >= 2 * accompCh) {
            var sum = 0
            for (c in 0 until accompCh) sum += bb.short.toInt()
            mono.add((sum / accompCh).toShort())
        }
        val resampled = if (accompRate == RATE) mono else resample(mono, accompRate, RATE)
        var w = accompWrite.get()
        for (s in resampled) if (w < accompBuffer.size) accompBuffer[w++] = s
        accompWrite.set(w)
    }

    @SuppressLint("MissingPermission")
    fun start(outFile: File, onLevel: ((Float) -> Unit)? = null): String? {
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

        accompWrite.set(0)
        val writer = WavIo.Writer(outFile, RATE)
        outputFile = outFile
        record.startRecording()
        recording = true

        worker = thread(name = "mix-recorder") {
            val buf = ShortArray(minBuf)
            val out = ShortArray(minBuf)
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        // 이 블록의 시작 재생 위치 → 반주 버퍼 인덱스.
                        val posMs = positionProvider?.invoke() ?: 0L
                        val base = (posMs * RATE / 1000L).toInt()
                        val wlimit = accompWrite.get()
                        for (i in 0 until n) {
                            val voice = buf[i].toInt()
                            val ai = base + i
                            val acc = if (ai in 0 until wlimit) accompBuffer[ai].toInt() else 0
                            var m = voice + (acc * 6 / 10)   // 반주는 살짝 낮춰 합성
                            if (m > 32767) m = 32767 else if (m < -32768) m = -32768
                            out[i] = m.toShort()
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

    /** srcRate → dstRate 선형보간 리샘플(모노). */
    private fun resample(input: List<Short>, srcRate: Int, dstRate: Int): List<Short> {
        if (input.isEmpty()) return input
        val ratio = dstRate.toDouble() / srcRate
        val outLen = (input.size * ratio).toInt()
        val out = ArrayList<Short>(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = input[idx.coerceIn(0, input.size - 1)].toInt()
            val s1 = input[(idx + 1).coerceIn(0, input.size - 1)].toInt()
            out.add((s0 + (s1 - s0) * frac).toInt().toShort())
        }
        return out
    }

    private fun findUsbInput(): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
}
