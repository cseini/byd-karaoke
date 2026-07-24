package com.cseini.byd.karaoke.scoring

import com.cseini.byd.karaoke.audio.SignalAnalysis
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 레퍼런스(원곡 음정) 없이 마이크 입력만으로 100점 채점. 순수 Kotlin(테스트 가능).
 *
 * ⚠️ 원곡 오디오에서 정답 멜로디를 추출하는 것은 YouTube ToS·저작권 위반이라 하지 않는다.
 * 대신 저작권 문제 없이 "멜로디 음정을 잘 짚는지"를 다음 두 축으로 본다:
 *   · 음정 정확도(in-tune) — 부른 음이 실제 음정(12평균율 반음 격자)에 앉는가. 미끄러짐/음이탈은 감점.
 *   · 음정 안정성 — 한 음을 흔들림 없이 유지하는가.
 * 개인 조성(전체적으로 살짝 높/낮게)은 글로벌 튠 오프셋을 보정해 벌하지 않는다.
 *
 * 배점: 음정정확도 30 / 음정안정성 20 / 박자규칙성 25 / 음량 10 / 고음·비브라토 15.
 */
object ScoringEngine {

    private const val MAX_ACCURACY = 30
    private const val MAX_STABILITY = 20
    private const val MAX_BEAT = 25
    private const val MAX_VOLUME = 10
    private const val MAX_VIBRATO = 15

    data class Score(
        val total: Int,
        val pitchAccuracy: Int,    // 0..30 — 멜로디 음정(음이 정확한 음정에 앉는가)
        val pitchStability: Int,   // 0..20 — 음 유지 안정성
        val beatConsistency: Int,  // 0..25
        val volumeDynamics: Int,   // 0..10
        val vibratoReach: Int,     // 0..15
        val voicedPct: Int,
        val medianF0: Float,
        val breakdown: String,
    )

    fun score(samples: FloatArray, sampleRate: Int): Score =
        score(SignalAnalysis.analyze(samples, sampleRate))

    fun score(frames: List<SignalAnalysis.Frame>): Score {
        val voiced = frames.filter { it.voiced && it.f0 > 0f }
        val voicedPct = if (frames.isEmpty()) 0 else (100 * voiced.size / frames.size)

        if (voiced.size < 8) {
            return Score(0, 0, 0, 0, 0, 0, voicedPct, 0f,
                "발성이 거의 감지되지 않았습니다. 마이크·음량을 확인하세요.")
        }

        val f0s = voiced.map { it.f0 }
        val medianF0 = median(f0s)

        val accuracy = (pitchAccuracyFraction(f0s) * MAX_ACCURACY).roundToInt().coerceIn(0, MAX_ACCURACY)
        val stability = (pitchStabilityFraction(f0s) * MAX_STABILITY).roundToInt().coerceIn(0, MAX_STABILITY)
        val beat = (beatConsistencyFraction(frames) * MAX_BEAT).roundToInt().coerceIn(0, MAX_BEAT)
        val volume = (volumeDynamicsFraction(frames) * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)
        val vibrato = (vibratoReachFraction(voiced) * MAX_VIBRATO).roundToInt().coerceIn(0, MAX_VIBRATO)
        val rawBase = (accuracy + stability + beat + volume + vibrato).coerceIn(0, 100)
        // 참여도: 실제 발성 비율이 낮으면(거의 안 부름) 점수를 끌어내려 변별력을 준다.
        val engage = (voicedPct / 25.0).coerceIn(0.45, 1.0)
        val raw = (rawBase * engage).roundToInt().coerceIn(0, 100)
        // 노래방 — 어느 정도 후하되(최저 45), 잘 부를수록 확실히 높게(변별력).
        val total = generous(raw)

        val bd = buildString {
            append("총점 ${total}점\n")
            append("· 멜로디 음정 $accuracy/$MAX_ACCURACY — ${accuracyComment(accuracy)}\n")
            append("· 음정 안정성 $stability/$MAX_STABILITY — ${stabilityComment(stability)}\n")
            append("· 박자 규칙성 $beat/$MAX_BEAT — ${beatComment(beat)}\n")
            append("· 음량 표현 $volume/$MAX_VOLUME — ${volumeComment(volume)}\n")
            append("· 고음·비브라토 $vibrato/$MAX_VIBRATO — ${vibratoComment(vibrato)}\n")
            append("(발성 ${voicedPct}%, 중심음 ${medianF0.toInt()}Hz. 원곡 대조가 아니라 음이 정확한 음정에 앉는지로 멜로디를 평가합니다.)")
        }
        return Score(total, accuracy, stability, beat, volume, vibrato, voicedPct, medianF0, bd)
    }

