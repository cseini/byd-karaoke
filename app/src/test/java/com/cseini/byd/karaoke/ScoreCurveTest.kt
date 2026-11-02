package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.audio.SignalAnalysis
import com.cseini.byd.karaoke.scoring.ScoreCurve
import com.cseini.byd.karaoke.scoring.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 채점 기준(후한 정도) 3단계. 0=원래대로(지금까지와 완전히 같아야 한다) 1=후하게 2=아주 후하게(아이용).
 * 요구: 아주 후하게 = 못 불러도 80점 이상, 평범하게만 불러도 100점이 많이 나온다.
 */
class ScoreCurveTest {

    @Test fun `원래대로는 지금까지의 공식과 모든 원점수에서 똑같다`() {
        for (raw in 0..100) {
            assertEquals("멜로디 채점기 raw=$raw", (35 + raw * 0.7).roundToInt().coerceIn(35, 100), ScoreCurve.total(raw, 0, 0.7))
            assertEquals("자체 채점기 raw=$raw", (35 + raw * 0.72).roundToInt().coerceIn(35, 100), ScoreCurve.total(raw, 0, 0.72))
        }
    }

    @Test fun `아주 후하게는 못 불러도 80점 이상이고 평범하게 불러도 100점이다`() {
        assertEquals(80, ScoreCurve.total(0, 2, 0.7))
        assertTrue((0..100).all { ScoreCurve.total(it, 2, 0.7) in 80..100 })
        assertTrue("raw 56 이상은 100점", (56..100).all { ScoreCurve.total(it, 2, 0.7) == 100 })
        assertEquals("실차에서 웬만큼(raw 80)", 100, ScoreCurve.total(80, 2, 0.7))
        assertEquals("실차에서 엉망(raw 37)", 93, ScoreCurve.total(37, 2, 0.7))
    }

    @Test fun `후하게는 최저 60점이고 웬만큼 부르면 96점 아주 잘 부르면 100점이다`() {
        assertEquals(60, ScoreCurve.total(0, 1, 0.7))
        assertTrue((0..100).all { ScoreCurve.total(it, 1, 0.7) in 60..100 })
        assertEquals(96, ScoreCurve.total(80, 1, 0.7))
        assertEquals(99, ScoreCurve.total(87, 1, 0.7))
        assertTrue("raw 88 이상은 100점", (88..100).all { ScoreCurve.total(it, 1, 0.7) == 100 })
    }

    @Test fun `원점수가 오를수록 점수는 내려가지 않는다`() {
        for (level in 0..2) for (slope in listOf(0.7, 0.72)) {
            var prev = -1
            for (raw in 0..100) {
                val t = ScoreCurve.total(raw, level, slope)
                assertTrue("level=$level raw=$raw", t >= prev)
                prev = t
            }
        }
    }

    @Test fun `단계가 높을수록 같은 원점수에서 점수가 같거나 높다`() {
        for (slope in listOf(0.7, 0.72)) for (raw in 0..100) {
            val a = ScoreCurve.total(raw, 0, slope); val b = ScoreCurve.total(raw, 1, slope); val c = ScoreCurve.total(raw, 2, slope)
            assertTrue("raw=$raw slope=$slope: $a $b $c", a <= b && b <= c)
        }
    }

    @Test fun `범위를 벗어난 원점수와 모르는 단계는 안전하게 처리한다`() {
        assertEquals(35, ScoreCurve.total(-5, 0, 0.7))
        assertEquals(100, ScoreCurve.total(250, 0, 0.7))
        assertEquals(ScoreCurve.total(50, 0, 0.7), ScoreCurve.total(50, 9, 0.7))   // 모르는 단계 = 원래대로
        assertEquals(80, ScoreCurve.total(-5, 2, 0.7))
    }

    // ── 채점기까지 설정이 실제로 흘러가는지 ──

    private fun frames(n: Int, f0: (Int) -> Float) = (0 until n).map { SignalAnalysis.Frame(it * 0.02, f0(it), true, -20f) }

    /** 음정이 계속 흔들리는 엉성한 가창 — 원점수가 낮게 나온다. */
    private val poor = frames(300) { 180f + 70f * sin(it * 0.9).toFloat() + (it % 7) * 9f }

    @Test fun `채점기에 단계를 넘기면 최종 점수에 반영된다`() {
        val t0 = ScoringEngine.score(poor, null, 0).total
        val t1 = ScoringEngine.score(poor, null, 1).total
        val t2 = ScoringEngine.score(poor, null, 2).total
        assertTrue("원래대로는 후하게보다 낮다: $t0 $t1 $t2", t0 < t1 && t1 < t2)
        assertTrue("아주 후하게는 못 불러도 80점 이상: $t2", t2 in 80..100)
        assertTrue("후하게 최저 60: $t1", t1 in 60..100)
        assertEquals("기본 인자는 원래대로", t0, ScoringEngine.score(poor).total)
    }

    @Test fun `후한 단계에서만 결과 설명에 채점 기준이 적힌다`() {
        assertFalse(ScoringEngine.score(poor, null, 0).breakdown.contains("채점 기준"))
        assertTrue(ScoringEngine.score(poor, null, 1).breakdown.contains("채점 기준: 후하게"))
        assertTrue(ScoringEngine.score(poor, null, 2).breakdown.contains("채점 기준: 아주 후하게(아이용)"))
    }

    @Test fun `소리가 안 잡힌 0점은 어떤 단계에서도 그대로 0점이다 — 마이크 점검 안내라서`() {
        val silent = frames(100) { 0f }.map { it.copy(voiced = false) }
        for (level in 0..2) assertEquals("level=$level", 0, ScoringEngine.score(silent, null, level).total)
    }
}
