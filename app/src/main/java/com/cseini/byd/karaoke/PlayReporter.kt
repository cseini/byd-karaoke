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

    // 슬랴+·Charreve 같은 가사채널은 제목·포맷이 제각각이라 간주점프 검출(TJ/금영 표준
    // 포맷에 의존)이 안 맞을 수 있다 — YouTubeRepository.keepKaraoke(검색 필터, 더 넓음)와
    // 별개로 TJ·금영만 정확히 좁힌다.
    private val tjKyMarkers = listOf("TJ노래방", "TJ Karaoke", "금영", "KY Karaoke", "KY ENTERTAINMENT")

    private fun isTjOrKy(title: String) = tjKyMarkers.any { title.contains(it, ignoreCase = true) }

    fun report(videoId: String, title: String) {
        if (videoId.isBlank() || !isTjOrKy(title)) return
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
