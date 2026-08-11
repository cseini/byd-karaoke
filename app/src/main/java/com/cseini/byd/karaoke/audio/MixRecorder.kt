package com.cseini.byd.karaoke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
        private const val MAX_ACCOMP_RATE = 48000
        private const val MAX_SECONDS = 360
        private const val ACCOMP_ADVANCE_MS = 36 // 실측: 반주가 36ms 늦어 그만큼 당겨 보정
    }

    // 녹음·저장 레이트(설정). 낮을수록 용량↓·채점↑빠름.
    private val rate = settings.recordRateHz

    @Volatile private var recording = false
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    var outputFile: File? = null
        private set
    val isRecording: Boolean get() = recording

    // 반주 PCM(모노, accompRate 기준)을 곡 시간순으로 저장. 재생 시작(onConfigure) 때 리셋/할당.
    private var accompBuffer = ShortArray(0)
    private val accompWrite = AtomicInteger(0)

    // 채점용 목소리(마이크 원음, rate 기준). 믹스가 아니라 목소리만 채점해 반주로 인한 고득점을 막는다.
    private var voiceBuffer = ShortArray(0)
    @Volatile private var voiceWrite = 0
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

    /**
     * startPosMs = 녹음 시작 시점의 재생 위치(ms). 여기서부터 반주를 읽는다.
     * speed = 재생 속도(1.0 기본). 반주 버퍼는 Sonic 뒤에서 따오므로 '들린 시간' 기준이라,
     * 재생위치(곡 시간)를 속도로 나눠 버퍼 위치로 환산한다(속도 1.0 이면 기존과 동일).
     */
    @SuppressLint("MissingPermission")
    fun start(outFile: File, startPosMs: Long, speed: Float = 1f, onLevel: ((Float) -> Unit)? = null): String? {
        if (recording) return "이미 녹음 중"
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "AudioRecord 버퍼 계산 실패($minBuf)"
        // USB 마이크 있으면 그걸, 없으면 차량 내장 통화 마이크로 폴백해서 연다. 사용자가 소스를 강제했으면 그걸 우선.
        // AUTO 기본은 MIC — 가장 호환성 좋은 소스라 유닛별 무음 문제(씨라 등 VOICE_RECOGNITION 무음)를 피한다.
        val opened = MicRouting.open(
            context, rate, minBuf * 2,
            settings.forcedMicSource(), android.media.MediaRecorder.AudioSource.MIC, settings.preferUsbMic
        ) ?: return "마이크를 열 수 없습니다(장치·권한 확인)"
        android.util.Log.d(
            "KaraokeMic",
            "model=${android.os.Build.MODEL} mfr=${android.os.Build.MANUFACTURER} " +
                "src=${settings.micSourceName} usb=${MicRouting.hasUsbMic(context)} builtin=${opened.builtin}",
        )
        val record = opened.record
        // 내장(통화) 마이크는 스피커 반주가 섞여 들어오므로 에코·잡음 제거를 켠다(믹스 이중 반주 방지).
        if (opened.builtin) {
            val sid = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable())
                aec = runCatching { AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull()
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(sid)?.enabled = true }
        }

        // 채점용 목소리 버퍼 준비(rate 기준, 최대 MAX_SECONDS).
        if (voiceBuffer.size != rate * MAX_SECONDS) {
            voiceBuffer = runCatching { ShortArray(rate * MAX_SECONDS) }.getOrDefault(ShortArray(0))
        }
        voiceWrite = 0

        val writer = WavIo.Writer(outFile, rate)
        outputFile = outFile
        record.startRecording()
        recording = true

        worker = thread(name = "mix-recorder") {
            val buf = ShortArray(minBuf)
            val out = ShortArray(minBuf)
            // 녹음 시작 위치 + 실측 보정 + 사용자 싱크 보정(마이크/재생 시스템 지연은 기기마다 다름).
            val s = if (speed > 0.05f) speed.toDouble() else 1.0
            var readPos = (startPosMs / s + ACCOMP_ADVANCE_MS + settings.syncOffsetMs) * accompRate / 1000.0
            val step = accompRate.toDouble() / rate
            // 목소리 처리: 명료도(고음 강조 pre-emphasis) + 에코(리버브)
            // 녹음 믹스 음량: 목소리(기본 1.0)와 반주(기본 0.6)를 설정으로 조절.
            val voiceGain = settings.voiceGainPct / 100.0
            val accompGain = settings.accompGainPct / 100.0
            val clarity = settings.voiceClarity / 100.0 * 0.9
            val echoDecay = settings.voiceEcho / 100.0 * 0.55
            val echoLen = (rate * 130 / 1000).coerceAtLeast(1)   // ~130ms 지연
            val echo = FloatArray(echoLen)
            var echoIdx = 0
            var prevX = 0.0
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        // 채점용 목소리 원음 저장(반주 섞기 전).
                        if (voiceBuffer.isNotEmpty()) {
                            val room = voiceBuffer.size - voiceWrite
                            val cnt = if (n < room) n else room
                            if (cnt > 0) {
                                System.arraycopy(buf, 0, voiceBuffer, voiceWrite, cnt)
                                voiceWrite += cnt
                            }
                        }
                        val wlimit = accompWrite.get()
                        for (i in 0 until n) {
                            val x = buf[i].toDouble()
                            var v = x + clarity * (x - prevX)    // 명료: 고음 강조
                            prevX = x
                            if (echoDecay > 0.0) {               // 에코
                                v += echoDecay * echo[echoIdx]
                                echo[echoIdx] = v.toFloat()
                                echoIdx = (echoIdx + 1) % echoLen
                            }
                            val ai = readPos.toInt()
                            val acc = if (ai in 0 until wlimit) accompBuffer[ai].toInt() else 0
                            var m = (v * voiceGain).toInt() + (acc * accompGain).toInt()
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
                runCatching { aec?.release() }; aec = null
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

    /** 채점용 목소리(마이크 원음). 추출하면서 바로 ≤12kHz 로 다운샘플해 메모리를 아낀다. */
    fun voiceForScoring(): Pair<FloatArray, Int> {
        val nSamp = voiceWrite.coerceAtMost(voiceBuffer.size)
        return decimateToFloat(voiceBuffer, nSamp, rate)
    }

    /** 반주(모노) 를 곡 시작부터, ≤12kHz 다운샘플. 4초 미만이면 비트 추정 불가 → null. */
    fun accompForScoring(): Pair<FloatArray, Int>? {
        val n = accompWrite.get().coerceAtMost(accompBuffer.size)
        if (n < accompRate * 4) return null
        return decimateToFloat(accompBuffer, n, accompRate)
    }

    /** PCM16 → 평균 데시메이션 float. 전체 해상도 float 복사(수십 MB)를 만들지 않는다. */
    private fun decimateToFloat(src: ShortArray, n: Int, rate: Int): Pair<FloatArray, Int> {
        val factor = Math.ceil(rate / 12000.0).toInt().coerceAtLeast(1)
        val outN = n / factor
        val out = FloatArray(outN)
        var i = 0
        for (k in 0 until outN) {
            var acc = 0
            for (j in 0 until factor) acc += src[i++]
            out[k] = acc / (32768f * factor)
        }
        return out to rate / factor
    }

    fun debugInfo(): String = "반주 ${accompWrite.get()}샘플/${accompRate}Hz/pcm16=$accompPcm16"
}
