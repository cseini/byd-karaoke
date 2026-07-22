package com.cseini.byd.karaoke.voice

import android.content.Context
import android.content.Intent
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
import org.vosk.android.SpeechService
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import org.vosk.android.RecognitionListener as VoskListener

/**
 * 음성으로 검색어 입력(한국어).
 * 시스템 SpeechRecognizer(GMS)가 있으면 그걸 쓰고, 없으면(BYD DiLink 등) Vosk 오프라인 모델로 인식한다.
 * (구형 WebView 라 온라인 STT(puter)가 로드조차 안 되므로 네이티브 오프라인이 유일한 길.)
 * 정확도는 유튜브 검색의 fuzzy 매칭이 상당 부분 보정한다.
 */
class VoiceSearch(private val context: Context, private val settings: SettingsStore) {

    companion object {
        private const val RECORD_MS = 5000L   // 노래 제목 말하기엔 5초면 충분
        private const val SAMPLE_RATE = 16000.0f
        @Volatile private var voskModel: Model? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var speechService: SpeechService? = null

    fun isAvailable(): Boolean = true

    /** onReady=듣기 시작, onProcessing=녹음 끝나 전사 중, onResult/onError=결과. */
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
        speechService?.run { runCatching { stop() }; runCatching { shutdown() } }
        speechService = null
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

    // ── 경로 2: Vosk 오프라인(한국어 소형 모델) ───────────────────────

    private fun startVosk(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val cached = voskModel
        if (cached != null) {
            beginVosk(cached, onReady, onResult, onError)
            return
        }
        // 첫 사용: assets 의 모델 zip 을 내부저장에 풀고 로드(수십 초 걸릴 수 있음).
        onProcessing()
        thread(name = "vosk-init") {
            try {
                val dir = File(context.filesDir, "vosk-model")
                if (!File(dir, "am/final.mdl").exists()) unpackModel(dir)
                val model = Model(dir.absolutePath)
                voskModel = model
                main.post { beginVosk(model, onReady, onResult, onError) }
            } catch (e: Exception) {
                main.post { onError("음성모델 준비 실패: ${e.message}") }
            }
        }
    }

    private fun beginVosk(
        model: Model,
        onReady: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        speechService?.run { runCatching { stop() }; runCatching { shutdown() } }
        speechService = null
        try {
            val rec = Recognizer(model, SAMPLE_RATE)
            val svc = SpeechService(rec, SAMPLE_RATE)
            speechService = svc
            onReady()
            svc.startListening(object : VoskListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) {}
                override fun onFinalResult(hypothesis: String?) {
                    val text = parseText(hypothesis)
                    if (text.isEmpty()) onError("말소리를 인식하지 못했습니다") else onResult(text)
                }
                override fun onError(e: Exception?) = onError("음성 인식 오류: ${e?.message}")
                override fun onTimeout() {}
            })
            // 5초 뒤 멈추면 onFinalResult 로 결과가 온다.
            main.postDelayed({ speechService?.let { runCatching { it.stop() } } }, RECORD_MS)
        } catch (e: Exception) {
            onError("음성 인식 시작 실패: ${e.message}")
        }
    }

    /** assets/model-ko.zip → filesDir/vosk-model 로 압축 해제. */
    private fun unpackModel(dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        context.assets.open("model-ko.zip").use { input ->
            ZipInputStream(input).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val out = File(dir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
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
