package com.cseini.byd.karaoke.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.cseini.byd.karaoke.audio.AudioRecorder
import com.cseini.byd.karaoke.data.SettingsStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 음성으로 검색어 입력(한국어).
 * 시스템 SpeechRecognizer(GMS)가 있으면 그걸 쓰고, 없으면(BYD DiLink 등) Gemini 로 인식한다.
 * Gemini 는 녹음한 오디오를 직접 듣고 "가수/노래 제목"을 검색어 텍스트로 돌려준다
 * (구형 WebView 로 puter STT 불가, Vosk 오프라인은 정확도 부족 → 온라인 Gemini 로 통일).
 */
class VoiceSearch(private val context: Context, private val settings: SettingsStore) {

    companion object {
        private const val RECORD_MS = 5000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recorder: AudioRecorder? = null
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    }

    fun isAvailable(): Boolean = true

    /** onReady=듣기 시작, onProcessing=전사 중, onResult/onError=결과, onLevel=마이크 입력 레벨(dBFS). */
    fun start(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {},
    ) {
        when {
            SpeechRecognizer.isRecognitionAvailable(context) ->
                startSystem(onReady, onProcessing, onResult, onError, onLevel)
            settings.openaiApiKey.isNotBlank() ->
                startGemini(onReady, onProcessing, onResult, onError, onLevel)
            else ->
                onError("음성검색을 쓰려면 설정에서 Gemini API 키(무료)를 넣으세요.")
        }
    }

    fun stop() {
        recognizer?.run { runCatching { stopListening() }; runCatching { destroy() } }
        recognizer = null
        recorder?.let { runCatching { it.stop() } }
        recorder = null
    }

    // ── 경로 1: 시스템 SpeechRecognizer(GMS 있는 기기) ─────────────────

    private fun startSystem(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit,
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
            // 시스템 STT 의 RMS(대략 0~10)를 dBFS 근사로 변환해 레벨 표시에 넘긴다.
            override fun onRmsChanged(rmsdB: Float) { onLevel(rmsdB * 5f - 50f) }
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

    // ── 경로 2: Gemini — 오디오를 듣고 가수/노래 제목을 검색어 텍스트로 리턴 ──

    private fun startGemini(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ) {
        stop()
        val file = File(context.cacheDir, "voice_query.wav")
        // 음성검색: USB 마이크는 입 가까이라 깨끗하므로 원음(UNPROCESSED)으로 받는다.
        // (VOICE_COMMUNICATION 의 에코제거/AGC 는 내장마이크용이라 USB 원음을 왜곡해 인식이 나빴다.)
        val rec = AudioRecorder(
            context, settings, 16000,
            sourceOverride = android.media.MediaRecorder.AudioSource.UNPROCESSED,
            forceEffects = false,
        )
        recorder = rec
        // 마이크 입력 레벨을 실시간으로 UI 에 넘겨 소리가 들어오는지 보이게 한다.
        val err = rec.start(file) { db -> main.post { onLevel(db) } }
        if (err != null) { onError("마이크 오류: $err"); return }
        onReady()
        main.postDelayed({
            rec.stop()
            recorder = null
            onProcessing()
            thread(name = "gemini") { transcribeGemini(file, onResult, onError) }
        }, RECORD_MS)
    }

    private fun transcribeGemini(file: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        try {
            if (!file.exists() || file.length() < 2000) {
                main.post { onError("녹음이 감지되지 않았습니다") }; return
            }
            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val prompt = "이 오디오에는 한 사람이 대한민국 가요의 노래 제목이나 가수 이름을 한국어로 말합니다. " +
                "오디오를 잘 듣고, 실제로 들린 노래 제목 또는 가수 이름만 유튜브 검색어로 그대로 한 줄로 출력하세요. " +
                "들리는 내용만 쓰고 추측하지 마세요. 설명·따옴표·부연 없이 검색어만."
            val payload = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().put("mime_type", "audio/wav").put("data", b64),
                                ),
                            ),
                    ),
                ),
            )
            val model = if (settings.geminiModel == "flash-lite") "gemini-2.5-flash-lite" else "gemini-2.5-flash"
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${settings.openaiApiKey}")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            http.newCall(req).execute().use { resp ->
                val json = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 실제 원인(모델/키/권한)을 그대로 보여준다.
                    val emsg = runCatching { JSONObject(json).getJSONObject("error").getString("message") }
                        .getOrDefault(json.take(150))
                    main.post { onError("Gemini 오류 ${resp.code}: $emsg") }
                    return
                }
                val text = runCatching {
                    JSONObject(json).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                        .getString("text").trim()
                }.getOrDefault("")
                main.post { if (text.isEmpty()) onError("인식된 내용이 없습니다") else onResult(text) }
            }
        } catch (e: Exception) {
            main.post { onError("음성 인식 오류: ${e.message}") }
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
