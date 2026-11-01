package com.cseini.byd.karaoke.share

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 단축 주소(byd.cseini.co.kr/go/<코드>) 등록. 앱이 자기 LAN 주소를 서버에 올리면 서버 페이지가 그 주소로 가는 버튼을 보여 준다.
 * 코드는 앱이 처음 만든 랜덤 비밀값에서 나오므로 추측할 수 없다(기기 식별자와 무관). 서버가 아직 없거나 실패하면 단축 주소는 숨긴다.
 */
object GoLink {
    private const val BASE = "https://byd.cseini.co.kr"
    private const val APP = "karaoke"
    private const val PREFS = "go_lan"
    private const val REFRESH_MS = 6L * 60 * 60 * 1000
    private const val OK_VALID_MS = 24L * 60 * 60 * 1000

    @Volatile private var running = false
    @Volatile private var lastOkAt = 0L

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun secret(ctx: Context): String {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString("secret", null)?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = b.joinToString("") { "%02x".format(it) }
        sp.edit().putString("secret", s).apply()
        return s
    }

    fun code(ctx: Context): String =
        MessageDigest.getInstance("SHA-256").digest(("go|app|" + secret(ctx)).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(10)

    /** 서버에 등록이 확인된 뒤(24시간 안)에만 값이 있다 — 서버가 없을 때 죽은 주소를 보여 주지 않는다. */
    fun shortUrl(ctx: Context): String? =
        if (System.currentTimeMillis() - lastOkAt < OK_VALID_MS) "$BASE/go/${code(ctx)}" else null

    /** 서버 시작 뒤 호출. 주소가 바뀌면(60초 주기 점검)·6시간마다 서버에 다시 올린다. */
    fun start(ctx: Context, port: Int) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread {
            var lastKey = ""
            var lastPost = 0L
            while (running) {
                var wait = 60_000L
                runCatching {
                    val ips = localIpCandidates(app).map { it.ip }.take(4)
                    val key = port.toString() + "|" + ips.joinToString(",")
                    if (ips.isNotEmpty() && (key != lastKey || System.currentTimeMillis() - lastPost > REFRESH_MS)) {
                        if (post(app, port, ips)) { lastKey = key; lastPost = System.currentTimeMillis(); lastOkAt = lastPost }
                        else wait = 300_000L   // 서버가 아직 없거나 오류 — 5분 뒤 재시도
                    }
                }
                try { Thread.sleep(wait) } catch (e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.name = "go-lan"; it.start() }
    }

    fun stop() { running = false }

    private fun post(ctx: Context, port: Int, ips: List<String>): Boolean {
        val body = JSONObject().put("code", code(ctx)).put("app", APP).put("port", port).put("ips", JSONArray(ips)).toString()
        val req = okhttp3.Request.Builder().url("$BASE/go/lan")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), body)).build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }
}
