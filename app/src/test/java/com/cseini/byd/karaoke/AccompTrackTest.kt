package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.audio.AccompReader
import com.cseini.byd.karaoke.audio.flushAccompWrite
import org.junit.Assert.assertEquals
import org.junit.Test

/** 실차(FB332): 곡 시작 직후 위치 이동으로 재생이 약 1.05초 멈췄다. rate=1000 이면 인덱스 1칸 = 1ms. */
class AccompTrackTest {
    private fun song(i: Int) = (i % 30000 + 1).toShort()
    private fun tape(upTo: Int) = ShortArray(20000) { if (it < upTo) song(it) else 0 }

    @Test fun `멈춤이 없으면 순서대로 읽는다`() {
        val buf = tape(5000)
        val r = AccompReader(400)
        for (k in 400 until 4000) assertEquals(song(k).toInt(), r.next(buf, 5000, true))
        assertEquals(0L, r.missed)
        assertEquals(0L, r.held)
    }

    @Test fun `멈춘 동안 읽기 위치가 그대로라 재개 뒤에도 반주가 이어진다`() {
        val buf = tape(1500)
        val r = AccompReader(400)
        repeat(600) { r.next(buf, 1500, true) }          // 1000 까지 들림, 쓰기는 500 앞섬
        repeat(1050) { assertEquals(0, r.next(buf, 1500, false)) }
        assertEquals(1000, r.pos)
        assertEquals(song(1000).toInt(), r.next(buf, 1500, true))
        assertEquals(0L, r.missed)
        assertEquals(1050L, r.held)
    }

    @Test fun `위치를 옮기면 들린 곳부터 새 위치의 소리가 이어진다`() {
        val buf = tape(1500)
        val r = AccompReader(400)
        repeat(600) { r.next(buf, 1500, true) }
        var written = flushAccompWrite(1500, r.pos)      // 안 들린 500 을 버림
        assertEquals(1000, written)
        repeat(1050) { r.next(buf, written, false) }
        for (k in 0 until 700) buf[written++] = song(9000 + k)   // 새 위치의 소리
        for (k in 0 until 700) assertEquals(song(9000 + k).toInt(), r.next(buf, written, true))
        assertEquals(0L, r.missed)
    }

    @Test fun `되돌리는 위치는 쓴 범위를 넘지 않는다`() {
        assertEquals(800, flushAccompWrite(800, 900))
        assertEquals(0, flushAccompWrite(800, -36))
    }

    @Test fun `쓰기보다 앞을 읽으면 빠진 길이로 센다`() {
        val r = AccompReader(100)
        repeat(50) { r.next(tape(120), 120, true) }
        assertEquals(30L, r.missed)
    }
}
