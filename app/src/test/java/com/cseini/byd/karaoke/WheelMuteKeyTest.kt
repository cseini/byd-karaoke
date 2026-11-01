package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.KeyCatcherService.Companion.isWheelMute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휠 무음 버튼 판정. 같은 이름 KEYCODE_AUTO_VOLUME_MUTE 가 차종마다 다른 번호로 온다
 * (아토3=293 부산 제보 FB3827, 아토3 페이스리프트=309 FB3834) — 번호가 아니라 이름으로도 알아봐야 한다.
 */
class WheelMuteKeyTest {

    @Test fun `기존 아토3 번호 293 은 이름이 없어도 알아본다`() {
        assertTrue(isWheelMute(293, "293"))
        assertTrue(isWheelMute(293, "KEYCODE_AUTO_VOLUME_MUTE"))
    }

    @Test fun `페이스리프트 번호 309 는 이름으로 알아본다`() {
        assertTrue(isWheelMute(309, "KEYCODE_AUTO_VOLUME_MUTE"))
    }

    @Test fun `볼륨 위아래와 일반 키는 무음 버튼이 아니다`() {
        assertFalse(isWheelMute(291, "KEYCODE_AUTO_VOLUME_UP"))
        assertFalse(isWheelMute(292, "KEYCODE_AUTO_VOLUME_DOWN"))
        assertFalse(isWheelMute(307, "KEYCODE_AUTO_VOLUME_UP"))
        assertFalse(isWheelMute(133, "KEYCODE_F3"))
        assertFalse(isWheelMute(309, "309"))
    }
}
