package com.cseini.byd.karaoke.scoring

import com.cseini.byd.karaoke.audio.SignalAnalysis
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 멜로디 대조 채점 — TV 노래방식.
 * 반주(가이드 멜로디)에서 실측 음정 타임라인을 뽑아 '정답'으로 삼고, 마이크 음정을 시간축에서
 * 직접 비교한다(MIDI 불필요, Frank Karaoke 방식 벤치마킹). 옥타브는 무시(남/여 키 차이 허용).
 *
 * 배점: 멜로디 일치 40 / 박자 타이밍 25 / 완창률 15 / 음량 표현 10 / 고음·비브라토 10.
 * 치팅 방어: 마이크가 반주 유출(스피커 소리)만 담으면 정답과 '지나치게 완벽'하게 일치
 * (중앙 편차 <12cents & 음량 곡선 상관 >0.9) → 노래 안 부른 것으로 판정.
 */
object MelodyScorer {

    private const val MATCH_CENTS = 60.0        // 이내면 일치(반음=100)
    private const val LAG_SEARCH_MS = 600       // 전역 정렬 탐색 범위(버퍼 시작 오프셋 흡수)
    private const val LAG_STEP_MS = 25

    fun score(voice: FloatArray, voiceRate: Int, accomp: FloatArray, accompRate: Int): ScoringEngine.Score {
        val mic = SignalAnalysis.analyze(voice, voiceRate)
        val ref = SignalAnalysis.analyze(bandpass(accomp, accompRate), accompRate)
        val hopSec = SignalAnalysis.HOP_MS / 1000.0

        val micVoiced = mic.count { it.voiced && it.f0 > 0f }
        if (micVoiced < 8) return noSing("발성이 거의 감지되지 않았습니다. 마이크에 대고 불러주세요!")

        val refVoicedIdx = ref.indices.filter { ref[it].voiced && ref[it].f0 > 0f }
        if (refVoicedIdx.size < 20) {
            // 반주에서 멜로디를 못 뽑으면(가이드 멜로디 없는 영상 등) 기존 방식으로 폴백
            return ScoringEngine.score(mic, BeatTracker.detectBeatPeriod(accomp, accompRate))
        }

        // ── 전역 정렬: 녹음 버퍼 시작 오프셋·시스템 지연을 흡수하는 최적 시차 탐색 ──
        val lagSteps = LAG_SEARCH_MS / LAG_STEP_MS
        val hopPerStep = (LAG_STEP_MS / SignalAnalysis.HOP_MS).coerceAtLeast(1.0)
        var bestLag = 0
        var bestMatched = -1
        for (s in -lagSteps..lagSteps) {
            val shift = (s * hopPerStep).roundToInt()
            val m = countMatched(mic, ref, shift).first
            if (m > bestMatched) { bestMatched = m; bestLag = shift }
        }
        val (matched, sang, devs) = countMatchedFull(mic, ref, bestLag)
        val matchable = refVoicedIdx.size

        if (sang < 8) return noSing("멜로디 구간에서 목소리가 잡히지 않았어요.")

        // ── 치팅(반주 유출) 판정: 사람은 신디 가이드와 12cents 중앙편차로 계속 일치하지 못한다 ──
        val medDev = median(devs)
        val envCorr = envelopeCorr(mic, ref, bestLag)
        if (matched >= 20 && medDev < 12.0 && envCorr > 0.9) {
            return noSing("노래가 감지되지 않았어요(반주 소리만 녹음됨). 마이크에 대고 불러주세요!")
        }

        // ── 박자 타이밍: 곡을 8구간으로 나눠 구간별 최적 시차의 흔들림(전역 시차는 무죄) ──
        val segLags = segmentLags(mic, ref, bestLag)
        val timingDevMs = if (segLags.size >= 3) {
            val med = median(segLags.map { it.toDouble() })
            median(segLags.map { abs(it - med) }) * SignalAnalysis.HOP_MS
        } else 120.0

        // ── 점수 ──
        val quality = matched.toDouble() / sang
        val coverage = sang.toDouble() / matchable
        val pitch = (40 * ((quality - 0.15) / 0.55).coerceIn(0.0, 1.0)).roundToInt()
        val timing = (25 * (1.0 - timingDevMs / 250.0).coerceIn(0.0, 1.0)).roundToInt()
        val cover = (15 * ((coverage - 0.10) / 0.70).coerceIn(0.0, 1.0)).roundToInt()
        val volume = (10 * ScoringEngine.volumeDynamicsFraction(mic)).roundToInt().coerceIn(0, 10)
        val vibrato = (10 * ScoringEngine.vibratoReachFraction(mic.filter { it.voiced && it.f0 > 0f }))
            .roundToInt().coerceIn(0, 10)
        val raw = pitch + timing + cover + volume + vibrato
        val total = (30 + raw * 0.7).roundToInt().coerceIn(30, 100)

        val bd = buildString {
            append("총점 ${total}점 — 🎼 멜로디 대조 채점\n")
            append("· 멜로디 일치 $pitch/40 — 반주 멜로디와 음정 일치 ${(quality * 100).roundToInt()}%\n")
            append("· 박자 타이밍 $timing/25 — 구간 타이밍 편차 ${timingDevMs.roundToInt()}ms\n")
            append("· 완창률 $cover/15 — 멜로디 구간의 ${(coverage * 100).roundToInt()}% 가창\n")
            append("· 음량 표현 $volume/10\n")
            append("· 고음·비브라토 $vibrato/10\n")
            append("(반주에서 추출한 가이드 멜로디와 목소리를 직접 비교. 옥타브 차이는 허용)")
        }
        return ScoringEngine.Score(
            total = total,
            pitchAccuracy = pitch, pitchStability = cover,
            beatConsistency = timing, volumeDynamics = volume, vibratoReach = vibrato,
            voicedPct = (coverage * 100).roundToInt(), medianF0 = 0f, breakdown = bd,
        )
    }

