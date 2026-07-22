package com.cseini.byd.karaoke.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cseini.byd.karaoke.data.SettingsStore
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * 음성으로 검색어 입력(한국어).
 * 시스템 SpeechRecognizer(GMS)가 있으면 그걸 쓰고, 없으면(BYD DiLink 등) Vosk 오프라인 모델로 인식한다.
 * Vosk 는 직접 AudioRecord 로 USB 마이크(입 가까이·깨끗)를 잡아 인식률을 높인다.
 * (SpeechService 기본은 내장 마이크라 차 안에서 인식이 나빴다.)
 */
class VoiceSearch(private val context: Context, private val settings: SettingsStore) {

    companion object {
        private const val RECORD_MS = 5000L
        private const val SAMPLE_RATE = 16000
        @Volatile private var voskModel: Model? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private var voskRec: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var voskRunning = false
    private var voskThread: Thread? = null

    fun isAvailable(): Boolean = true

    /** onReady=듣기 시작, onProcessing=전사 중, onResult/onError=결과. */
    fun start(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) startSystem(onReady, onProcessing, onResult, onError)
        else startVosk(onReady, onProcessing, onResult, onError)
    }

    fun stop() {
        recognizer?.run { runCatching { stopListening() }; runCatching { destroy() } }
        recognizer = null
        voskRunning = false
        voskThread?.join(1000)
        voskThread = null
        audioRecord?.run { runCatching { stop() }; runCatching { release() } }
        audioRecord = null
        voskRec?.run { runCatching { close() } }
        voskRec = null
    }

    // ── 경로 1: 시스템 SpeechRecognizer(GMS 있는 기기) ─────────────────

    private fun startSystem(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onReady()
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) onError("인식된 내용이 없습니다") else onResult(text)
            }
            override fun onError(error: Int) = onError(errorText(error))
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() = onProcessing()
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    // ── 경로 2: Vosk 오프라인(한국어 소형 모델) + USB 마이크 직접 ──────

    private fun startVosk(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val cached = voskModel
        if (cached != null) {
            beginVosk(cached, onReady, onProcessing, onResult, onError)
            return
        }
        // 첫 사용: 모델 zip 을 내부저장에 풀고 로드(수십 초).
        onProcessing()
        thread(name = "vosk-init") {
            try {
                val dir = File(context.filesDir, "vosk-model")
                if (!File(dir, "am/final.mdl").exists()) unpackModel(dir)
                val model = Model(dir.absolutePath)
                voskModel = model
                main.post { beginVosk(model, onReady, onProcessing, onResult, onError) }
            } catch (e: Exception) {
                main.post { onError("음성모델 준비 실패: ${e.message}") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginVosk(
        model: Model,
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        voskRec = rec
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { onError("오디오 버퍼 계산 실패"); return }
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) { onError("마이크 열기 실패: ${e.message}"); return }
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); onError("마이크 초기화 실패"); return }
        // USB 마이크(입 가까이)로 라우팅 — 내장 마이크보다 인식률이 훨씬 높다.
        findUsbInput()?.let { ar.setPreferredDevice(it) }
        audioRecord = ar

        ar.startRecording()
        voskRunning = true
        onReady()
        voskThread = thread(name = "vosk-rec") {
            val buf = ShortArray(minBuf)
            while (voskRunning) {
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) runCatching { rec.acceptWaveForm(buf, n) }
                else if (n < 0) break
            }
            val text = parseText(runCatching { rec.finalResult }.getOrNull())
            main.post {
                if (!isStopped()) {
                    if (text.isEmpty()) onError("말소리를 인식하지 못했습니다 (또박또박 말해보세요)")
                    else onResult(text)
                }
            }
        }
        // 5초 뒤 멈추면 위 스레드가 finalResult 로 결과를 낸다.
        main.postDelayed({ if (voskRunning) { onProcessing(); voskRunning = false } }, RECORD_MS)
    }

    private fun isStopped(): Boolean = voskRec == null

    /** assets/model-ko.zip → filesDir/vosk-model 로 압축 해제. */
    private fun unpackModel(dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        context.assets.open("model-ko.zip").use { input ->
            ZipInputStream(input).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val out = File(dir, entry.name)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                    entry = zin.nextEntry
                }
            }
        }
    }

    private fun parseText(hypothesis: String?): String =
        runCatching { JSONObject(hypothesis ?: "{}").optString("text", "").trim() }.getOrDefault("")

    private fun findUsbInput(): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "오디오 오류"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한 필요"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
        SpeechRecognizer.ERROR_NO_MATCH -> "인식 실패(다시 말해주세요)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
        SpeechRecognizer.ERROR_SERVER -> "서버 오류"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 없습니다"
        else -> "오류($error)"
    }
}
