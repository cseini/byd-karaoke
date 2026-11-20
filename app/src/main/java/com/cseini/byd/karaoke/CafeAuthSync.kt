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
                "confirmed" -> {
                    val e = p(ctx).edit()
                        .putBoolean("verified", true).putLong("verified_at", System.currentTimeMillis())
                        .remove("code").remove("th").remove("expires")
                    val pend = p(ctx).getString("tok_pending", "") ?: ""
                    if (pend.isNotEmpty()) e.putString("tok", pend).remove("tok_pending")
                    e.apply()
                }
                "expired" -> p(ctx).edit().remove("code").remove("th").remove("expires").remove("tok_pending").apply()
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
            p(ctx).edit().putString("code", code).putString("th", th).putLong("expires", exp).putString("tok_pending", raw).apply()
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

    /** 정회원 권한 체크 결과. enforceAt: 사용 제한 시작 시각(밀리초, 0 이면 서버가 정하지 않음). */
    class GateState(
        val pass: Boolean,
        val enforceAt: Long,
        val paused: Boolean = false,
        val pauseTitle: String = "",
        val pauseBody: String = "",
    )

    /** 정회원 권한 확인(블로킹 — 백그라운드 스레드에서 호출). 서버로는 인증에 쓰던 해시(th)뿐 보낸다.
     *  원문 비밀값이 없는(옛 버전에서 인증한) 사용자는 th 를 만들 수 없어 임시 값으로 묻고 미통과로 본다.
     *  서버에 닿지 못하면 마지막 통과가 7일 안일 때만 통과시키고, 아니면 알고 있던 제한 시각 전까지만 통과시킨다. */
    fun checkGate(ctx: Context): GateState {
        val pr = p(ctx)
        val now = System.currentTimeMillis()
        val tok = pr.getString("tok", "") ?: ""
        val th = if (tok.isNotEmpty()) sha256hex(tok) else sha256hex(hex(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        val resp = req("GET", "/cafe-auth/status?th=$th", null)
        val j = runCatching { JSONObject(resp ?: "") }.getOrNull()
        if (j == null || !j.optBoolean("ok", false)) {
            val cached = pr.getLong("gate_enforce_at", 0)
            if (now - pr.getLong("gate_pass_at", 0) < 7L * 24 * 3600 * 1000) return GateState(true, cached)
            return GateState(cached == 0L || now < cached, cached)
        }
        val enforceAt = j.optLong("gateEnforceAt", 0)
        val pass = (tok.isNotEmpty() && j.optBoolean("verified", false)) || j.optBoolean("exempt", false)
        val e = pr.edit()
        if (enforceAt != 0L) e.putLong("gate_enforce_at", enforceAt) else e.remove("gate_enforce_at")
        if (pass) e.putLong("gate_pass_at", now)
        e.apply()
        // 운영자가 서비스를 멈춘 경우 — 예외 대상이 아니면 서버가 안내문과 함께 알려 준다(인증으로는 풀리지 않는다).
        if (!pass && j.optBoolean("paused", false)) {
            return GateState(false, enforceAt, true, j.optString("pauseTitle", ""), j.optString("pauseBody", ""))
        }
        return GateState(pass, enforceAt)
    }

    /** 단톡방 안내를 받을 수 있는 상태(인증 확인 때 원문 비밀값을 보관한 경우). 이전 버전에서 인증한 사람은 없다. */
    fun hasChatToken(ctx: Context): Boolean = !p(ctx).getString("tok", "").isNullOrEmpty()

    class ChatResult(val url: String?, val password: String?, val error: String?)

    /** 단톡방 주소·비밀번호 요청(블로킹 — 백그라운드 스레드에서 호출). 받은 값은 저장도 로그도 하지 않는다.
     *  error: no_token / network / not_authenticated / not_member / not_configured / rate_limited / bad_response */
    fun fetchChat(ctx: Context): ChatResult {
        val tok = p(ctx).getString("tok", "") ?: ""
        if (tok.isEmpty()) return ChatResult(null, null, "no_token")
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(BASE + "/cafe-auth/chat/app-claim").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(JSONObject().put("token", tok).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val j = JSONObject(stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return ChatResult(null, null, "bad_response"))
            if (j.optBoolean("ok", false)) {
                val url = j.optString("url", ""); val pw = j.optString("password", "")
                if (url.startsWith("https://") && pw.isNotEmpty()) ChatResult(url, pw, null) else ChatResult(null, null, "bad_response")
            } else {
                val err = j.optString("error", "bad_response")
                if (err == "not_authenticated") p(ctx).edit().remove("tok").apply()
                ChatResult(null, null, err)
            }
        } catch (t: java.io.IOException) {
            ChatResult(null, null, "network")
        } catch (t: org.json.JSONException) {
            ChatResult(null, null, "bad_response")
        } finally {
            conn?.disconnect()
        }
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
