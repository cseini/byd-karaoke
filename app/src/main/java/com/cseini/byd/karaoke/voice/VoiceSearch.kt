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
        // VAD(무음 감지) 자동 종료 — 어절이 끝나고 잠깐 조용하면 자동으로 끊는다.
        // 이 이상이면 '말소리'로 간주. 씨라이언 순정마이크(BUS) 실측: 배경 -57~-62dB, 목소리 -35~-40dB.
        // -36 은 너무 빡빡해 -37dB 목소리를 무음 처리했다(v6.56 실측) → -45 로 낮춰 배경과 12dB 이상 벌린다.
        private const val SPEECH_DB = -45f
        private const val SILENCE_MS = 900L      // 말소리 뒤 이만큼 조용하면 종료
        private const val MIN_MS = 700L          // 최소 녹음 시간(너무 이른 종료 방지)
        private const val NO_SPEECH_MS = 4000L   // 이때까지 말이 없으면 포기
        private const val MAX_MS = 8000L         // 하드 상한(계속 말해도 여기서 끊음)
        // 마이크(USB/BUS)를 여는 순간의 초기 팝·노이즈가 '말소리'로 오인돼 즉시 종료되는 것을 막는
        // 워밍업 구간. 이 시간이 지나기 전의 입력은 VAD 에서 무시한다(마이크 안정화 대기).
        private const val WARMUP_MS = 700L
        // 자동 소스 폴백: 어떤 소스는 열리기만 하고(state OK) 완전 무음(-99)을 준다(씰 순정마이크 실측: src=9 무음,
        // src=6 은 -39~-48 로 잡힘). 주변음이 있는 정상 소스는 첫 구간부터 바닥이 -40~-62 다. 그래서 '이 아래면
        // 신호경로 없음'을 -80dB 로 잡아 '조용한 사용자'(-45~-62)와 구분한다.
        const val DEAD_DB = -80f
        const val DEAD_CHECK_MS = 1200L

        /** 자동 소스 폴백 판정 — 부수효과 없는 순수 함수(단위 테스트). 첫 ~1.2초가 완전 무음이면 다음 소스로. */
        fun shouldSwitchSource(elapsedMs: Long, maxDb: Float, hasSpeech: Boolean, hasNext: Boolean): Boolean =
            hasNext && !hasSpeech && elapsedMs > DEAD_CHECK_MS && maxDb < DEAD_DB

        /**
         * 음성인식 모델 폴백 순서(전부 무료 티어 · 한도는 모델별로 따로 = 모델당 하루 20회 수준).
         * `*-latest` 는 구글이 최신 세대를 가리키는 별칭이라 앱 수정 없이 성능이 따라 올라간다.
         * 한 모델의 하루 한도가 차면(429) 다음 모델로 내려가고, 선호 계열을 다 쓰면
         * 반대 계열까지 이어서 쓴다 → 키 1개로 8개 모델분(≈160회/일)을 확보.
         */
        // 실제 계정에서 확인된 모델 ID 기준(2026-07). 없는 모델은 404 → 자동으로 건너뛴다.
        private val FLASH = listOf(
            "gemini-flash-latest", "gemini-3.6-flash", "gemini-3.5-flash",
            "gemini-3.1-flash", "gemini-2.5-flash",
        )
        private val LITE = listOf(
            "gemini-flash-lite-latest", "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite", "gemini-2.5-flash-lite",
        )
        private val MODELS = mapOf(
            "flash" to (FLASH + LITE),
            "flash-lite" to (LITE + FLASH),
        )

        // 서버(Groq whisper-large-v3-turbo) STT 프록시 — 사용자 키 불필요.
        // 키가 없어 달리 방법이 없는 유닛(씨라이언 등)의 기본 경로이자, 설정에서 명시 선택 가능.
        private const val STT_SERVER = "https://karaoke.usenu.kr/api/stt"
        private const val STT_SECRET = "b13b80e18c631211fc5f7392f991f906"
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recorder: AudioRecorder? = null
    private var pendingStop: Runnable? = null       // 5초 뒤 전사 시작 예약(취소 시 제거)
    @Volatile private var cancelled = false          // 취소되면 결과 콜백을 무시
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)   // 짧게 끊고 아래에서 1회 재시도(차 네트워크 지터 대응)
            .build()
    }

    fun isAvailable(): Boolean = true

    /**
     * Gboard 마이크가 쓰는 것과 같은 "액티비티 방식" 음성인식(ACTION_RECOGNIZE_SPEECH).
     * 백그라운드 SpeechRecognizer(RecognitionService)가 없는 유닛에서도, 이 인텐트를
     * 처리하는 앱(구글 음성입력 등)이 있으면 API 키·네트워크 없이 무료로 인식된다.
     * 처리 앱이 없으면 null → 호출부가 Gemini 경로로 폴백한다.
     */
    fun activitySttIntent(): Intent? {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "노래 제목이나 가수를 말하세요")
        }
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /** onReady=듣기 시작, onProcessing=전사 중, onResult/onError=결과, onLevel=마이크 입력 레벨(dBFS). */
    fun start(
        onReady: () -> Unit,
        onProcessing: () -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Float) -> Unit = {},
    ) {
        cancelled = false
        when {
            // 서버(Groq) 선택 → 녹음은 공통(startGemini)이고 전사만 서버로 간다.
            settings.sttEngine == "server" ->
                startGemini(onReady, onProcessing, onResult, onError, onLevel)
            SpeechRecognizer.isRecognitionAvailable(context) ->
                startSystem(onReady, onProcessing, onResult, onError, onLevel)
            settings.geminiApiKeys().isNotEmpty() ->
                startGemini(onReady, onProcessing, onResult, onError, onLevel)
            // 키·시스템STT 가 전혀 없는 유닛(씨라이언 등)도 서버(Groq)로 시도한다 — 설정 없이 그냥 되게.
            else ->
                startGemini(onReady, onProcessing, onResult, onError, onLevel)
        }
    }

    /** 사용자가 취소(오버레이 탭) — 예약된 전사·결과 콜백까지 전부 중단. */
    fun stop() {
        cancelled = true
        teardown()
    }

    private fun teardown() {
        pendingStop?.let { main.removeCallbacks(it) }
        pendingStop = null
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
        teardown()
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
        teardown()
        val file = File(context.cacheDir, "voice_query.wav")
        // 시도할 마이크 소스 목록. 설정에서 소스를 지정했으면 그것만(사용자 선택 존중). '자동'이면 원음→음성인식→MIC
        // 순으로, 앞 소스가 완전 무음이면 다음으로 넘어간다(씰 순정마이크: src9 무음, src6 잡힘). 단 씨라이언
        // (BYD 가 USB 독점: com.byd.sing)에선 폴백 금지 — BUS 를 여러 소스로 붙잡으면 28초 행이 난다(v6.54).
        val bydOwns = runCatching { context.packageManager.getPackageInfo("com.byd.sing", 0); true }.getOrDefault(false)
        val forced = settings.forcedMicSource()
        val sources: List<Int> = when {
            forced != null -> listOf(forced)
            bydOwns -> listOf(android.media.MediaRecorder.AudioSource.UNPROCESSED)
            else -> listOf(
                android.media.MediaRecorder.AudioSource.UNPROCESSED,
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                android.media.MediaRecorder.AudioSource.MIC,
            )
        }
        var readyFired = false

        fun attempt(idx: Int) {
            val src = sources[idx]
            val hasNext = idx < sources.size - 1
            val rec = AudioRecorder(context, settings, 16000, sourceOverride = src, forceEffects = false)
            recorder = rec
            val startAt = android.os.SystemClock.elapsedRealtime()
            val speechAt = java.util.concurrent.atomic.AtomicLong(0L)
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val switching = java.util.concurrent.atomic.AtomicBoolean(false)   // 소스 전환 중 옛 recorder 콜백 무시용
            val usedSource = java.util.concurrent.atomic.AtomicInteger(-1)
            val levels = FloatArray(24) { -99f }
            var maxDb = -99f

            fun switchNext() {
                if (!switching.compareAndSet(false, true)) return
                com.cseini.byd.karaoke.CrashLog.event(context, "voice ${com.cseini.byd.karaoke.audio.MicRouting.sourceName(src)} 무음 → ${com.cseini.byd.karaoke.audio.MicRouting.sourceName(sources[idx + 1])} 자동전환")
                main.post {
                    pendingStop?.let { main.removeCallbacks(it) }; pendingStop = null
                    runCatching { rec.stop() }; recorder = null
                    attempt(idx + 1)
                }
            }

            fun finish(hadSpeech: Boolean) {
                if (switching.get()) return
                if (!finished.compareAndSet(false, true)) return
                val took = android.os.SystemClock.elapsedRealtime() - startAt
                com.cseini.byd.karaoke.CrashLog.event(context, "voice VAD 종료 hadSpeech=$hadSpeech src=${com.cseini.byd.karaoke.audio.MicRouting.sourceName(usedSource.get())} ${took}ms")
                val nb = ((took / 500) + 1).toInt().coerceIn(1, levels.size)
                com.cseini.byd.karaoke.CrashLog.event(
                    context,
                    "voice levels(0.5s,dB)=[" + levels.take(nb).joinToString(",") { it.toInt().toString() } + "] 기준 말소리>${SPEECH_DB.toInt()}",
                )
                main.post {
                    pendingStop?.let { main.removeCallbacks(it) }; pendingStop = null
                    runCatching { rec.stop() }; recorder = null
                    if (cancelled) return@post
                    if (!hadSpeech) { deliver { onError("말소리가 없습니다. 다시 시도하세요.") }; return@post }
                    onProcessing()
                    thread(name = "stt") { transcribeByEngine(file, onResult, onError) }
                }
            }

            val err = rec.start(file) { db ->
                if (switching.get() || finished.get()) return@start
                main.post { onLevel(db) }
                val now = android.os.SystemClock.elapsedRealtime()
                val elapsed = now - startAt
                val b = (elapsed / 500).toInt()
                if (b in levels.indices && db > levels[b]) levels[b] = db
                if (db > maxDb) maxDb = db
                if (elapsed > WARMUP_MS && db > SPEECH_DB) speechAt.set(now)
                val last = speechAt.get()
                // 완전 무음 소스면(주변음도 없음) 다음 소스로 자동 전환. 그 뒤 VAD 판단은 스킵.
                if (shouldSwitchSource(elapsed, maxDb, last != 0L, hasNext)) { switchNext(); return@start }
                when {
                    last != 0L && elapsed > MIN_MS && now - last > SILENCE_MS -> finish(true)
                    last == 0L && elapsed > NO_SPEECH_MS -> finish(false)
                    elapsed > MAX_MS -> finish(last != 0L)
                }
            }
            if (err != null) {
                if (hasNext) switchNext() else onError("마이크 오류: $err")
                return
            }
            usedSource.set(rec.getCurrentSource())
            com.cseini.byd.karaoke.CrashLog.event(context, "voice ready ${android.os.SystemClock.elapsedRealtime() - startAt}ms src=${com.cseini.byd.karaoke.audio.MicRouting.sourceName(src)}(#${idx + 1}/${sources.size})")
            if (!readyFired) { readyFired = true; onReady() }
            val guard = Runnable { finish(speechAt.get() != 0L) }
            pendingStop = guard
            main.postDelayed(guard, MAX_MS + 1500)
        }
        attempt(0)
    }

    /** 취소된 뒤에는 결과·오류 콜백을 전달하지 않는다(백그라운드 자동재생 방지). */
    private fun deliver(block: () -> Unit) = main.post { if (!cancelled) block() }

    /** 녹음이 작으면 Gemini 가 잘 못 알아들으므로 피크 기준으로 음량을 키운다(사실상 무음이면 스킵). */
    private fun normalizeWav(file: File) {
        runCatching {
            val b = file.readBytes()
            if (b.size <= 44) return
            var peak = 0
            var i = 44
            while (i + 1 < b.size) {
                val v = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                i += 2
            }
            if (peak < 80) return          // 사실상 무음/잡음 → 증폭하면 오히려 악화
            val target = 29000             // 약 0.9 풀스케일
            if (peak >= target) return     // 이미 충분히 큼
            val gain = (target.toDouble() / peak).coerceAtMost(12.0)
            i = 44
            while (i + 1 < b.size) {
                val v = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()
                val ng = (v * gain).toInt().coerceIn(-32768, 32767)
                b[i] = (ng and 0xFF).toByte()
                b[i + 1] = ((ng shr 8) and 0xFF).toByte()
                i += 2
            }
            file.writeBytes(b)
        }
    }

    /** 녹음된 WAV 를 설정 엔진으로 전사 — 서버(Groq)/gemini. 지연·결과를 로깅. */
    private fun transcribeByEngine(file: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        // 서버(Groq) 경로: 명시적 server 선택, 또는 키가 전혀 없어(씨라이언 등) 달리 방법이 없을 때.
        if (settings.sttEngine == "server" || settings.geminiApiKeys().isEmpty()) {
            transcribeServer(file, onResult, onError); return
        }
        transcribeGemini(file, onResult, onError)
    }

    /** 서버(Cloudflare Worker → Groq whisper-large-v3-turbo) 전사. 실패 시 gemini(키 있으면)로 폴백. */
    private fun transcribeServer(file: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!file.exists() || file.length() < 2000) { deliver { onError("녹음이 감지되지 않았습니다") }; return }
        normalizeWav(file)
        val device = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull() ?: "unknown"
        val url = "$STT_SERVER?k=$STT_SECRET&lang=ko&device=$device&ver=${com.cseini.byd.karaoke.BuildConfig.VERSION_NAME}"
        val t0 = android.os.SystemClock.elapsedRealtime()
        val req = Request.Builder().url(url)
            .post(file.readBytes().toRequestBody("audio/wav".toMediaTypeOrNull()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                val json = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val obj = runCatching { JSONObject(json) }.getOrNull()
                    val ok = obj?.optBoolean("ok") == true
                    val text = obj?.optString("text").orEmpty().trim()
                    val engine = obj?.optString("engine").orEmpty()
                    // whisper 무음/특수토큰([음악]·(박수) 등)·빈문자는 실패로 본다(무음 오인식 방지).
                    val bad = !ok || text.isBlank() || text.startsWith("[") || text.startsWith("(")
                    com.cseini.byd.karaoke.CrashLog.event(
                        context, "stt server ${ms}ms engine=$engine ok=$ok → '${text.take(50)}'",
                    )
                    if (bad) deliver { onError("말소리를 알아듣지 못했어요. 다시 시도해 주세요.") }
                    else deliver { onResult(text) }
                    return
                }
                // 429(운영자 Groq 계정 하루 무료 한도 ~50회 소진)·5xx·기타 → 폴백/안내
                val limited = resp.code == 429 ||
                    json.contains("rate", true) || json.contains("quota", true) ||
                    json.contains("limit", true) || json.contains("429")
                com.cseini.byd.karaoke.CrashLog.event(context, "stt server 실패 http=${resp.code} limited=$limited ${ms}ms → 폴백")
                serverFallback(file, onResult, onError, limited)
            }
        } catch (e: Exception) {
            com.cseini.byd.karaoke.CrashLog.event(context, "stt server 예외 ${e.message} → 폴백")
            serverFallback(file, onResult, onError, false)
        }
    }

    /**
     * 서버 실패 시 폴백. gemini 키가 있으면 gemini 로 처리(그대로 됨). 키가 없는데 '한도 소진(429)'이면
     * Groq(운영자 계정 공유, 하루 ~50회 무료)를 다 쓴 것이므로 Gemini/Gboard 로 바꾸라고 명확히 안내한다.
     */
    private fun serverFallback(file: File, onResult: (String) -> Unit, onError: (String) -> Unit, limited: Boolean) {
        if (settings.geminiApiKeys().isNotEmpty()) {
            com.cseini.byd.karaoke.CrashLog.event(context, "stt server→gemini 폴백 (limited=$limited)")
            transcribeGemini(file, onResult, onError)
        } else if (limited) {
            deliver { onError("오늘 무료 음성검색 한도(약 50회)를 다 썼어요.\n설정 → 음성 검색에서 'Gboard' 또는 'Gemini'로 바꿔주세요.") }
        } else {
            deliver { onError("음성 인식 서버에 연결하지 못했어요.\n인터넷 연결을 확인하고 다시 시도해 주세요.") }
        }
    }

    private fun transcribeGemini(file: File, onResult: (String) -> Unit, onError: (String) -> Unit) {
        try {
            if (!file.exists() || file.length() < 2000) {
                deliver { onError("녹음이 감지되지 않았습니다") }; return
            }
            normalizeWav(file)   // 음량이 작으면 키워서 인식률을 높인다
            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val prompt = "이 오디오에서 한 사람이 노래 제목이나 가수 이름을 말합니다. " +
                "한국어일 수도, 영어일 수도, 'I O I'·'BTS'처럼 알파벳을 하나씩 부르는 약자일 수도 있습니다. " +
                "들린 그대로 유튜브 검색어 한 줄로 출력하세요. " +
                "영어 이름·제목이나 알파벳을 부르는 경우는 억지로 한글로 바꾸지 말고 영어 철자 그대로 쓰세요(예: I.O.I, IVE, aespa, NewJeans). " +
                "한국어로 들리면 한국어로. 띄어쓰기와 명백한 오타만 다듬고, 들리지 않은 다른 곡을 지어내지 마세요. " +
                "설명·따옴표·부연 없이 검색어만."
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
            val keys = settings.geminiApiKeys()
            if (keys.isEmpty()) { deliver { onError("설정에서 Gemini API 키(무료)를 넣으세요.") }; return }
            // 모델 × 키 이중 폴백. 한도(RPD)는 모델별로 따로 잡히므로, 최신 모델부터 쓰고
            // 그 모델의 한도가 다 차면 아래 세대로 내려간다 → 무료 사용량이 사실상 합산된다.
            val models = MODELS[settings.geminiModel] ?: MODELS.getValue("flash")
            var lastErr = ""
            val payloadStr = payload.toString()
            for (model in models) {
                var allQuota = true
                for (key in keys) {
                    var r = requestGemini(key, payloadStr, model)
                    if (r is KeyResult.Fail && r.retryable) r = requestGemini(key, payloadStr, model)  // 타임아웃 1회 재시도
                    when (r) {
                        is KeyResult.Ok -> {
                            com.cseini.byd.karaoke.CrashLog.event(context, "stt gemini($model) → '${r.text.take(50)}'")
                            deliver { if (r.text.isEmpty()) onError("인식된 내용이 없습니다") else onResult(r.text) }
                            return
                        }
                        is KeyResult.Quota -> lastErr = r.msg           // 이 키는 소진 → 다음 키
                        is KeyResult.Fail -> {
                            if (r.retryModel) { lastErr = r.msg; allQuota = false; break }  // 이 모델을 못 씀 → 다음 모델
                            deliver { onError(if (r.retryable) "네트워크가 느려요 — 다시 시도하세요" else r.msg) }
                            return
                        }
                    }
                }
                if (!allQuota) continue   // 모델 자체 문제였으면 다음 모델로
            }
            deliver {
                onError(
                    if (lastErr.contains("불안정")) lastErr   // 전 모델이 서버 과부하였던 경우
                    else "오늘 무료 사용량을 다 썼어요.\n설정에서 키를 더 넣거나 내일 다시 시도해 주세요.",
                )
            }
        } catch (e: Exception) {
            deliver { onError("음성 인식 오류: ${e.message}") }
        }
    }

    private sealed class KeyResult {
        data class Ok(val text: String) : KeyResult()
        data class Quota(val msg: String) : KeyResult()
        /** retryable=네트워크 일시 오류(재시도) · retryModel=이 모델을 못 씀(다음 모델로) */
        data class Fail(val msg: String, val retryable: Boolean = false, val retryModel: Boolean = false) : KeyResult()
    }

    /**
     * 구글이 주는 영어 오류를 사용자가 이해할 한글 안내로 바꾼다.
     * (그대로 보여주면 "영어가 잔뜩 나온다"는 신고가 들어온다.)
     */
    private fun friendlyError(code: Int, msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("api key not valid") || m.contains("api_key_invalid") || m.contains("invalid api key") ->
                "API 키가 올바르지 않습니다.\n설정에서 Gemini 키를 다시 확인해 주세요."
            code == 403 || m.contains("permission") || m.contains("denied") ->
                "이 키로는 사용할 수 없습니다.\naistudio.google.com 에서 발급한 키인지 확인해 주세요."
            code == 400 && m.contains("user location") ->
                "이 지역에서는 사용할 수 없는 키입니다."
            code == 400 ->
                "요청이 거부되었습니다. 설정에서 키를 다시 저장해 보세요."
            code in 500..599 ->
                "구글 서버가 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요."
            else -> "음성 인식에 실패했습니다. (오류 $code)"
        }
    }

    /** 키 1개로 Gemini 호출. 한도 초과(429/RESOURCE_EXHAUSTED)면 Quota 로 반환해 다음 키를 쓰게 한다. */
    private fun requestGemini(key: String, payload: String, model: String): KeyResult {
        return try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                .post(payload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            http.newCall(req).execute().use { resp ->
                val json = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val emsg = runCatching { JSONObject(json).getJSONObject("error").getString("message") }
                        .getOrDefault(json.take(150))
                    val quota = resp.code == 429 ||
                        emsg.contains("RESOURCE_EXHAUSTED", true) || emsg.contains("quota", true)
                    // 그 모델이 없거나(404) 이 키로 못 쓰는 경우엔 다음 세대 모델로 넘어간다.
                    val badModel = resp.code == 404 ||
                        emsg.contains("not found", true) || emsg.contains("not supported", true)
                    // 진단은 로그로만 남기고(영문 원문), 화면엔 한글 안내를 보여준다.
                    android.util.Log.w("KaraokeVoice", "model=$model http=${resp.code} $emsg")
                    // 5xx/overloaded 는 보통 '그 모델'의 일시 과부하 → 다음 모델로 폴백해야 한다
                    val overloaded = resp.code in 500..599 ||
                        emsg.contains("overloaded", true) || emsg.contains("unavailable", true)
                    when {
                        quota -> KeyResult.Quota("한도 초과")
                        badModel -> KeyResult.Fail("모델 사용 불가", retryModel = true)
                        overloaded -> KeyResult.Fail(friendlyError(resp.code, emsg), retryModel = true)
                        else -> KeyResult.Fail(friendlyError(resp.code, emsg))
                    }
                } else {
                    val text = runCatching {
                        JSONObject(json).getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                            .getString("text").trim()
                    }.getOrDefault("")
                    KeyResult.Ok(text)
                }
            }
        } catch (e: Exception) {
            // 타임아웃/네트워크 예외는 재시도 가능으로 표시.
            android.util.Log.w("KaraokeVoice", "call failed: ${e.message}")
            KeyResult.Fail(
                if (e is java.io.IOException) "네트워크 연결을 확인해 주세요" else "음성 인식에 실패했습니다",
                retryable = e is java.io.IOException,
            )
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
