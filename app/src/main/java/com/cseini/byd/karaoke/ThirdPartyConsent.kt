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

    private const val DRIVING_MESSAGE =
        "운행 중 영상 시청은 매우 위험하며, 법으로 금지되어 있습니다.\n\n" +
            "■ 그래서 오너스랩노래방은 운행 중에는 영상 화면을 검게 가립니다. " +
            "기어가 P(주차) 또는 N(중립)일 때만 영상이 보이고, D(주행)·R(후진)에서는 화면이 가려지며 '운행 중 영상 시청 불가' 안내가 나옵니다. " +
            "이때도 반주 소리는 계속 재생됩니다. 노래를 부르다가 기어를 넣고 출발하면 그 즉시 화면이 가려집니다. " +
            "차량의 기어를 확인할 수 없는 경우에도 안전을 위해 화면이 가려집니다.\n\n" +
            "■ 뒷좌석 태블릿(세컨드 스크린)이 연결되어 있으면, 운행 중에도 뒷좌석 화면에서는 영상이 계속 재생됩니다. " +
            "이때 헤드유닛 화면에는 안내 문구만 표시됩니다. 뒷좌석 화면은 운전자의 시야에 들어오지 않는 위치에 설치해 주세요.\n\n" +
            "■ 운전자의 시선이 화면으로 향하는 순간, 차는 눈을 감은 채로 달리는 것과 같습니다. " +
            "시속 60km로 달리는 차는 1초에 약 17m를 갑니다. 영상을 3초만 봐도 50m 넘게 앞을 보지 못하는 셈이고, " +
            "그 사이 앞차의 급제동, 신호 변경, 횡단하는 사람을 알아채지 못하면 돌이킬 수 없는 사고로 이어집니다.\n\n" +
            "■ 도로교통법은 자동차를 운전하는 중에 영상 표시 장치로 영상을 시청하는 행위를 금지하고 있습니다. " +
            "위반하면 범칙금과 벌점이 부과될 수 있고, 시청 중 사고가 나면 안전운전 의무 위반으로 민사·형사상 책임을 질 수 있습니다. " +
            "신호 대기 중이나 정체 중이라도 기어가 D에 있는 운전 중 상태라면 단속과 책임의 대상이 될 수 있습니다.\n\n" +
            "■ 이 앱의 화면 가림을 우회하거나 운행 중에 영상을 시청하려는 시도로 생기는 모든 사고와 법적 책임은 사용자 본인에게 있으며, " +
            "앱 제작자는 이에 대해 어떠한 책임도 지지 않습니다.\n\n" +
            "위 내용을 모두 읽었고, 운행 중에는 영상을 시청하지 않겠다는 데 동의하는 경우에만 시작해 주세요."

    /** 실행할 때마다 보여 주는 운행 중 영상 시청 경고 — 닫기 불가, 동의해야 시작된다. */
    fun drivingWarning(activity: Activity, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("⚠ 운행 중 영상 시청은 불가능합니다")
            .setMessage(DRIVING_MESSAGE)
            .setCancelable(false)
            .setPositiveButton("위 내용을 읽었고 동의합니다") { _, _ -> onDone() }
            .show()
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