    private fun noSing(msg: String) = ScoringEngine.Score(10, 0, 0, 0, 0, 0, 0, 0f, "총점 10점\n$msg")

    /** shift(프레임): mic[i] 를 ref[i+shift] 와 비교. 반환: (일치 수, 겹침 수) */
    private fun countMatched(
        mic: List<SignalAnalysis.Frame>, ref: List<SignalAnalysis.Frame>, shift: Int,
    ): Pair<Int, Int> {
        var matched = 0; var overlap = 0
        for (i in mic.indices) {
            val j = i + shift
            if (j < 0 || j >= ref.size) continue
            val m = mic[i]; val r = ref[j]
            if (!m.voiced || m.f0 <= 0f || !r.voiced || r.f0 <= 0f) continue
            overlap++
            if (centsDev(m.f0, r.f0) <= MATCH_CENTS) matched++
        }
        return matched to overlap
    }

    private data class MatchStat(val matched: Int, val sang: Int, val devs: List<Double>)

    private fun countMatchedFull(
        mic: List<SignalAnalysis.Frame>, ref: List<SignalAnalysis.Frame>, shift: Int,
    ): MatchStat {
        var matched = 0; var sang = 0
        val devs = ArrayList<Double>()
        for (i in mic.indices) {
            val j = i + shift
            if (j < 0 || j >= ref.size) continue
            val m = mic[i]; val r = ref[j]
            if (!r.voiced || r.f0 <= 0f) continue
            if (!m.voiced || m.f0 <= 0f) continue
            sang++
            val d = centsDev(m.f0, r.f0)
            devs.add(d)
            if (d <= MATCH_CENTS) matched++
        }
        return MatchStat(matched, sang, devs)
    }

    /** 옥타브 무시 음정 편차(cents, 0..600). */
    private fun centsDev(f: Float, refF: Float): Double {
        val d = 1200.0 * ln(f.toDouble() / refF) / ln(2.0)
        val folded = d - 1200.0 * Math.round(d / 1200.0)
        return abs(folded)
    }

    /** 8구간 각각의 최적 시차(프레임) — 리듬 일관성 판정용. 유성 프레임 부족 구간은 제외. */
    private fun segmentLags(
        mic: List<SignalAnalysis.Frame>, ref: List<SignalAnalysis.Frame>, globalLag: Int,
    ): List<Int> {
        val segN = 8
        val segLen = mic.size / segN
        if (segLen < 40) return emptyList()
        val local = (300 / SignalAnalysis.HOP_MS).roundToInt()   // ±300ms
        val out = ArrayList<Int>()
        for (seg in 0 until segN) {
            val slice = mic.subList(seg * segLen, (seg + 1) * segLen)
            var best = 0; var bestM = -1
            for (dl in -local..local) {
                var m = 0
                for (i in slice.indices) {
                    val j = seg * segLen + i + globalLag + dl
                    if (j < 0 || j >= ref.size) continue
                    val mm = slice[i]; val r = ref[j]
                    if (!mm.voiced || mm.f0 <= 0f || !r.voiced || r.f0 <= 0f) continue
                    if (centsDev(mm.f0, r.f0) <= MATCH_CENTS) m++
                }
                if (m > bestM) { bestM = m; best = dl }
            }
            if (bestM >= 10) out.add(best)   // 그 구간에서 실제로 부른 경우만
        }
        return out
    }

    /** 음량(rmsDb) 곡선의 정규화 상관 — 반주 유출 판정 보조. */
    private fun envelopeCorr(
        mic: List<SignalAnalysis.Frame>, ref: List<SignalAnalysis.Frame>, shift: Int,
    ): Double {
        val a = ArrayList<Double>(); val b = ArrayList<Double>()
        for (i in mic.indices) {
            val j = i + shift
            if (j < 0 || j >= ref.size) continue
            a.add(mic[i].rmsDb.toDouble()); b.add(ref[j].rmsDb.toDouble())
        }
        if (a.size < 20) return 0.0
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        if (da <= 0 || db <= 0) return 0.0
        return num / Math.sqrt(da * db)
    }

    /** 200~3500Hz 대역통과(멜로디 대역) — 베이스·킥과 심벌을 걷어 가이드 멜로디를 살린다. */
    private fun bandpass(x: FloatArray, rate: Int): FloatArray {
        val out = x.copyOf()
        biquadHighpass(out, rate, 200.0)
        biquadLowpass(out, rate, 3500.0)
        return out
    }

    // RBJ biquad (in-place)
    private fun biquadHighpass(x: FloatArray, rate: Int, fc: Double) {
        val w = 2.0 * Math.PI * fc / rate
        val q = 0.707
        val alpha = Math.sin(w) / (2 * q)
        val cw = Math.cos(w)
        val b0 = (1 + cw) / 2; val b1 = -(1 + cw); val b2 = (1 + cw) / 2
        val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
        applyBiquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun biquadLowpass(x: FloatArray, rate: Int, fc: Double) {
        val w = 2.0 * Math.PI * fc / rate
        val q = 0.707
        val alpha = Math.sin(w) / (2 * q)
        val cw = Math.cos(w)
        val b0 = (1 - cw) / 2; val b1 = 1 - cw; val b2 = (1 - cw) / 2
        val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
        applyBiquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun applyBiquad(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        return xs.sorted()[xs.size / 2]
    }
}
