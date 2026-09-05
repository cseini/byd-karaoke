package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.voice.VoiceSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자동 마이크 소스 폴백 판정. 씰 순정마이크 실측: src=9(UNPROCESSED)는 열리지만 완전 무음(-99),
 * src=6(VOICE_RECOGNITION)은 -39~-48 로 잡힌다. 첫 ~1.2초가 완전 무음이면 다음 소스로 넘어간다.
 */
class SourceFallbackTest {

    @Test fun `완전 무음(-99)이 1_2초 지나고 다음 소스 있으면 전환`() {
        assertTrue(VoiceSearch.shouldSwitchSource(1300, -99f, hasSpeech = false, hasNext = true))
    }

    @Test fun `주변음이라도 있으면(-60) 전환 안 함 — 정상 소스, 사용자가 조용한 것뿐`() {
        // 씨라이언 6.56 실측 배경 -57~-62. 이건 신호 경로가 살아있는 것 → 소스 바꾸면 안 됨.
        assertFalse(VoiceSearch.shouldSwitchSource(1300, -60f, hasSpeech = false, hasNext = true))
        assertFalse(VoiceSearch.shouldSwitchSource(3000, -55f, hasSpeech = false, hasNext = true))
    }

    @Test fun `말소리가 잡혔으면 전환 안 함`() {
        assertFalse(VoiceSearch.shouldSwitchSource(1300, -30f, hasSpeech = true, hasNext = true))
    }

    @Test fun `판정 시점(1_2초) 전엔 전환 안 함 — 마이크 안정화 대기`() {
        assertFalse(VoiceSearch.shouldSwitchSource(800, -99f, hasSpeech = false, hasNext = true))
        assertFalse(VoiceSearch.shouldSwitchSource(VoiceSearch.DEAD_CHECK_MS, -99f, hasSpeech = false, hasNext = true))
    }

    @Test fun `마지막 소스면(다음 없음) 무음이어도 전환 안 함`() {
        assertFalse(VoiceSearch.shouldSwitchSource(1300, -99f, hasSpeech = false, hasNext = false))
    }
}
