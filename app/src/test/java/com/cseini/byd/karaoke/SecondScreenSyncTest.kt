package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.share.SecondScreenState
import com.cseini.byd.karaoke.share.SecondScreenState.PlaySnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세컨드스크린 서버-상대 시간 싱크의 순수함수 검증.
 * livePositionMs = 서버가 /now 에서 내려주는 라이브 위치, syncDecision = 태블릿 영상 보정(JS 미러링).
 */
class SecondScreenSyncTest {

    @Test fun `재생 중이면 경과 시간만큼 위치를 보간한다`() {
        val s = PlaySnap(positionMs = 10_000, atNanos = 1_000_000_000L, isPlaying = true, speed = 1f)
        val live = SecondScreenState.livePositionMs(s, 1_000_000_000L + 2_000_000_000L)  // 2초 뒤
        assertEquals(12_000, live)
    }

    @Test fun `속도 1_5배면 경과의 1_5배만큼 전진`() {
        val s = PlaySnap(positionMs = 0, atNanos = 1L, isPlaying = true, speed = 1.5f)
        val live = SecondScreenState.livePositionMs(s, 1L + 2_000_000_000L)  // 2초 뒤
        assertEquals(3_000, live)
    }

    @Test fun `일시정지면 보간 없이 스냅샷 위치 그대로`() {
        val s = PlaySnap(positionMs = 30_000, atNanos = 1L, isPlaying = false, speed = 1f)
        assertEquals(30_000, SecondScreenState.livePositionMs(s, 1L + 9_000_000_000L))
    }

    @Test fun `atNanos 가 0이면 아직 재생 전 - 보간하지 않는다`() {
        val s = PlaySnap(positionMs = 500, atNanos = 0L, isPlaying = true, speed = 1f)
        assertEquals(500, SecondScreenState.livePositionMs(s, 9_000_000_000L))
    }

    @Test fun `곡이 바뀌면 무조건 seek`() {
        assertTrue(SecondScreenState.syncDecision(5000, 4000, 1f, videoChanged = true).seek)
    }

    @Test fun `1초 넘게 어긋나면 seek(앞서든 뒤처지든)`() {
        assertTrue(SecondScreenState.syncDecision(6000, 4000, 1f, false).seek)
        assertTrue(SecondScreenState.syncDecision(2000, 4000, 1f, false).seek)
    }

    @Test fun `작은 오차는 seek 없이 재생속도 미세조정으로 수렴`() {
        val d = SecondScreenState.syncDecision(4200, 4000, 1f, false)  // 영상 200ms 뒤처짐
        assertFalse(d.seek)
        assertTrue(d.playbackRate > 1.0)       // 따라잡으려 살짝 빠르게
        assertTrue(d.playbackRate <= 1.03)     // 상한 안
    }

    @Test fun `보정 상한은 정확히 3퍼센트`() {
        assertEquals(1.03, SecondScreenState.syncDecision(4999, 4000, 1f, false).playbackRate, 1e-9)
        assertEquals(0.97, SecondScreenState.syncDecision(3001, 4000, 1f, false).playbackRate, 1e-9)
    }

    @Test fun `속도가 1이 아니어도 그 속도를 중심으로 미세조정`() {
        // 오차 0 → 그 속도 그대로
        assertEquals(1.2, SecondScreenState.syncDecision(4000, 4000, 1.2f, false).playbackRate, 1e-6)
    }
}