    /** 원점수를 살짝 후한 곡선으로 — 최저 45, 잘 부르면 100. 변별력을 위해 기울기를 살린다. */
    private fun generous(raw: Int): Int = (45 + raw * 0.55).roundToInt().coerceIn(45, 100)

    // ── 음정 정확도: 부른 음이 반음 격자에 얼마나 정확히 앉는가(글로벌 튠 오프셋 보정) ──
    private fun pitchAccuracyFraction(f0s: List<Float>): Double {
        // 각 프레임의 '가장 가까운 반음'까지의 편차(cents, [-50,50])
        val devs = f0s.map { f ->
            val cents = 1200.0 * ln(f / 440.0) / ln(2.0)
            wrapCents(cents - 100.0 * (cents / 100.0).roundToInt())
        }
        if (devs.isEmpty()) return 0.0
        // 개인 조성(전체적 높낮이) 보정: 편차의 중앙값을 빼고 나머지(residual)만 본다
        val offset = median(devs)
        val residual = devs.map { abs(wrapCents(it - offset)) }
        val medDev = median(residual) // in-tune 이면 ~10cents, 음이탈/미끄러짐이면 30~50
        // 0 cents → 1.0, 50 cents → 0.0 (약간 관대하게 45 기준)
        return ((45.0 - medDev) / 45.0).coerceIn(0.0, 1.0)
    }

    // ── 음정 안정성: 프레임간 흔들림(jitter)이 작을수록 ──
    private fun pitchStabilityFraction(f0s: List<Float>): Double {
        val cents = ArrayList<Double>()
        for (i in 1 until f0s.size) {
            val a = f0s[i - 1]; val b = f0s[i]
            if (a > 0 && b > 0) cents.add(minOf(abs(1200.0 * ln(b / a) / ln(2.0)), 400.0))
        }
        if (cents.isEmpty()) return 0.0
        val jitter = median(cents)
        return ((200.0 - jitter) / 180.0).coerceIn(0.0, 1.0)
    }

    // ── 박자 규칙성: 발성 온셋(구절 시작) 간격의 변동계수(CV)가 작을수록 ──
    // 목소리만 채점하면 유성/무성이 프레임 단위로 깜빡여 온셋이 잘게 쪼개진다.
    // 250ms 이내 온셋은 같은 구절로 합쳐(디바운스), 구절 단위 리듬으로 평가한다.
    private fun beatConsistencyFraction(frames: List<SignalAnalysis.Frame>): Double {
        val raw = ArrayList<Double>()
        var prev = false
        for (f in frames) {
            if (f.voiced && !prev) raw.add(f.timeSec)
            prev = f.voiced
        }
        val onsets = ArrayList<Double>()
        for (t in raw) if (onsets.isEmpty() || t - onsets.last() >= 0.25) onsets.add(t)
        if (onsets.size < 4) return 0.5
        val intervals = ArrayList<Double>()
        for (i in 1 until onsets.size) intervals.add(onsets[i] - onsets[i - 1])
        val mean = intervals.average()
        if (mean <= 0) return 0.5
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        val cv = Math.sqrt(variance) / mean
        // 노래는 완전히 규칙적이지 않으므로 관대하게: cv 0→만점, cv 1.5→0.
        return ((1.5 - cv) / 1.2).coerceIn(0.0, 1.0)
    }

