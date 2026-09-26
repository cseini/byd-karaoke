package com.cseini.byd.karaoke

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

/**
 * 카페 닉네임 등록 — 앱 시작 시 "지역ll닉네임ll차종" 3분절을 받아 로컬 저장 + 공통 피드백 서버로 1건 전송.
 * uid 는 오토전비 DiLink5Support.deviceTag() 와 동일 계산이라 워커 통합 명부에서 같은 헤드유닛으로 묶인다.
 */
object CafeNick {

    private const val ENDPOINT = "https://byd.cseini.co.kr/feedback"
    const val SEP = "ll"

    /** 차량별 안정적 태그(PII 아님) — ANDROID_ID 의 SHA-256 앞 8hex. 오토전비와 동일. */
    fun deviceTag(ctx: Context): String {
        return try {
            val aid = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
            if (aid.isNullOrEmpty()) return "unk"
            val h = java.security.MessageDigest.getInstance("SHA-256").digest(aid.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0 until 4) sb.append("%02x".format(h[i].toInt() and 0xff))
            sb.toString()
        } catch (t: Throwable) { "unk" }
    }

    /** 저장된 닉이 3분절 규격이면 다시 묻지 않는다. */
    fun isRegistered(saved: String): Boolean {
        val parts = saved.split(SEP)
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    /**
     * 입력 한 칸이 유효한지 — 비어 있지 않고, 구분자(ll)나 서버가 ll 로 정규화하는 오타(|| 11 ㅣㅣ)를
     * 포함하지 않아야 3분절이 깨지지 않는다.
     */
    fun isValidField(v: String): Boolean {
        val t = v.trim()
        if (t.isEmpty()) return false
        val lower = t.lowercase()
        return !lower.contains("ll") && !t.contains("||") && !t.contains("11") && !t.contains("ㅣㅣ")
    }

    /**
     * 3칸(지역ll닉네임ll차종) 편집 다이얼로그 — 최초 등록 게이트(cancelable=false, 빈칸)와
     * 설정 화면의 수정(cancelable=true, 저장값 미리 채움) 둘 다 이 함수 하나로 처리한다.
     * 저장·전송은 onSaved 콜백에서 호출자가 각자의 Settings 저장 방식에 맞게 한다.
     */
    fun showDialog(activity: Activity, initial: String, cancelable: Boolean, onSaved: (String) -> Unit) {
        val parts = initial.split(SEP)
        val (initRegion, initNick, initCar) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple("", "", "")
        val dp = { d: Int -> (d * activity.resources.displayMetrics.density).toInt() }
        fun field(hint: String, value: String): android.widget.EditText = android.widget.EditText(activity).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val etRegion = field("지역", initRegion).apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT }
        val etNick = field("닉네임", initNick).apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT }
        val etCar = field("차종", initCar).apply { imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE }
        fun sep() = android.widget.TextView(activity).apply {
            text = SEP
            setPadding(dp(6), 0, dp(6), 0)
        }
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            val lp = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(etRegion, lp)
            addView(sep())
            addView(etNick, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sep())
            addView(etCar, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (cancelable) "BYD 써드파티연구소 닉네임 수정" else "BYD 써드파티연구소 닉네임 등록")
            .setMessage(
                (if (cancelable) "닉네임을 수정합니다." else "BYD 써드파티연구소 카페 닉네임을 등록해 주세요. 세 칸을 모두 채워야 시작할 수 있어요.") +
                    "\n(구분자 ll·|| 등은 칸 안에 넣지 마세요)",
            )
            .setView(row)
            .setPositiveButton("확인", null)   // 아래에서 유효성 통과 시에만 닫히도록 재정의
            .setCancelable(cancelable)
        if (cancelable) builder.setNegativeButton("취소", null)
        val dialog = builder.create()
        val valid = { isValidField(etRegion.text.toString()) &&
            isValidField(etNick.text.toString()) &&
            isValidField(etCar.text.toString()) }
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = valid()
            val watch = { _: android.text.Editable? -> ok.isEnabled = valid(); Unit }
            etRegion.doAfterTextChanged(watch)
            etNick.doAfterTextChanged(watch)
            etCar.doAfterTextChanged(watch)
            ok.setOnClickListener {
                if (!valid()) return@setOnClickListener
                val combined = etRegion.text.toString().trim() + SEP +
                    etNick.text.toString().trim() + SEP + etCar.text.toString().trim()
                onSaved(combined)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 로컬 저장 후 서버로 등록 1건 전송. onDone(성공여부) 로 회신. */
    fun register(ctx: Context, region: String, nick: String, car: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val combined = region.trim() + SEP + nick.trim() + SEP + car.trim()
        send(app, combined, onDone)
    }

    /** 저장된 합성 닉을 서버로 전송(등록 시 + 오프라인 실패 후 재전송 공용). */
    fun send(ctx: Context, combinedNick: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val body = Gson().toJson(
            mapOf(
                "app" to "karaoke",
                "uid" to deviceTag(app),
                "nickname" to combinedNick,
                "category" to "닉네임등록",
                "message" to "닉네임 등록",
                "device" to "headunit",
                "model" to android.os.Build.MODEL,
                "appVer" to BuildConfig.VERSION_NAME
            )
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
