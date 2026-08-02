package com.cseini.byd.karaoke.scoring

import android.util.Base64
import android.util.Log
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.voice.VoiceSearch
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini 로 노래 채점(옵션). 목소리 트랙 60초 발췌(16kHz 다운샘플)를 보내 점수+한 줄 코멘트를 받는다.
 * 음성검색과 같은 무료 한도를 쓰므로(모델당 RPD 20) 실패·한도초과 시 null → 기본 채점 폴백.
 * 요청 형식은 차에서 검증된 VoiceSearch 와 동일(text part 먼저, inline_data 다음).
 */
object AiScorer {

    private const val TAG = "karaoke-ai-score"
    private const val EXCERPT_SEC = 60
    private const val TARGET_RATE = 16000
    private const val BUDGET_MS = 30_000L   // 전체 시도 시간 상한 — 채점 화면을 너무 오래 잡지 않게

    /** 항목별 배점은 앱 자체 채점과 같은 체계(30/20/25/10/15) — 총점은 합계. */
    data class AiScore(
        val pitch: Int,      // 0..30
        val stability: Int,  // 0..20
        val beat: Int,       // 0..25
        val volume: Int,     // 0..10
        val vibrato: Int,    // 0..15
        val comment: String,
    ) {
        val total: Int get() = (pitch + stability + beat + volume + vibrato).coerceIn(0, 100)

        fun breakdownText(): String = buildString {
            append("🤖 AI 채점 (Gemini가 직접 듣고 평가)\n")
            append("· 멜로디 음정 $pitch/30\n")
            append("· 음정 안정성 $stability/20\n")
            append("· 박자 규칙성 $beat/25\n")
            append("· 음량 표현 $volume/10\n")
            append("· 고음·비브라토 $vibrato/15\n\n")
            append("심사평: “$comment”")
        }
    }

    /** 마지막 실패 사유(진단용) — 성공 시 null. 채점 화면에 그대로 보여 제보받는다. */
    @Volatile var lastError: String? = null

    suspend fun score(settings: SettingsStore, samples: FloatArray, sampleRate: Int): AiScore? =
        withContext(Dispatchers.IO) {
            lastError = null
            val keys = settings.geminiApiKeys()
            if (keys.isEmpty() || samples.isEmpty() || sampleRate <= 0) {
                lastError = "키 없음/입력 없음"; return@withContext null
            }
            val wav = excerptWav(samples, sampleRate)
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            Log.i(TAG, "excerpt=${wav.size / 1024}KB b64=${b64.length / 1024}KB")
            val prompt = "당신은 노래방 심사위원입니다. 첨부 오디오는 차량 노래방 마이크 트랙입니다. " +
                "⚠️ 가장 먼저 판정할 것: 사람이 마이크에 대고 '직접 부른' 목소리가 뚜렷이 있는가? " +
                "마이크에는 차 스피커에서 새어 들어온 반주·원곡이 작게 섞여 있을 수 있습니다. " +
                "가까이서 녹음된 생생한 사람 목소리 없이 멀리서 들리는 음악·원곡 가수 소리만 있다면 sang=false 로 하고 " +
                "모든 항목을 0~3점으로 주세요(comment: \"노래가 감지되지 않았어요. 마이크에 대고 불러주세요!\"). " +
                "sang=true 인 경우에만 다음 5개 항목을 채점: pitch=멜로디 음정(0~30), stability=음정 안정성(0~20), " +
                "beat=박자 규칙성(0~25), volume=음량 표현(0~10), vibrato=고음·비브라토(0~15). " +
                "노래방 점수처럼 후하게 — 평균 실력이면 합계가 75~85, 잘 부르면 90 이상. " +
                "대신 곡마다·항목마다 차이가 나게 정밀하게. 원곡 가수처럼 완벽한 스튜디오 음질이면 오히려 의심하고 sang 을 재검토하세요. " +
                "심사평(comment)은 한국어 25~60자로, 잘한 점 1가지와 보완할 점 1가지를 구체적으로 " +
                "(예: \"고음 뻗음이 시원해요. 후렴에서 박자가 살짝 밀리니 반주를 더 들어보세요.\"). " +
                "\"열창!\" 같은 한두 단어 감탄사는 금지. " +
                "다른 말 없이 JSON만 출력: " +
                "{\"sang\": true|false, \"pitch\": 정수, \"stability\": 정수, \"beat\": 정수, \"volume\": 정수, \"vibrato\": 정수, \"comment\": \"심사평\"}"
            // 차에서 검증된 VoiceSearch 페이로드 구조 그대로(JSONObject, text 먼저).
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
            ).toString()
            val models = VoiceSearch.MODELS[settings.geminiModel] ?: VoiceSearch.MODELS.getValue("flash")

