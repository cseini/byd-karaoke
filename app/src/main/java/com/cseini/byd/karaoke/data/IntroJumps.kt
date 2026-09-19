package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 간주점프 데이터(videoId -> 점프 지점 초). 맥미니 배치(scripts/introjump/)가
 * TJ·금영 인트로에서 "첫 가사(로마자 발음줄) 등장" 을 OCR 로 검출해 만든 정적
 * JSON(karaoke.usenu.kr/introjumps.json)을 읽는다. 서버 조회 없이 로컬 캐시로
 * 즉시 쓸 수 있고, 앱 시작 시 갱신만 시도한다.
 */
object IntroJumps {
    private const val URL_STR = "https://karaoke.usenu.kr/introjumps.json"
    private const val PREF = "intro_jumps"
    private const val KEY_JSON = "json"

    private val gson = Gson()
    @Volatile private var map: Map<String, Double> = emptyMap()
    private var loaded = false

    /** 현재 곡에 간주점프 데이터가 있으면 점프 지점(초), 없으면 null. */
    fun jumpSecFor(videoId: String): Double? = map[videoId]

    /** 로컬 캐시를 즉시 메모리에 올린다(네트워크 없이). 앱 시작 시 1회 호출. */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_JSON, null)?.let { parse(it) }
    }

    /** 원격에서 최신 데이터를 받아 캐시 갱신. 실패해도 조용히 무시(기존 캐시 유지). */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, body).apply()
        }
    }

    private fun parse(json: String) {
        runCatching {
            val type = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
            val raw: Map<String, Map<String, Any>> = gson.fromJson(json, type)
            map = raw.mapNotNull { (videoId, fields) ->
                (fields["jump_sec"] as? Double)?.let { videoId to it }
            }.toMap()
        }
    }
}
