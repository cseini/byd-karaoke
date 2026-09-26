package com.cseini.byd.karaoke

import android.content.Context
import com.cseini.byd.karaoke.data.SettingsStore
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 사용자 피드백 — BYD 공통 피드백 시스템(/feedback)으로 보낸다. 다른 BYD 앱(써드파티플레이어 등)과
 * 동일 규격. 예전 '로그 전송'(karaoke.usenu.kr/api/log, 사용자 메시지 없이 로그만)을 대체 —
 * 진단 기록은 이 화면의 첨부 옵션으로 흡수했다.
 */
object FeedbackSender {
    private const val ENDPOINT = "https://byd.cseini.co.kr/feedback"

    fun send(ctx: Context, category: String, message: String, attachLog: Boolean, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val settings = SettingsStore(app)
        val body = JSONObject()
            .put("app", "karaoke")
            .put("uid", CafeNick.deviceTag(app))
            .put("device", "headunit")
            .put("model", android.os.Build.MODEL)
            .put("nickname", settings.cafeNick)
            .put("appVer", BuildConfig.VERSION_NAME)
            .put("category", category)
            .put("message", message)
        if (attachLog) body.put("applog", applog(app))
        val payload = body.toString()
        val handler = android.os.Handler(app.mainLooper)
        Thread {
            val ok = runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "byd-karaoke")
                    connectTimeout = 15_000
                    readTimeout = 20_000
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.use { it.readBytes() }
                conn.responseCode in 200..299
            }.getOrDefault(false)
            handler.post { onDone(ok) }
        }.start()
    }

    /** 직전 크래시 + 최근 이벤트 로그 — LogUploader 가 읽던 것과 같은 파일. */
    private fun applog(ctx: Context): String = buildString {
        val crash = runCatching { File(ctx.filesDir, "crash.txt").readText().take(6000) }.getOrNull()
        if (crash != null) { append("===== 이전 실행 오류 =====\n"); append(crash); append("\n\n") }
        append("===== 이벤트 로그 =====\n")
        val events = runCatching { File(ctx.filesDir, "events.log").readText().takeLast(8000) }.getOrNull()
        append(if (events.isNullOrBlank()) "(비어있음)" else events)
    }
}
