package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.audio.PitchDetector
import com.cseini.byd.karaoke.audio.SignalAnalysis
import com.cseini.byd.karaoke.scoring.ScoringEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 합성 신호로 DSP·채점을 차 없이 검증. `./gradlew :app:testDebugUnitTest` */
class DspTest {

    private val sr = 44100

    private fun tone(freq: Double, seconds: Double, amp: Double = 0.3, vibratoHz: Double = 0.0, vibratoCents: Double = 0.0): FloatArray {
        val n = (sr * seconds).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = if (vibratoHz > 0) freq * Math.pow(2.0, (vibratoCents / 1200.0) * sin(2 * PI * vibratoHz * t)) else freq
            phase += 2 * PI * f / sr
            out[i] = (amp * sin(phase)).toFloat()
        }
        return out
    }

    @Test
    fun pitchDetector_detects220() {
        val det = PitchDetector(sr)
        val frame = tone(220.0, 0.05)  // A3
        val r = det.detect(frame)
        assertTrue("voiced 여야 함", r.voiced)
        assertEquals(220f, r.f0, 6f) // ±6Hz
    }

    @Test
    fun pitchDetector_detects440() {
        val det = PitchDetector(sr)
        val r = det.detect(tone(440.0, 0.05))
        assertTrue(r.voiced)
        assertEquals(440f, r.f0, 8f)
    }

    @Test
    fun silence_isUnvoiced() {
        val det = PitchDetector(sr)
        val r = det.detect(FloatArray((sr * 0.05).toInt()))
        assertTrue("무음은 voiced 아님", !r.voiced)
    }

    @Test
    fun stableTone_scoresHigherThanWobble() {
        // 안정: 순수 220Hz
        val stable = ScoringEngine.score(tone(220.0, 4.0), sr)
        // 불안정: 220↔247 반복(음 흔들림)
        val wobble = buildWobble()
        val wob = ScoringEngine.score(wobble, sr)
        assertTrue("안정 음정 점수 > 흔들리는 음정 점수 (안정=${stable.pitchStability}, 흔들=${wob.pitchStability})",
            stable.pitchStability > wob.pitchStability)
        assertTrue("안정 총점이 유의미하게 나와야 함", stable.total in 1..100)
    }

    @Test
    fun vibrato_getsBonusOverFlat() {
        val flat = ScoringEngine.score(tone(300.0, 4.0), sr)
        val vib = ScoringEngine.score(tone(300.0, 4.0, vibratoHz = 5.5, vibratoCents = 60.0), sr)
        assertTrue("비브라토가 평탄음보다 고음·비브라토 점수 높아야 (평탄=${flat.vibratoReach}, 비브=${vib.vibratoReach})",
            vib.vibratoReach >= flat.vibratoReach)
    }

    @Test
    fun inTuneNotes_scoreHigherAccuracyThanGlissando() {
        // 반음 격자에 정확히 앉는 또렷한 음들(A3/C4/E4 반복)
        val inTune = ScoringEngine.score(discreteNotes(), sr)
        // 200→400Hz 계속 미끄러지는 글리산도(음정을 짚지 않음)
        val gliss = ScoringEngine.score(glissando(), sr)
        assertTrue(
            "또렷한 음정이 글리산도보다 멜로디 음정 점수 높아야 (또렷=${inTune.pitchAccuracy}, 미끄럼=${gliss.pitchAccuracy})",
            inTune.pitchAccuracy > gliss.pitchAccuracy
        )
        assertTrue("또렷한 음정은 절반 이상 득점해야 (=${inTune.pitchAccuracy}/30)", inTune.pitchAccuracy >= 15)
    }

    @Test
    fun analyze_marksVoicedRegions() {
        val frames = SignalAnalysis.analyze(tone(200.0, 1.0), sr)
        val voiced = frames.count { it.voiced }
        assertTrue("대부분 유성으로 잡혀야 함 ($voiced/${frames.size})", voiced > frames.size / 2)
    }

    /** 반음 격자에 정확히 앉는 음들(A3=220, C4=261.63, E4=329.63) 반복. */
    private fun discreteNotes(): FloatArray {
        val freqs = doubleArrayOf(220.0, 261.63, 329.63, 261.63)
        val parts = ArrayList<FloatArray>()
        for (i in 0 until 8) parts.add(tone(freqs[i % freqs.size], 0.6))
        return concat(parts)
    }

    /** 200→400Hz 로그 선형 글리산도(음정을 짚지 않고 계속 미끄러짐). */
    private fun glissando(): FloatArray {
        val seconds = 4.0
        val n = (sr * seconds).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val frac = i.toDouble() / n
            val f = 200.0 * Math.pow(2.0, frac) // 200→400Hz
            phase += 2 * PI * f / sr
            out[i] = (0.3 * sin(phase)).toFloat()
        }
        return out
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var p = 0
        for (part in parts) { part.copyInto(out, p); p += part.size }
        return out
    }

    private fun buildWobble(): FloatArray {
        // 40ms 마다 200↔300Hz(약 700 cents) 급변 → 프레임(46ms)마다 경계를 물어 프레임간 지터 큼
        val seg = 0.04
        val parts = ArrayList<FloatArray>()
        for (i in 0 until 100) parts.add(tone(if (i % 2 == 0) 200.0 else 300.0, seg))
        val total = parts.sumOf { it.size }
        val out = FloatArray(total)
        var p = 0
        for (part in parts) { part.copyInto(out, p); p += part.size }
        return out
    }
}
