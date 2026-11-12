package com.cseini.byd.karaoke

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

/**
 * 비공식 써드파티 앱 고지 동의 — 보증수리 거부/제한 가능성을 고지하고 "이해했고 동의합니다"를
 * 눌러야 진행된다(최초 1회만, 닫기 불가). 동의 시각·uid·앱빌드를 공통 피드백 서버로 전송해
 * 분쟁 발생 시 고지·동의 사실의 증거로 남긴다. (운영자 지시, 링크락과 공통 규격)
 */
object ThirdPartyConsent {

    private const val ENDPOINT = "https://byd.cseini.co.kr/consent"
    // 모든 BYD 앱 공통 서버 인증 헤더(링크락과 동일 규격) — 비밀값이 아니라 식별용.
    private const val X_AUTH = "d9dd8767ab1faf0874ac816ba03ca535"

    private const val MESSAGE =
        "링크 노래방은 BYD 공식 앱이 아닌 비공식 써드파티 앱입니다.\n\n" +
            "설치·사용으로 인해 헤드유닛(차량 인포테인먼트) 보증수리가 제한되거나 거부될 수 있습니다.\n\n" +
            "이 사실을 이해했으며, 사용 중 발생하는 문제는 본인 책임임에 동의하는 경우에만 계속 진행해 주세요."

    private const val DRIVING_MESSAGE =
        "운행 중 영상 시청은 위험하며 법으로 금지되어 있습니다.\n\n" +
            "• 기어가 P(주차)·N(중립)일 때만 영상이 보입니다. 주행 중에는 화면이 가려지고 반주 소리만 나옵니다.\n" +
            "• 뒷좌석 태블릿이 연결되어 있으면 그쪽에서는 계속 볼 수 있습니다.\n" +
            "• 운행 중 영상 시청으로 생기는 사고와 법적 책임은 사용자 본인에게 있습니다.\n\n" +
            "동의하는 경우에만 시작해 주세요."

    /** 실행할 때마다 보여 주는 운행 중 영상 시청 경고 — 5초 카운트다운 후 자동으로 닫히고, 그 전에 큰 네모 칸을 눌러도 닫힌다. */
    fun drivingWarning(activity: Activity, onDone: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(android.widget.TextView(activity).apply {
            text = DRIVING_MESSAGE
            textSize = 16f
        })
        val box = android.widget.TextView(activity).apply {
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, (20 * dp).toInt(), 0, (20 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((3 * dp).toInt(), android.graphics.Color.GRAY)
                cornerRadius = 8 * dp
            }
        }
        root.addView(box, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (24 * dp).toInt() })
        val dialog = AlertDialog.Builder(activity)
            .setTitle("⚠ 운행 중 영상 시청 금지")
            .setView(root)
            .setCancelable(false)
            .create()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var left = 5
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            if (dialog.isShowing) dialog.dismiss()
            onDone()
        }
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                if (left <= 0) { finish(); return }
                box.text = "동의합니다 ($left)"
                left--
                handler.postDelayed(this, 1000)
            }
        }
        box.setOnClickListener { finish() }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.show()
        tick.run()
    }

    /** 동의를 이미 받았으면 바로 onDone, 아니면 닫기 불가 다이얼로그로 막는다. */
    fun gate(activity: Activity, settings: com.cseini.byd.karaoke.data.SettingsStore, onDone: () -> Unit) {
        if (settings.thirdPartyConsentGiven) {
            if (!settings.thirdPartyConsentSynced) {
                send(activity, settings.cafeNick) { ok -> if (ok) settings.thirdPartyConsentSynced = true }
            }
            onDone()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("비공식 써드파티 앱 안내")
            .setMessage(MESSAGE)
            .setCancelable(false)
            .setPositiveButton("이해했고 동의합니다") { _, _ ->
                settings.thirdPartyConsentGiven = true
                send(activity, settings.cafeNick) { ok -> settings.thirdPartyConsentSynced = ok }
                onDone()
            }
            .show()
    }

    /** 동의 사실 1건 전송 — 전용 영구 보존 테이블(legal_consents)로 모든 BYD 앱이 공유하는 경로. */
    private fun send(ctx: Context, cafeNick: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val body = Gson().toJson(
            mapOf(
                "app" to "karaoke",
                "uid" to CafeNick.deviceTag(app),
                "vin" to "",
                "nickname" to cafeNick,
                "consent_version" to "v1",
                "app_build" to BuildConfig.VERSION_NAME,
                "device" to "headunit",
                // 카페 인증 기기 슬롯 집계용 — vehicleType 비어있으면 폰, 아니면 헤드유닛으로 서버가 판정.
                "deviceTag" to CafeNick.deviceTag(app),
                "vehicleType" to VehicleProbe.vehicleType()
            )
        )
        val handler = android.os.Handler(app.mainLooper)
        Thread {
            val ok = runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Auth", X_AUTH)
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
