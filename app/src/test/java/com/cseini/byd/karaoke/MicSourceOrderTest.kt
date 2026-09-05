package com.cseini.byd.karaoke

import android.media.MediaRecorder.AudioSource
import com.cseini.byd.karaoke.audio.MicRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 마이크 소스 선택 순서. 유닛마다 되는 소스가 달라 폴백이 핵심이다.
 *
 * 실차(씨라이언7·순정마이크) 로그 기준: 소리가 실제로 들어온 경우(hadSpeech=true)는 전부
 * VOICE_COMMUNICATION(src=7)이었다. 한때 이 소스가 마이크를 죽인다고 보고 막았다가,
 * 검증된 적 없는 대안으로 갈아타는 셈이라 되돌렸다 — 씨라이언 대응은 소스가 아니라
 * 'USB 장치를 선호 장치로 잡지 않기'로 한정한다.
 */
class MicSourceOrderTest {

    private val AUTO = AudioSource.UNPROCESSED   // 음성검색이 기본으로 요청하는 값

    @Test fun `USB 마이크가 있으면 요청한 소스를 먼저 쓴다`() {
        val order = MicRouting.sourceOrder(forced = null, autoRequested = AUTO, hasUsb = true)
        assertEquals(AUTO, order.first())
    }

    @Test fun `USB 를 안 쓰는 유닛에서는 통화 소스로 시작한다 — 실차에서 유일하게 소리가 들어온 소스`() {
        val order = MicRouting.sourceOrder(forced = null, autoRequested = AUTO, hasUsb = false)
        assertEquals(AudioSource.VOICE_COMMUNICATION, order.first())
    }

    @Test fun `사용자가 고른 소스가 최우선 — 통화 마이크 선택도 그대로 존중`() {
        // 씨라이언 사용자가 '통화 마이크'를 고르는 이유가 그걸로만 음성이 들어가서였다.
        // 가설로 사용자 선택을 덮어쓰면 유일하게 되는 설정을 막게 된다.
        for (s in listOf(AudioSource.VOICE_COMMUNICATION, AudioSource.MIC, AudioSource.VOICE_RECOGNITION)) {
            assertEquals(s, MicRouting.sourceOrder(forced = s, autoRequested = AUTO, hasUsb = false).first())
        }
    }

    @Test fun `어떤 조합이든 MIC 와 DEFAULT 폴백이 남는다`() {
        for (hasUsb in listOf(true, false)) {
            val order = MicRouting.sourceOrder(null, AUTO, hasUsb)
            assertTrue("hasUsb=$hasUsb", AudioSource.MIC in order && AudioSource.DEFAULT in order)
        }
    }

    @Test fun `같은 소스가 두 번 시도되지 않는다`() {
        val order = MicRouting.sourceOrder(forced = AudioSource.MIC, autoRequested = AUTO, hasUsb = true)
        assertEquals(order.size, order.toSet().size)
    }
}
