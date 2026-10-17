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
        "오너스랩노래방은 BYD 공식 앱이 아닌 비공식 써드파티 앱입니다.\n\n" +
            "설치·사용으로 인해 헤드유닛(차량 인포테인먼트) 보증수리가 제한되거나 거부될 수 있습니다.\n\n" +
            "이 사실을 이해했으며, 사용 중 발생하는 문제는 본인 책임임에 동의하는 경우에만 계속 진행해 주세요."

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
