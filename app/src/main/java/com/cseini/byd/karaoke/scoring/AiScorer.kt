package com.cseini.byd.karaoke.scoring

import android.util.Base64
import android.util.Log
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.voice.VoiceSearch
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini 로 노래 채점(옵션). 목소리 트랙 90초 발췌를 보내 점수+한 줄 코멘트를 받는다.
 * 음성검색과 같은 무료 한도를 쓰므로(모델당 RPD 20) 실패·한도초과 시 null → 기본 채점 폴백.
 */
object AiScorer {

    private const val TAG = "karaoke-ai-score"
    private const val EXCERPT_SEC = 90

    data class AiScore(val total: Int, val comment: String)

    suspend fun score(settings: SettingsStore, samples: FloatArray, sampleRate: Int): AiScore? =
        withContext(Dispatchers.IO) {
            val keys = settings.geminiApiKeys()
            if (keys.isEmpty() || samples.isEmpty() || sampleRate <= 0) return@withContext null
            val wav = excerptWav(samples, sampleRate)
            val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            val prompt = "당신은 노래방 심사위원입니다. 첨부 오디오는 사용자가 노래방에서 부른 목소리 트랙입니다(반주 제거됨). " +
                "음정·박자·표현력·성량을 종합해 0~100점으로 채점하세요. 노래방 점수처럼 후하게(평균이면 75~85, 잘 부르면 90+), " +
                "대신 곡마다 차이가 나게 정밀하게 주세요. 한국어 한 줄 코멘트도 함께. " +
                "JSON만 출력: {\"score\": 정수, \"comment\": \"한 줄\"}"
            val body = """{"contents":[{"parts":[{"inline_data":{"mime_type":"audio/wav","data":"$b64"}},{"text":${Gson().toJson(prompt)}}]}],"generationConfig":{"response_mime_type":"application/json","temperature":0.3}}"""
            val models = VoiceSearch.MODELS[settings.geminiModel] ?: VoiceSearch.MODELS.getValue("flash")

            for (model in models) {
                var modelMissing = false
                for (key in keys) {
                    if (modelMissing) break
                    val res = runCatching { call(model, key, body) }.getOrNull() ?: continue
                    when {
                        res.first == 200 -> parse(res.second)?.let { return@withContext it }
                        res.first == 404 -> modelMissing = true          // 이 계정에 없는 모델 → 다음 모델
                        res.first == 429 -> {}                            // 한도 → 다음 키
                        else -> Log.i(TAG, "$model http=${res.first}")
                    }
                }
            }
            null
        }

    private fun call(model: String, key: String, body: String): Pair<Int, String> {
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
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
        val text = root.getAsJsonArray("candidates").get(0).asJsonObject
            .getAsJsonObject("content").getAsJsonArray("parts").get(0).asJsonObject
            .get("text").asString
        val j = Gson().fromJson(text, JsonObject::class.java)
        val score = j.get("score").asInt.coerceIn(0, 100)
        val comment = j.get("comment")?.asString.orEmpty().take(120)
        AiScore(score, comment)
    }.getOrNull()

    /** 중간 90초 발췌(전주 회피: 15초 이후부터) → 16bit PCM mono WAV. */
    private fun excerptWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val maxN = EXCERPT_SEC * sampleRate
        val start = if (samples.size > maxN + 15 * sampleRate) 15 * sampleRate
        else ((samples.size - maxN) / 2).coerceAtLeast(0)
        val n = minOf(maxN, samples.size - start)
        val out = ByteArrayOutputStream(44 + n * 2)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
        out.write("RIFF".toByteArray()); le32(36 + n * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(n * 2)
        for (i in start until start + n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            le16(v and 0xFFFF)
        }
        return out.toByteArray()
    }
}