    // ── 음량 다이나믹스: 적당한 변화 보상 + 클리핑 페널티 ──
    private fun volumeDynamicsFraction(frames: List<SignalAnalysis.Frame>): Double {
        val voicedDb = frames.filter { it.voiced }.map { it.rmsDb.toDouble() }
        if (voicedDb.size < 5) return 0.4
        val mean = voicedDb.average()
        val std = Math.sqrt(voicedDb.sumOf { (it - mean) * (it - mean) } / voicedDb.size)
        val expression = when {
            std < 1.0 -> 0.3
            std <= 10.0 -> 1.0
            else -> 0.5
        }
        val clip = frames.count { it.rmsDb > -3f }.toDouble() / frames.size
        val clipPenalty = (clip * 2).coerceAtMost(0.5)
        return (expression - clipPenalty).coerceIn(0.0, 1.0)
    }

    // ── 고음 도달(0.5) + 비브라토(0.5) ──
    private fun vibratoReachFraction(voiced: List<SignalAnalysis.Frame>): Double {
        val f0s = voiced.map { it.f0 }
        val reach = f0s.count { it > 150f }.toDouble() / f0s.size.coerceAtLeast(1)
        val vibrato = vibratoPresence(voiced)
        return (reach.coerceIn(0.0, 1.0) * 0.5) + (vibrato.coerceIn(0.0, 1.0) * 0.5)
    }

    private fun vibratoPresence(voiced: List<SignalAnalysis.Frame>): Double {
        if (voiced.size < 20) return 0.0
        val dtSec = SignalAnalysis.HOP_MS / 1000.0
        val fsTrack = 1.0 / dtSec
        val med = median(voiced.map { it.f0 })
        val cents = DoubleArray(voiced.size) { 1200.0 * ln(voiced[it].f0 / med) / ln(2.0) }
        val m = cents.average()
        for (i in cents.indices) cents[i] -= m
        val energy = cents.sumOf { it * it } + 1e-9
        var best = 0.0
        val lagLo = (fsTrack / 7.0).toInt().coerceAtLeast(1)
        val lagHi = (fsTrack / 4.0).toInt().coerceAtLeast(lagLo + 1)
        for (lag in lagLo..lagHi) {
            var s = 0.0
            for (i in 0 until cents.size - lag) s += cents[i] * cents[i + lag]
            val r = s / energy
            if (r > best) best = r
        }
        val depth = Math.sqrt(energy / cents.size)
        val depthFactor = (depth / 50.0).coerceIn(0.0, 1.0)
        return best.coerceIn(0.0, 1.0) * depthFactor
    }

    // ── helpers ──
    /** cents 를 [-50,50] 로 접기(가장 가까운 반음 기준). */
    private fun wrapCents(c: Double): Double {
        var x = c
        while (x > 50.0) x -= 100.0
        while (x < -50.0) x += 100.0
        return x
    }

    private fun median(xs: List<Float>): Float {
        if (xs.isEmpty()) return 0f
        return xs.sorted()[xs.size / 2]
    }

    @JvmName("medianD")
    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        return xs.sorted()[xs.size / 2]
    }

    private fun accuracyComment(s: Int) = when {
        s >= 24 -> "음정을 정확히 짚습니다."
        s >= 15 -> "대체로 음정에 맞으나 살짝 벗어납니다."
        else -> "음이 미끄러지거나 음정을 자주 벗어납니다."
    }
    private fun stabilityComment(s: Int) = when {
        s >= 16 -> "음을 흔들림 없이 유지합니다."
        s >= 10 -> "대체로 안정적이나 흔들림이 조금 있어요."
        else -> "음이 자주 흔들립니다."
    }
    private fun beatComment(s: Int) = when {
        s >= 20 -> "박자가 일정합니다."
        s >= 12 -> "박자가 대체로 규칙적입니다."
        else -> "박자 간격이 들쭉날쭉합니다."
    }
    private fun volumeComment(s: Int) = when {
        s >= 7 -> "강약 표현이 좋습니다."
        s >= 4 -> "표현이 조금 밋밋하거나 과합니다."
        else -> "음량이 단조롭거나 클리핑됩니다."
    }
    private fun vibratoComment(s: Int) = when {
        s >= 11 -> "고음·비브라토가 잘 살았습니다."
        s >= 6 -> "고음/비브라토가 부분적으로 나타납니다."
        else -> "고음 도달·비브라토가 약합니다."
    }
}
