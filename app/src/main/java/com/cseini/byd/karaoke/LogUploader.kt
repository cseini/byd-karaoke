package com.cseini.byd.karaoke

import android.content.Context
import com.cseini.byd.karaoke.data.SettingsStore
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 이벤트 로그 원격 전송(설정 옵트인) — 차에서 생긴 문제를 사진 대신 서버(D1)로 받아 보기 위한 장치.
 * 항상 events.log 꼬리 전체를 보내고 중복 제거는 서버(INSERT OR IGNORE)가 한다 —
 * events.log 가 상한 초과 시 잘려나가 클라이언트 오프셋 관리가 불가능하기 때문.
 * 실패는 조용히 무시(다음 기회에 같은 꼬리를 다시 보내면 된다).
 */
object LogUploader {

    private const val ENDPOINT = "https://karaoke.usenu.kr/api/log"
    @Volatile private var lastTry = 0L

    fun maybeUpload(ctx: Context) {
        val app = ctx.applicationContext
        if (!SettingsStore(app).logUpload) return
        val now = System.currentTimeMillis()
        if (now - lastTry < 60_000) return
        lastTry = now
        // 파일 읽기는 호출 스레드에서 — crash.txt 는 직후 takeCrash 가 지우므로 여기서 먼저 집는다.
        val events = runCatching { File(app.filesDir, "events.log").readText().takeLast(8000) }.getOrNull().orEmpty()
        val crash = runCatching { File(app.filesDir, "crash.txt").readText().take(6000) }.getOrNull()
        val lines = events.lines().filter { it.isNotBlank() }.toMutableList()
        if (crash != null) lines += "CRASH " + crash.replace("\n", " ⏎ ")
        if (lines.isEmpty()) return
        val device = runCatching {
            android.provider.Settings.Secure.getString(
                app.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull() ?: "unknown"
        val body = Gson().toJson(
            mapOf("device" to device, "ver" to BuildConfig.VERSION_NAME, "lines" to lines)
        )
        Thread {
            runCatching {
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
            }
        }.start()
    }
}