            val t0 = android.os.SystemClock.elapsedRealtime()
            var err = ""
            for (model in models) {
                var modelMissing = false
                for (key in keys) {
                    if (modelMissing) break
                    if (android.os.SystemClock.elapsedRealtime() - t0 > BUDGET_MS) {
                        lastError = err.ifEmpty { "시간 초과(네트워크 느림)" }
                        return@withContext null
                    }
                    val attempt = runCatching { call(model, key, payload) }
                    val res = attempt.getOrNull()
                    if (res == null) {
                        val e = attempt.exceptionOrNull()
                        err = "${e?.javaClass?.simpleName}: ${e?.message?.take(80)}"
                        Log.w(TAG, "$model $err")
                        continue
                    }
                    when (res.first) {
                        200 -> {
                            parse(res.second)?.let { return@withContext it }
                            err = "응답 파싱 실패: ${res.second.take(120)}"
                            Log.w(TAG, err)
                        }
                        404 -> modelMissing = true
                        429 -> err = "한도 초과(429)"
                        else -> {
                            err = "http=${res.first} ${res.second.take(120)}"
                            Log.w(TAG, "$model $err")
                        }
                    }
                }
            }
            lastError = err.ifEmpty { "알 수 없는 실패" }
            null
        }

    private fun call(model: String, key: String, body: String): Pair<Int, String> {
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 25_000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = runCatching {
            (if (code == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun parse(body: String): AiScore? = runCatching {
        val root = Gson().fromJson(body, JsonObject::class.java)
        var text = root.getAsJsonArray("candidates").get(0).asJsonObject
            .getAsJsonObject("content").getAsJsonArray("parts").get(0).asJsonObject
            .get("text").asString.trim()
        // 모델이 ```json 펜스나 부연을 붙여도 첫 { .. 마지막 } 만 취해 파싱
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s >= 0 && e > s) text = text.substring(s, e + 1)
        val j = Gson().fromJson(text, JsonObject::class.java)
        // 직접 부른 목소리가 없다고 판정되면(반주 유출만 녹음) 항목 상한을 강제로 3점으로.
        val sang = j.get("sang")?.asBoolean ?: true
        fun cap(v: Int, max: Int) = if (sang) v.coerceIn(0, max) else v.coerceIn(0, 3)
        AiScore(
            pitch = cap(j.get("pitch").asInt, 30),
            stability = cap(j.get("stability").asInt, 20),
            beat = cap(j.get("beat").asInt, 25),
            volume = cap(j.get("volume").asInt, 10),
            vibrato = cap(j.get("vibrato").asInt, 15),
            comment = j.get("comment")?.asString.orEmpty().take(120),
        )
    }.getOrNull()

    /** 중간 60초 발췌(전주 회피: 15초 이후부터) → 16kHz 다운샘플 → 16bit PCM mono WAV. */
    private fun excerptWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val maxN = EXCERPT_SEC * sampleRate
        val start = if (samples.size > maxN + 15 * sampleRate) 15 * sampleRate
        else ((samples.size - maxN) / 2).coerceAtLeast(0)
        val n = minOf(maxN, samples.size - start)
        // 선형 보간 다운샘플(음질보다 크기·업로드 속도가 중요)
        val outN = (n.toLong() * TARGET_RATE / sampleRate).toInt().coerceAtLeast(1)
        val out = ByteArrayOutputStream(44 + outN * 2)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
        out.write("RIFF".toByteArray()); le32(36 + outN * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(TARGET_RATE); le32(TARGET_RATE * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(outN * 2)
        val step = sampleRate.toDouble() / TARGET_RATE
        for (i in 0 until outN) {
            val pos = start + i * step
            val i0 = pos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (pos - i0).toFloat()
            val v = samples[i0] * (1 - frac) + samples[i1] * frac
            le16(((v.coerceIn(-1f, 1f)) * 32767).toInt() and 0xFFFF)
        }
        return out.toByteArray()
    }
}
