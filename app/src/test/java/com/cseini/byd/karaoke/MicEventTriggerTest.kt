package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.BydMicEventReceiver.Companion.Decision
import com.cseini.byd.karaoke.BydMicEventReceiver.Companion.PAIR_MS
import com.cseini.byd.karaoke.BydMicEventReceiver.Companion.START_IGNORE_MS
import com.cseini.byd.karaoke.BydMicEventReceiver.Companion.decide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BYD micevent(마이크 버튼 브로드캐스트) 트리거 판정.
 * 씨라이언7 실측: 짧게 누르면 131, 길게 누르면 132. 131 은 앱 시작 6~9초 뒤에도 한 번 온다(마이크 전원 알림).
 */
class MicEventTriggerTest {

    private val LATER = 60_000L        // 시작한 지 한참 지남
    private val NEVER = Long.MAX_VALUE // 직전 트리거 없음

    @Test fun `길게 누름(132)은 바로 트리거`() {
        assertEquals(Decision.TRIGGER, decide(132, "both", LATER, NEVER, false))
    }

    @Test fun `짧게 누름(131)도 시작 후 충분히 지났으면 트리거`() {
        assertEquals(Decision.TRIGGER, decide(131, "both", LATER, NEVER, false))
    }

    @Test fun `앱 시작 직후 131 은 무시 — 켜자마자 음성검색이 뜨면 안 된다`() {
        // 실측된 도착 시각(6·7·9초) 전부 무시돼야 한다.
        for (t in listOf(0L, 6_000L, 7_000L, 9_000L, START_IGNORE_MS - 1)) {
            assertEquals("t=$t", Decision.STARTUP_131, decide(131, "both", t, NEVER, false))
        }
    }

    @Test fun `앱 시작 직후라도 132 는 버튼이므로 트리거`() {
        assertEquals(Decision.TRIGGER, decide(132, "both", 1_000L, NEVER, false))
    }

    @Test fun `한 누름이 132 다음 131 로 올 때 두 번 실행하지 않는다`() {
        // 실측 간격 4~5초 — PAIR_MS 안이라 두 번째는 중복 처리.
        assertEquals(Decision.TRIGGER, decide(132, "both", LATER, NEVER, false))
        assertEquals(Decision.DUPLICATE, decide(131, "both", LATER, 4_000L, false))
        assertEquals(Decision.DUPLICATE, decide(131, "both", LATER, 5_000L, false))
    }

    @Test fun `중복 창을 지나면 다시 누를 수 있다`() {
        assertEquals(Decision.TRIGGER, decide(132, "both", LATER, PAIR_MS, false))
        assertEquals(Decision.TRIGGER, decide(131, "both", LATER, PAIR_MS + 1, false))
    }

    @Test fun `설정이 132 면 131 에는 반응하지 않는다`() {
        assertEquals(Decision.NOT_MY_CODE, decide(131, "132", LATER, NEVER, false))
        assertEquals(Decision.TRIGGER, decide(132, "132", LATER, NEVER, false))
    }

    @Test fun `설정이 off 면 어떤 코드에도 반응하지 않는다`() {
        assertEquals(Decision.NOT_MY_CODE, decide(131, "off", LATER, NEVER, false))
        assertEquals(Decision.NOT_MY_CODE, decide(132, "off", LATER, NEVER, false))
    }

    @Test fun `버튼과 무관한 다른 코드는 무시`() {
        // 133=패널 134=볼륨↑ 135=볼륨↓ 는 우리가 BYD 로 재전송하는 값 — 되받아 트리거하면 루프가 된다.
        for (c in listOf(133, 134, 135, 140)) {
            assertEquals("code=$c", Decision.NOT_MY_CODE, decide(c, "both", LATER, NEVER, false))
        }
    }

    @Test fun `시작 알림 131 을 한 번 흘린 뒤에는 같은 창 안이라도 버튼으로 받는다`() {
        // 실측: 시작 19.9초에 누른 131 이 시간 창에 걸려 유실됐다. 시작 알림은 한 번뿐이므로
        // 그 한 번을 본 뒤에는 창 안이어도 버튼으로 처리해야 한다.
        assertEquals(Decision.STARTUP_131, decide(131, "both", 6_000L, NEVER, false))
        assertEquals(Decision.TRIGGER, decide(131, "both", 19_943L, NEVER, true))
        assertEquals(Decision.TRIGGER, decide(131, "both", 8_000L, NEVER, true))
    }

    @Test fun `기본값 off — 전원 버튼 신호로 음성검색이 뜨지 않는다`() {
        // 실차 확정: micevent 로 오는 131·132 는 둘 다 마이크 '전원' 버튼이다(로그 42건 전수).
        // 켜두면 마이크를 껐다 켤 때마다 음성검색이 뜬다 — 기본은 반드시 off.
        val DEFAULT = "off"
        assertEquals(Decision.NOT_MY_CODE, decide(131, DEFAULT, LATER, NEVER, false))
        assertEquals(Decision.NOT_MY_CODE, decide(132, DEFAULT, LATER, NEVER, false))
        assertEquals(Decision.NOT_MY_CODE, decide(131, DEFAULT, 1_000L, NEVER, true))
    }

    @Test fun `씨라이언 자체녹음 전원 둘 다(131 132) — 끔에 검색창 미리 열어 켜짐 전환에 캡처`() {
        val trig = BydMicEventReceiver.effectiveTrigger(sealionMode = true, setting = "off", gboardMode = false)
        assertEquals("both", trig)
        assertEquals(Decision.TRIGGER, decide(132, trig, LATER, NEVER, false))
        assertEquals(Decision.TRIGGER, decide(131, trig, LATER, NEVER, false))
        assertEquals(Decision.STARTUP_131, decide(131, trig, 5_000L, NEVER, false))
        assertEquals(Decision.NOT_MY_CODE, decide(133, trig, LATER, NEVER, false))
    }

    @Test fun `씨라이언 Gboard 켜짐(131)만 — Gboard 는 마이크 켜져 있어야 인식되므로 끔(132)엔 안 누른다`() {
        val trig = BydMicEventReceiver.effectiveTrigger(sealionMode = true, setting = "off", gboardMode = true)
        assertEquals("131", trig)
        assertEquals(Decision.TRIGGER, decide(131, trig, LATER, NEVER, false))        // 켜짐 → Gboard 마이크 클릭
        assertEquals(Decision.NOT_MY_CODE, decide(132, trig, LATER, NEVER, false))    // 끔 → 안 누름(무음 방지)
        assertEquals(Decision.STARTUP_131, decide(131, trig, 5_000L, NEVER, false))   // 시작 알림은 무시
    }

    @Test fun `씨라이언 아니면 저장 설정 그대로(Gboard 무관)`() {
        assertEquals("off", BydMicEventReceiver.effectiveTrigger(sealionMode = false, setting = "off", gboardMode = true))
        assertEquals("132", BydMicEventReceiver.effectiveTrigger(sealionMode = false, setting = "132", gboardMode = false))
    }

}
