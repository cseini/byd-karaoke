package com.cseini.byd.karaoke

import android.view.KeyEvent
import com.cseini.byd.karaoke.KeyCatcherService.Companion.SEALION_COOLDOWN_MS
import com.cseini.byd.karaoke.KeyCatcherService.Companion.sealionKeyTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 씨라이언7 순정마이크 버튼 → 접근성 KeyEvent 트리거 판정.
 * 실측(v6.51 D1): F1=131 전원 ON, F2=132 전원 OFF, F3=133 볼륨창(패널) 버튼. 볼륨창 버튼만 음성검색.
 */
class SealionKeyTriggerTest {

    private val NEVER = Long.MAX_VALUE   // 직전 트리거 없음

    @Test fun `씨라이언 모드에서 볼륨창 버튼(F3)은 음성검색`() {
        assertTrue(sealionKeyTrigger(KeyEvent.KEYCODE_F3, true, NEVER))
    }

    @Test fun `전원 버튼(F1 F2)에는 절대 반응하지 않는다 — 전원 신호에 걸어 마이크를 망가뜨린 전력`() {
        for (c in listOf(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2)) {
            assertFalse("code=$c", sealionKeyTrigger(c, true, NEVER))
        }
    }

    @Test fun `볼륨(F4 F5)·조합키·휠 키 등 다른 키도 반응하지 않는다`() {
        val others = listOf(
            KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_META_RIGHT,
            293, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOLUME_UP,
        )
        for (c in others) assertFalse("code=$c", sealionKeyTrigger(c, true, NEVER))
    }

    @Test fun `씨라이언 모드가 꺼져 있으면 F3 도 무시 — 다른 유닛의 진짜 F3 키를 가로채지 않는다`() {
        assertFalse(sealionKeyTrigger(KeyEvent.KEYCODE_F3, false, NEVER))
    }

    @Test fun `쿨다운 안의 두 번째 F3 는 무시 — 한 번 누름이 F3 두 번으로 온 실측`() {
        assertFalse(sealionKeyTrigger(KeyEvent.KEYCODE_F3, true, 1_000L))
        assertFalse(sealionKeyTrigger(KeyEvent.KEYCODE_F3, true, SEALION_COOLDOWN_MS - 1))
        assertTrue(sealionKeyTrigger(KeyEvent.KEYCODE_F3, true, SEALION_COOLDOWN_MS))
    }
}
