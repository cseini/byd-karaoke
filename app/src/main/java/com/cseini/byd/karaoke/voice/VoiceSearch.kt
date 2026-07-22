package com.cseini.byd.karaoke.voice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cseini.byd.karaoke.audio.AudioRecorder
import com.cseini.byd.karaoke.data.SettingsStore
import java.io.File

/**
 * 음성으로 검색어 입력(한국어).
 * 시스템 SpeechRecognizer(GMS)가 있으면 그걸 쓰고, 없으면(DiLink 등) 마이크로 몇 초 녹음해
 * Puter Whisper(키·로그인 불필요, 온라인)로 전사한다. Vosk 소형 모델보다 정확도가 훨씬 높다.
 */
class VoiceSearch(private val context: Context, private val settings: SettingsStore) {

    companion object {
        private const val RECORD_MS = 5000L   // 노래 제목 말하기엔 5초면 충분
        private const val SAMPLE_RATE = 16000
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private var webView: WebView? = null
    @Volatile private var pageReady = false
    private var recorder: AudioRecorder? = null
    private var lastDataUrl: String? = null
    private var cbResult: ((String) -> Unit)? = null
    private var cbError: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = true   // Puter 는 네트워크만 있으면 됨

    fun start(onReady: () -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) startSystem(onReady, onResult, onError)
        else startWhisper(onReady, onResult, onError)
    }

    fun stop() {
        recognizer?.run { runCatching { stopListening() }; runCatching { destroy() } }
        recognizer = null
        recorder?.let { runCatching { it.stop() } }
        recorder = null
    }

    // ── 경로 1: 시스템 SpeechRecognizer(GMS 있는 기기) ─────────────────

    private fun startSystem(onReady: () -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
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
            override fun onEndOfSpeech() {}
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

    // ── 경로 2: Puter Whisper(오프라인 기기용, 온라인 전사) ─────────────

    @SuppressLint("MissingPermission")
    private fun startWhisper(onReady: () -> Unit, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val activity = context as? Activity ?: run { onError("음성 인식을 시작할 수 없습니다"); return }
        cbResult = onResult
        cbError = onError
        ensureWebView(activity)

        val file = File(context.cacheDir, "voice_query.wav")
        val rec = AudioRecorder(context, settings, SAMPLE_RATE)
        recorder = rec
        val err = rec.start(file, null)
        if (err != null) { onError("마이크 오류: $err"); return }
        onReady()
        main.postDelayed({ finishAndTranscribe(file) }, RECORD_MS)
    }

    private fun finishAndTranscribe(file: File) {
        recorder?.let { runCatching { it.stop() } }
        recorder = null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.size < 2000) { cbError?.invoke("녹음이 감지되지 않았습니다"); return }
        lastDataUrl = "data:audio/wav;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val wv = webView ?: run { cbError?.invoke("음성 인식 준비 실패"); return }
        val fire = { wv.evaluateJavascript("startTranscribe()", null) }
        if (pageReady) fire()
        else main.postDelayed(
            { if (pageReady) fire() else cbError?.invoke("음성 인식 준비 실패 (네트워크 확인)") },
            4000
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebView(activity: Activity) {
        if (webView != null) return
        val wv = WebView(activity)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "Android")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) { pageReady = true }
        }
        // 화면엔 보이지 않게 1x1 로 붙인다(백그라운드에서 전사만 수행).
        activity.addContentView(wv, ViewGroup.LayoutParams(1, 1))
        wv.loadUrl("file:///android_asset/puter_stt.html")
        webView = wv
    }

    private inner class Bridge {
        @JavascriptInterface fun getAudioDataUrl(): String = lastDataUrl ?: ""

        @JavascriptInterface fun onResult(text: String) {
            main.post {
                val t = text.trim()
                if (t.isEmpty()) cbError?.invoke("말소리를 인식하지 못했습니다") else cbResult?.invoke(t)
            }
        }

        @JavascriptInterface fun onError(msg: String) {
            main.post { cbError?.invoke("음성 인식 실패: $msg") }
        }
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
