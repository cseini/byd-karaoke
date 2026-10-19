package com.cseini.byd.karaoke

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 카페 정회원 인증(2026-10-19) — 헤드유닛 전용 앱(경로B)의 코드 발급.
 * raw 시크릿은 로컬(prefs)만 보관, 서버엔 sha256 해시(token_hash)만 전달.
 * 코드 발급 후 인증 웹페이지(/cafe-auth?code=&th=)를 브라우저로 열면, 그 화면이
 * 카페 글 안내·확인까지 전부 처리한다(오토전비 CafeAuthSync.java 와 동일 패턴).
 */
object CafeAuthSync {
    private const val BASE = "https://byd.cseini.co.kr"
    private const val PREFS = "cafeauth"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPending(ctx: Context): Boolean {
        val p = p(ctx)
        return p.getString("code", "")!!.isNotEmpty() && p.getLong("expires", 0) > System.currentTimeMillis()
    }

    fun pendingCode(ctx: Context): String = p(ctx).getString("code", "") ?: ""

    /** 인증 완료 여부를 앱이 직접 안다 — 전엔 th 발급만 하고 확정 여부를 웹페이지에만 맡겨서
     *  앱 스스로는 "이 기기가 인증됐는지"를 전혀 몰랐다(검증해서 막는 기능을 쓸 수 없는 근본 결함).
     *  이제 result를 직접 폴링해 로컬에 남긴다. */
    fun isVerified(ctx: Context): Boolean = p(ctx).getBoolean("verified", false)

    /** result 폴링(블로킹 — 백그라운드 스레드에서 호출). "confirmed"/"pending"/"rejected"/"expired"/"not_found" 중 하나, 실패 시 null. */
    fun pollResult(ctx: Context): String? {
        val th = p(ctx).getString("th", "") ?: ""
        if (th.isEmpty()) return null
        return runCatching {
            val resp = req("GET", "/cafe-auth/result?th=$th", null) ?: return null
            val j = JSONObject(resp)
            val status = j.optString("status", "")
            when (status) {
                "confirmed" -> p(ctx).edit()
                    .putBoolean("verified", true).putLong("verified_at", System.currentTimeMillis())
                    .remove("code").remove("th").remove("expires").apply()
                "expired" -> p(ctx).edit().remove("code").remove("th").remove("expires").apply()
            }
            status.ifEmpty { null }
        }.getOrNull()
    }

    /** 코드 발급(블로킹 — 백그라운드 스레드에서 호출). 성공 시 코드 반환, 실패 null. */
    fun startAuth(ctx: Context): String? {
        return runCatching {
            val rawB = ByteArray(32)
            SecureRandom().nextBytes(rawB)
            val raw = hex(rawB)
            val th = sha256hex(raw)
            val body = JSONObject()
                .put("token_hash", th)
                .put("app", "karaoke")
                .put("label", android.os.Build.MODEL + " 헤드유닛")
                .put("deviceTag", CafeNick.deviceTag(ctx))
                .put("vehicleType", VehicleProbe.vehicleType())
            val resp = req("POST", "/cafe-auth/register", body.toString()) ?: return null
            val j = JSONObject(resp)
            if (!j.optBoolean("ok", false)) return null
            val code = j.optString("code", "")
            if (code.isEmpty()) return null
            val exp = j.optLong("expires_at", System.currentTimeMillis() + 3_600_000)
            p(ctx).edit().putString("code", code).putString("th", th).putLong("expires", exp).apply()
            code
        }.getOrNull()
    }

    /** 직전에 발급한 코드로 인증 웹페이지 URL 구성(만료 전까지만 유효) — null이면 재발급 필요. */
    fun webUrl(ctx: Context): String? {
        val p = p(ctx)
        val code = p.getString("code", "") ?: ""
        val th = p.getString("th", "") ?: ""
        if (code.isEmpty() || th.isEmpty() || p.getLong("expires", 0) < System.currentTimeMillis()) return null
        return "$BASE/cafe-auth?code=$code&th=$th"
    }

    private fun req(method: String, path: String, body: String?): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(BASE + path).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 12_000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (code in 200..299) resp else null
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    private fun sha256hex(raw: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return hex(h)
    }
}
