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
 * 관용 장치(상용 채점기 벤치마킹):
 *  · 정답은 '안정 구간'만 사용 — 반주 피치 추적의 잡음 프레임(코드·타악기)을 정답에서 제외
 *  · 프레임 시차 ±70ms 창에서 최선의 일치를 인정
 *
 * 배점: 멜로디 일치 40 / 박자 타이밍 25 / 완창률 15 / 음량 표현 10 / 고음·비브라토 10.
 * 치팅 방어: 마이크가 반주 유출(스피커 소리)만 담으면 정답과 '지나치게 완벽'하게 일치
 * (중앙 편차 <12cents & 음량 곡선 상관 >0.9) → 노래 안 부른 것으로 판정.
 */
object MelodyScorer {

    private const val MATCH_CENTS = 70.0        // 이내면 일치(반음=100)
    private const val MATCH_WIN = 3             // 프레임 시차 관용(±3×23ms ≈ ±70ms)
    private const val LAG_SEARCH_MS = 600       // 전역 정렬 탐색 범위(버퍼 시작 오프셋 흡수)
    private const val LAG_STEP_MS = 25

    fun score(voice: FloatArray, voiceRate: Int, accomp: FloatArray, accompRate: Int): ScoringEngine.Score {
        // 속도: 피치 분석 비용은 샘플레이트 제곱에 비례 → ~11kHz 로 다운샘플(멜로디 대역엔 충분)
        // + 목소리/반주 분석을 두 스레드로 병렬.
        val (vDs, vRate) = decimate(voice, voiceRate)
        val (aDs, aRate) = decimate(accomp, accompRate)
        var micResult: List<SignalAnalysis.Frame>? = null
        val voiceThread = kotlin.concurrent.thread { micResult = SignalAnalysis.analyze(vDs, vRate) }
        val ref = SignalAnalysis.analyze(bandpass(aDs, aRate), aRate)
        voiceThread.join()
        val mic = micResult ?: return noSing("채점 분석에 실패했습니다.")

        val micVoiced = mic.count { it.voiced && it.f0 > 0f }
        if (micVoiced < 8) return noSing("발성이 거의 감지되지 않았습니다. 마이크에 대고 불러주세요!")

        // 정답 안정 구간: 이웃 프레임과 80cents 이내로 이어지는 유성 프레임만(멜로디 노트).
        // 코드·타악기·추적 실패로 튀는 프레임을 정답에서 빼 '틀린 정답'과의 비교를 막는다.
        val stable = BooleanArray(ref.size) { j ->
            val r = ref[j]
            if (!r.voiced || r.f0 <= 0f) false
            else {
                fun near(k: Int): Boolean {
                    val o = ref.getOrNull(k) ?: return false
                    return o.voiced && o.f0 > 0f && centsDev(o.f0, r.f0) <= 80.0
                }
                near(j - 1) || near(j + 1)
            }
        }
        val matchable = stable.count { it }
        if (matchable < 20) {
            // 반주에서 멜로디를 못 뽑으면(가이드 멜로디 없는 영상 등) 기존 방식으로 폴백
            return ScoringEngine.score(mic, BeatTracker.detectBeatPeriod(aDs, aRate))
        }

        // ── 전역 정렬: 녹음 버퍼 시작 오프셋·시스템 지연을 흡수하는 최적 시차 탐색 ──
        val lagSteps = LAG_SEARCH_MS / LAG_STEP_MS
        val hopPerStep = (LAG_STEP_MS / SignalAnalysis.HOP_MS).coerceAtLeast(1.0)
        var bestLag = 0
        var bestMatched = -1
        for (s in -lagSteps..lagSteps) {
            val shift = (s * hopPerStep).roundToInt()
            val m = matchStat(mic, ref, stable, shift).matched
            if (m > bestMatched) { bestMatched = m; bestLag = shift }
        }
        val stat = matchStat(mic, ref, stable, bestLag)
        val (matched, sang, devs) = Triple(stat.matched, stat.sang, stat.devs)

        if (sang < 8) return noSing("멜로디 구간에서 목소리가 잡히지 않았어요.")

        // ── 치팅(반주 유출) 판정 ──
        val medDev = median(devs)
        val envCorr = envelopeCorr(mic, ref, bestLag)
        if (matched >= 20 && medDev < 12.0 && envCorr > 0.9) {
            return noSing("노래가 감지되지 않았어요(반주 소리만 녹음됨). 마이크에 대고 불러주세요!")
        }

        // ── 박자 타이밍: 곡을 8구간으로 나눠 구간별 최적 시차의 흔들림(전역 시차는 무죄) ──
        val segLags = segmentLags(mic, ref, stable, bestLag)
        val timingShort = segLags.size < 2
        val timingFraction = if (!timingShort) {
            val med = median(segLags.map { it.toDouble() })
            val devMs = median(segLags.map { abs(it - med) }) * SignalAnalysis.HOP_MS
            (1.0 - devMs / 250.0).coerceIn(0.0, 1.0)
        } else 0.7    // 짧게 불러 데이터가 부족하면 중립 점수(부분 가창 배려하되 공짜 고득점은 아님)

        // ── 점수 ──
        val quality = matched.toDouble() / sang
        val coverage = sang.toDouble() / matchable
        // 이 곡의 '우연 일치율'을 실측: 일부러 5초 어긋난 정렬로 비교한 일치율 = 아무렇게나 불러도
        // 나오는 기준선. 정답(반주 추출)에 잡음이 많은 곡일수록 기준선·상한이 함께 낮아져
        // 정확히 부른 사람이 정답 품질 때문에 손해 보지 않는다.
        val ctrlShift = bestLag + (5000.0 / SignalAnalysis.HOP_MS).roundToInt()
        val ctrl = matchStat(mic, ref, stable, ctrlShift)
        val qRand = if (ctrl.sang >= 20) (ctrl.matched.toDouble() / ctrl.sang).coerceIn(0.05, 0.35) else 0.15
        val ceiling = minOf(0.50, qRand + 0.30)
        val skill = ((quality - qRand) / (ceiling - qRand)).coerceIn(0.0, 1.0)
        val pitch = (40 * skill).roundToInt()
        val timing = (25 * timingFraction).roundToInt()
        val cover = (15 * ((coverage - 0.05) / 0.60).coerceIn(0.0, 1.0)).roundToInt()
        val volume = (10 * ScoringEngine.volumeDynamicsFraction(mic)).roundToInt().coerceIn(0, 10)
        val vibrato = (10 * ScoringEngine.vibratoReachFraction(mic.filter { it.voiced && it.f0 > 0f }))
            .roundToInt().coerceIn(0, 10)
        val raw = pitch + timing + cover + volume + vibrato
        // 주 사용자(아이) 기준 후하게: 웬만큼 부르면 80+, 아주 잘 부르면 100.
        // raw 55≈81, 65≈89, 80+→100. 변별력은 raw 그대로 살아있다(안 부르면 여전히 10점).
        val total = (40 + raw * 0.75).roundToInt().coerceIn(40, 100)

        val timingDevMsShown = ((1.0 - timingFraction) * 250).roundToInt()
        val bd = buildString {
            append("총점 ${total}점 — 🎼 멜로디 대조 채점\n")
            append("· 멜로디 일치 $pitch/40 — 일치 ${(quality * 100).roundToInt()}% (이 곡 우연 기준 ${(qRand * 100).roundToInt()}%)\n")
            if (timingShort) append("· 박자 타이밍 $timing/25 — 가창이 짧아 대략 추정\n")
            else append("· 박자 타이밍 $timing/25 — 구간 타이밍 편차 약 ${timingDevMsShown}ms\n")
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

    private data class MatchStat(val matched: Int, val sang: Int, val devs: List<Double>)

    /**
     * shift(프레임)로 정렬해 비교. 정답은 안정 구간만, 마이크 프레임은 ±MATCH_WIN 창에서
     * 가장 가까운 정답과 비교(미세 시차 관용).
     */
    private fun matchStat(
        mic: List<SignalAnalysis.Frame>,
        ref: List<SignalAnalysis.Frame>,
        stable: BooleanArray,
        shift: Int,
    ): MatchStat {
        var matched = 0; var sang = 0
        val devs = ArrayList<Double>()
        for (i in mic.indices) {
            val j = i + shift
            if (j < 0 || j >= ref.size) continue
            if (!stable[j]) continue
            val m = mic[i]
            if (!m.voiced || m.f0 <= 0f) continue
            sang++
            // 시차 관용은 '같은 노트 안'에서만 — 창 안의 다른 노트와 비교하면
            // 아무 음이나 불러도 우연 일치가 폭증한다(v4.30~4.31 과잉 관용 버그).
            val anchor = ref[j].f0
            var best = centsDev(m.f0, anchor)
            for (w in -MATCH_WIN..MATCH_WIN) {
                val k = j + w
                if (w == 0 || k < 0 || k >= ref.size || !stable[k]) continue
                if (centsDev(ref[k].f0, anchor) > 50.0) continue   // 같은 노트만
                val d = centsDev(m.f0, ref[k].f0)
                if (d < best) best = d
            }
            devs.add(best)
            if (best <= MATCH_CENTS) matched++
        }
        return MatchStat(matched, sang, devs)
    }

    /** 옥타브 무시 음정 편차(cents, 0..600). */
    private fun centsDev(f: Float, refF: Float): Double {
        val d = 1200.0 * ln(f.toDouble() / refF) / ln(2.0)
        val folded = d - 1200.0 * Math.round(d / 1200.0)
        return abs(folded)
    }

    /** 8구간 각각의 최적 시차(프레임) — 리듬 일관성 판정용. 가창이 적은 구간은 제외. */
    private fun segmentLags(
        mic: List<SignalAnalysis.Frame>,
        ref: List<SignalAnalysis.Frame>,
        stable: BooleanArray,
        globalLag: Int,
    ): List<Int> {
        val segN = 8
        val segLen = mic.size / segN
        if (segLen < 40) return emptyList()
        val local = (300 / SignalAnalysis.HOP_MS).roundToInt()   // ±300ms
        val out = ArrayList<Int>()
        for (seg in 0 until segN) {
            val from = seg * segLen
            var best = 0; var bestM = -1
            for (dl in -local..local) {
                var m = 0
                for (i in 0 until segLen) {
                    val j = from + i + globalLag + dl
                    if (j < 0 || j >= ref.size || !stable[j]) continue
                    val mm = mic[from + i]
                    if (!mm.voiced || mm.f0 <= 0f) continue
                    if (centsDev(mm.f0, ref[j].f0) <= MATCH_CENTS) m++
                }
                if (m > bestM) { bestM = m; best = dl }
            }
            if (bestM >= 6) out.add(best)   // 그 구간에서 실제로 부른 경우만
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

    /** 정수배 평균 데시메이션(박스 필터) — 피치 추적용으론 충분한 안티앨리어싱. */
    private fun decimate(x: FloatArray, rate: Int): Pair<FloatArray, Int> {
        val factor = (rate / 11025).coerceAtLeast(1)
        if (factor == 1) return x to rate
        val n = x.size / factor
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            val base = i * factor
            for (k in 0 until factor) s += x[base + k]
            out[i] = s / factor
        }
        return out to rate / factor
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        return xs.sorted()[xs.size / 2]
    }
}
