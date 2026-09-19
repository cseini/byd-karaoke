package com.cseini.byd.karaoke

import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

/**
 * 노래방 모드 재생을 서버(D1 played_songs)에 보고 — 간주점프 자동화의 입력.
 * 사용자가 많이 부르는 TJ/금영 곡부터 맥미니 배치가 자동으로 검출·데이터화한다
 * (intro-jump-detection 메모). 실패해도 조용히 무시(LogUploader 와 같은 패턴).
 */
object PlayReporter {
    private const val ENDPOINT = "https://karaoke.usenu.kr/api/play"

    fun report(videoId: String, title: String) {
        if (videoId.isBlank()) return
        val body = Gson().toJson(mapOf("videoId" to videoId, "title" to title))
        Thread {
            runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.use { it.readBytes() }
            }
        }.start()
    }
}
