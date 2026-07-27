package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.scoring.BeatTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** 반주 비트 추정 + 격자 정렬 채점을 합성 신호로 검증(차 없이). */
class BeatTrackerTest {

    private val sr = 16000

    /** 지정 BPM 으로 8초짜리 클릭 트랙(짧은 노이즈 버스트) 생성. */
    private fun clickTrack(bpm: Double, seconds: Double = 8.0): FloatArray {
        val period = 60.0 / bpm
        val out = FloatArray((sr * seconds).toInt())
        val rnd = Random(42)
        var t = 0.0
        while (t < seconds) {
            val start = (t * sr).toInt()
            val len = (0.03 * sr).toInt()   // 30ms 버스트
            for (i in 0 until len) {
                val idx = start + i
                if (idx < out.size) out[idx] = (rnd.nextFloat() * 2 - 1) * 0.8f
            }
            t += period
        }
        return out
    }

    @Test
    fun detectsTempo120() {
        val p = BeatTracker.detectBeatPeriod(clickTrack(120.0), sr)
        assertNotNull("120BPM 비트 검출 실패", p)
        // 0.5초(정박) 또는 옥타브(0.25/1.0) 허용, 단 여기선 0.5 기대.
        assertTrue("검출 주기 $p 가 0.5초 근처 아님", abs(p!! - 0.5) < 0.05)
    }

    @Test
    fun detectsTempo90() {
        val p = BeatTracker.detectBeatPeriod(clickTrack(90.0), sr)
        assertNotNull("90BPM 비트 검출 실패", p)
        assertTrue("검출 주기 $p 가 0.667초 근처 아님", abs(p!! - 0.6667) < 0.06)
    }

    @Test
    fun noiseHasNoConfidentBeat() {
        val rnd = Random(1)
        val noise = FloatArray(sr * 8) { (rnd.nextFloat() * 2 - 1) * 0.3f }
        // 균일 잡음은 뚜렷한 비트가 없어야 함(신뢰도 컷으로 null).
        val p = BeatTracker.detectBeatPeriod(noise, sr)
        assertTrue("잡음인데 비트가 뚜렷하다고 나옴: $p", p == null)
    }

    @Test
    fun onBeatScoresHigherThanRandom() {
        val period = 0.5
        // 정박: 그리드에 정확히 얹힌 온셋
        val onGrid = (0 until 16).map { it * period }
        // 무작위: 8초 구간 랜덤 온셋
        val rnd = Random(7)
        val random = (0 until 16).map { rnd.nextDouble() * 8.0 }.sorted()

        val onScore = BeatTracker.alignmentFraction(onGrid, period)
        val rndScore = BeatTracker.alignmentFraction(random, period)

        assertTrue("정박 점수 낮음: $onScore", onScore > 0.85)
        assertTrue("정박($onScore)이 무작위($rndScore)보다 확실히 높아야", onScore - rndScore > 0.3)
    }

    @Test
    fun consistentOffsetStillCountsAsOnBeat() {
        val period = 0.5
        // 일정한 위상 오프셋(녹음 지연 등) — 여전히 리듬에 맞는 것으로 봐야 함.
        val shifted = (0 until 16).map { it * period + 0.12 }
        val score = BeatTracker.alignmentFraction(shifted, period)
        assertTrue("일정 오프셋인데 점수 낮음: $score", score > 0.85)
    }

    @Test
    fun halfBeatSyncopationIsModerate() {
        val period = 0.5
        // 절반씩 어긋난 온셋(엇박) 섞임 → 중간 점수 근처
        val mixed = (0 until 16).map { it * period + if (it % 2 == 0) 0.0 else period / 2 }
        val score = BeatTracker.alignmentFraction(mixed, period)
        // 두 위상이 섞이면 단일 격자 정렬이 안 돼 낮아짐(무작위보단 위).
        assertTrue("엇박 점수 범위 벗어남: $score", score in 0.2..0.75)
    }
}
