package com.cseini.byd.karaoke

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 이벤트 로그 원격 전송(사용자가 버튼을 누를 때만) — 차에서 생긴 문제를 사진 대신 서버(D1)로 받아
 * 보기 위한 장치. events.log 꼬리 전체를 보내고 중복 제거는 서버(INSERT OR IGNORE)가 한다.
 */
object LogUploader {

    private const val ENDPOINT = "https://karaoke.usenu.kr/api/log"

    /** 설정의 '로그 보내기' 버튼에서 호출 — 지금 즉시 1회 전송. onDone(성공여부)로 결과 회신. */
    fun uploadNow(ctx: Context, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val events = runCatching { File(app.filesDir, "events.log").readText().takeLast(8000) }.getOrNull().orEmpty()
        val crash = runCatching { File(app.filesDir, "crash.txt").readText().take(6000) }.getOrNull()
        val lines = events.lines().filter { it.isNotBlank() }.toMutableList()
        if (crash != null) lines += "CRASH " + crash.replace("\n", " ⏎ ")
        if (lines.isEmpty()) { onDone(false); return }
        val device = runCatching {
            android.provider.Settings.Secure.getString(
                app.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull() ?: "unknown"
        val body = Gson().toJson(
            mapOf("device" to device, "ver" to BuildConfig.VERSION_NAME, "lines" to lines)
        )
        val handler = android.os.Handler(app.mainLooper)
        Thread {
            val ok = runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "byd-karaoke")
                    connectTimeout = 5_000
                    readTimeout = 10_000
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.use { it.readBytes() }
                conn.responseCode in 200..299
            }.getOrDefault(false)
            handler.post { onDone(ok) }
        }.start()
    }
}
