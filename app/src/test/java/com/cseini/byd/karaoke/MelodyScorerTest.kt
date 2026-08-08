package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.scoring.MelodyScorer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * MelodyScorer 종단 검증 — 합성 신호로 "잘 부름 > 엉망 > 안 부름" 변별이 실제로 되는지.
 * 실차 제보(잘 불렀는데 음정 1점)가 알고리즘 버그인지 신호 문제인지 가르기 위한 테스트.
 */
class MelodyScorerTest {

    private val sr = 22050

    /** 가이드 멜로디(주파수 열)를 노트당 400ms 로 이어붙인 신호. */
    private fun synth(freqs: List<Double>, noteMs: Int = 400, gain: Double = 0.5, harmonics: Int = 3): FloatArray {
        val n = freqs.size * noteMs * sr / 1000
        val out = FloatArray(n)
        val noteLen = noteMs * sr / 1000
        for (k in freqs.indices) {
            val f = freqs[k]
            for (i in 0 until noteLen) {
                val t = (k * noteLen + i).toDouble() / sr
                var v = 0.0
                for (h in 1..harmonics) v += sin(2 * PI * f * h * t) / h
                out[k * noteLen + i] = (v * gain / 1.8).toFloat()
            }
        }
        return out
    }

    private fun delay(x: FloatArray, ms: Int): FloatArray {
        val d = ms * sr / 1000
        val out = FloatArray(x.size + d)
        System.arraycopy(x, 0, out, d, x.size)
        return out
    }

    private fun addNoise(x: FloatArray, level: Float): FloatArray {
        val rnd = Random(42)
        return FloatArray(x.size) { x[it] + (rnd.nextFloat() - 0.5f) * 2f * level }
    }

    // 동요풍 8노트 멜로디 × 4회 반복(약 13초)
    private val melody: List<Double> = List(4) {
        listOf(262.0, 294.0, 330.0, 349.0, 392.0, 349.0, 330.0, 294.0)
    }.flatten()

    // 반주: 멜로디 + 낮은 코드음(잡음원)
    private fun accomp(): FloatArray {
        val mel = synth(melody, gain = 0.6)
        val chord = synth(List(melody.size) { 131.0 }, gain = 0.25)
        return FloatArray(mel.size) { mel[it] + chord[it] }
    }

    // 목소리: 같은 멜로디를 한 옥타브 위로(아이 목소리), 300ms 늦게, 약간의 노이즈
    private fun goodVoice(): FloatArray =
        addNoise(delay(synth(melody.map { it * 2 }, gain = 0.5, harmonics = 2), 300), 0.02f)

    // 엉망: 무관한 음 열
    private fun badVoice(): FloatArray {
        val rnd = Random(7)
        val wrong = List(melody.size) { 200.0 + rnd.nextDouble() * 300.0 }
        return addNoise(delay(synth(wrong, gain = 0.5, harmonics = 2), 300), 0.02f)
    }

    @Test
    fun `잘 부르면 음정·박자가 높게 나온다`() {
        val s = MelodyScorer.score(goodVoice(), sr, accomp(), sr)
        println("GOOD: total=${s.total}\n${s.breakdown}")
        assertTrue("멜로디 점수 낮음: ${s.pitchAccuracy}", s.pitchAccuracy >= 25)
        assertTrue("박자 점수 낮음: ${s.beatConsistency}", s.beatConsistency >= 15)
        assertTrue("총점 낮음: ${s.total}", s.total >= 80)
    }

    @Test
    fun `엉망으로 부르면 낮게 나온다`() {
        val good = MelodyScorer.score(goodVoice(), sr, accomp(), sr)
        val bad = MelodyScorer.score(badVoice(), sr, accomp(), sr)
        println("BAD: total=${bad.total}\n${bad.breakdown}")
        assertTrue("변별 실패: good=${good.total} bad=${bad.total}", good.total - bad.total >= 15)
        assertTrue("엉망인데 멜로디 점수 높음: ${bad.pitchAccuracy}", bad.pitchAccuracy <= 15)
    }

    @Test
    fun `녹음 시작 오프셋이 커도(1_5초) 매칭된다`() {
        val s = MelodyScorer.score(
            addNoise(delay(synth(melody.map { it * 2 }, gain = 0.5, harmonics = 2), 1500), 0.02f),
            sr, accomp(), sr,
        )
        println("OFFSET1500: total=${s.total}\n${s.breakdown}")
        assertTrue("큰 오프셋에서 멜로디 붕괴: ${s.pitchAccuracy}", s.pitchAccuracy >= 20)
    }
}
