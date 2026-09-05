package com.cseini.byd.karaoke

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.cseini.byd.karaoke.data.SettingsStore

/**
 * BYD 시스템이 USB 마이크 버튼을 누를 때 보내는 브로드캐스트(byd.intent.minikaraoke_micevent)를 받는다.
 * 실차 확정(2026-09-02, D1): com.byd.sing 유닛에서 extra "android.intent.extra.KEY_EVENT"(int) 131/132 가
 * 버튼마다 들어온다 — USB 권한·HID 없이 씨라이언 계열의 마이크 버튼을 쓰는 경로. minikaraoke 유닛은 안 온다.
 * 131 은 앱 시작 직후에도 반복 관측돼(마이크 전원 ON 알림 추정) 기본 트리거는 132. 설정(micEventTrigger)으로 조정.
 * 이 receiver 는 MainActivity 가 동적 등록하므로 콜백으로 바로 액티비티에 전달한다(Intent 우회 불필요).
 */
class BydMicEventReceiver(
    private val settings: SettingsStore,
    private val onMicButton: (Int) -> Unit,
) : BroadcastReceiver() {

    // 실측(씨라이언7): 짧게 누르면 131, 길게 눌러야 132 가 온다. 131 도 받아야 짧게 눌러 쓸 수 있는데,
    // 131 은 앱이 BYD 마이크 서비스에 붙는 순간(시작 후 6~9초)에도 한 번 온다 — 그때 음성검색이
    // 저절로 뜨면 안 되므로 시작 직후 창은 무시한다.
    private val startedAt = android.os.SystemClock.elapsedRealtime()
    private var lastAccepted = 0L
    private var startup131Seen = false

    override fun onReceive(context: Context, intent: Intent) {
        val codeInt = intent.getIntExtra("android.intent.extra.KEY_EVENT", Int.MIN_VALUE)
        val ke = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }.getOrNull()
        val keys = runCatching { intent.extras?.keySet()?.joinToString(",") }.getOrNull() ?: ""
        // USB 연결/해제면 어떤 장치인지까지 남긴다 — 마이크 버튼이 브로드캐스트가 아니라 USB 재열거로
        // 신호를 내는지 확인하려는 계측(가설: 버튼 누름 = 수신기 재연결 = BYD 가 볼륨창을 띄움).
        val dev = runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        }.getOrNull()
        val devInfo = dev?.let { " dev=${it.productName}/${it.deviceName} vid=${it.vendorId}" } ?: ""
        CrashLog.event(
            context,
            "bydmicevent act=${intent.action} KEYint=$codeInt ke=${ke?.keyCode}/${ke?.action} extras=[$keys]$devInfo",
        )
        if (intent.action != MICEVENT || codeInt == Int.MIN_VALUE) return
        val now = android.os.SystemClock.elapsedRealtime()
        val sinceStart = now - startedAt
        val sinceLast = if (lastAccepted == 0L) Long.MAX_VALUE else now - lastAccepted
        // 씨라이언7 전용 모드: 전원 버튼 신호(131 짧게/132 길게)를 트리거로 쓴다 — 이 유닛에서 목소리가 잡힌
        // 5건 전부 전원 버튼 트리거 직후였다(마이크 펌웨어 리프레시 창). 6.46 에서 껐던 이유(USB 직접 grab+
        // 오디오 효과로 BYD 마이크 파손)는 BUS 라우팅·fx=false 로 제거됨. 운영자 승인(2026-09-03)으로 복원.
        val trig = effectiveTrigger(settings.sealionMode, settings.micEventTrigger, settings.googleSttPreferred)
        val d = decide(codeInt, trig, sinceStart, sinceLast, startup131Seen)
        if (d == Decision.STARTUP_131) startup131Seen = true   // 시작 알림은 한 번뿐 — 다음 131 부터는 버튼으로 본다
        if (d != Decision.TRIGGER) {
            if (d != Decision.NOT_MY_CODE) CrashLog.event(context, "micevent $codeInt 무시($d ${sinceStart}ms/${sinceLast}ms trig=$trig)")
            return
        }
        lastAccepted = now
        CrashLog.event(context, "micevent $codeInt → 트리거 (trig=$trig sealion=${settings.sealionMode} ${sinceStart}ms/${sinceLast}ms)")
        onMicButton(codeInt)
    }

    companion object {
        const val MICEVENT = "byd.intent.minikaraoke_micevent"

        /** 앱 시작 직후 이 시간 안의 '첫' 131 만 마이크 전원 알림으로 본다(실측 6~9초). 20초는 너무 넓어
         *  시작 13초 뒤의 실제 짧게 누름(131)을 삼켰다(v6.56 실측) → 10초로 좁힌다. startup131Seen 이
         *  '첫 한 번'을 보장하므로 이 창은 시작 알림 타이밍(6~9초)만 덮으면 된다. */
        const val START_IGNORE_MS = 10_000L
        /** 132→131 처럼 한 누름이 두 신호로 오는 유닛의 중복 방지 창. */
        const val PAIR_MS = 10_000L

        enum class Decision { TRIGGER, NOT_MY_CODE, STARTUP_131, DUPLICATE }

        /**
         * 실제 적용할 트리거 설정 — 순수 함수(단위 테스트). 씨라이언7 전용 모드면 저장된 설정과 무관하게
         * 전원 신호 둘 다(131/132)를 트리거해 **마이크 끔(132)에 검색창(녹음)을 미리 연다**. 녹음이 이미
         * 돌고 있어야, 사용자가 마이크를 다시 켜는 전환을 타고 살아난 소리를 흡수한다(실측: 6.56 에서 이렇게
         * 성공). '켤 때(131)에야 녹음을 새로 여는' 방식은 여는 0.3초 사이 BYD 가 마이크를 채가 무음이 됐다(6.58 실패).
         * 앱 시작 시 오는 첫 131(전원 알림)은 decide 의 startup131 가드로 무시. 다른 유닛은 저장 설정(기본 off).
         */
        fun effectiveTrigger(sealionMode: Boolean, setting: String, gboardMode: Boolean): String = when {
            // Gboard 음성은 노래방 마이크가 '켜져 있어야' 인식된다(꺼짐엔 무음). 그러니 켜짐(131)에만 트리거해
            // 그때 Gboard 마이크를 누른다. 꺼짐(132)에 누르면 무음이라 무의미.
            sealionMode && gboardMode -> "131"
            // 자체 녹음 모드(Gboard 아님): 마이크 켜짐/꺼짐 전환 창에서 캡처가 되므로 둘 다 받는다.
            sealionMode -> "both"
            else -> setting
        }

        /**
         * 트리거 판정 — 부수효과 없는 순수 함수라 단위 테스트로 검증한다.
         * @param sinceStart 앱(리시버) 시작 후 경과 ms
         * @param sinceLast 직전에 트리거를 받아들인 뒤 경과 ms (없으면 Long.MAX_VALUE)
         */
        fun decide(
            code: Int,
            trigger: String,
            sinceStart: Long,
            sinceLast: Long,
            startup131Seen: Boolean,
        ): Decision {
            // 버튼 신호는 131/132 뿐이다. 133=패널·134=볼륨↑·135=볼륨↓ 는 우리가 BYD 로 재전송하는 값이라
            // "both" 로 뭉뚱그리면 볼륨 버튼에도 음성검색이 뜬다.
            if (code != 131 && code != 132) return Decision.NOT_MY_CODE
            if (trigger != "both" && trigger != code.toString()) return Decision.NOT_MY_CODE
            // 앱이 BYD 마이크 서비스에 붙을 때 131 이 딱 한 번 딸려온다(마이크 전원 알림). 그 '첫 한 번'만
            // 무시한다 — 시간 창으로만 막으면 그 안에 누른 진짜 버튼까지 삼킨다(실측: 19.9초에 누른 게 유실).
            if (code == 131 && !startup131Seen && sinceStart < START_IGNORE_MS) return Decision.STARTUP_131
            if (sinceLast < PAIR_MS) return Decision.DUPLICATE
            return Decision.TRIGGER
        }

        /** 마이크 버튼 관련일 수 있는 BYD 브로드캐스트 후보들 — 무엇이 오는지 폭넓게 관찰한다. */
        val ACTIONS = listOf(
            MICEVENT,
            "byd.intent.action.SHOW_KARAOKE_VIEW",
            "byd.intent.action.DISMISS_KARAOKE_VIEW",
            "byd.intent.action.AUDIO_SESSION_CHANGED",
            // 마이크 버튼이 USB 재열거로 신호를 내는지 확인용 계측(트리거는 아직 붙이지 않는다).
            // dumpsys 에서 BYD-micTS02 가 001/005→010→011→012→013 로 반복 재열거된 것이 단서.
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
            "android.hardware.usb.action.USB_DEVICE_DETACHED",
        )
    }
}
