package com.cseini.byd.karaoke.scoring

import com.cseini.byd.karaoke.audio.SignalAnalysis
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 멜로디 대조 채점 v2 — 노트(음표) 단위 비교.
 *
 * 반주에서 가이드 멜로디의 노트열을, 마이크에서 부른 노트열을 뽑아 DTW(동적 시간 정렬)로
 * 맞춰본다. 프레임(23ms) 단위 비교(v4.29~4.33)는 미세 오차·정답 잡음에 흔들렸는데,
 * 노트 단위는 사람이 멜로디를 인식하는 단위라 안정적이다. 옥타브는 무시(남/여/아이 키 허용).
 *
 * 변별력 장치:
 *  · 이 곡의 '우연 일치율'을 실측(10초 어긋난 대조) → 그보다 잘한 만큼만 점수
 *  · DTW 는 ±2초 밴드 안에서만 짝지음(아무 데나 갖다 붙이기 방지)
 *  · 반주 유출(안 부르고 반주만) → 지나치게 완벽한 일치 + 음량곡선 상관으로 적발 → 10점
 *
 * 배점: 멜로디 일치 40 / 박자 타이밍 25 / 완창률 15 / 음량 표현 10 / 고음·비브라토 10.
 */
object MelodyScorer {

    private const val NOTE_MATCH_CENTS = 100.0   // 노트 단위 일치 허용(반음)
    private const val BAND_SEC = 0.8             // DTW 시간 밴드(넓으면 아무 음이나 체리피킹됨)
    private const val LAG_SEARCH_MS = 3000       // 전역 오프셋(버퍼 시작 차이) 탐색
    private const val LAG_STEP_MS = 50

    private data class Note(val t: Double, val dur: Double, val cents: Double)

    fun score(voice: FloatArray, voiceRate: Int, accomp: FloatArray, accompRate: Int): ScoringEngine.Score {
        // 속도: 11kHz 다운샘플 + 두 스레드 병렬 분석
        val (vDs, vRate) = decimate(voice, voiceRate)
        val (aDs, aRate) = decimate(accomp, accompRate)
        var micResult: List<SignalAnalysis.Frame>? = null
        val voiceThread = kotlin.concurrent.thread { micResult = SignalAnalysis.analyze(vDs, vRate) }
        val ref = SignalAnalysis.analyze(bandpass(aDs, aRate), aRate)
        voiceThread.join()
        val mic = micResult ?: return noSing("채점 분석에 실패했습니다.")

        if (mic.count { it.voiced && it.f0 > 0f } < 8) {
            return noSing("발성이 거의 감지되지 않았습니다. 마이크에 대고 불러주세요!")
        }

        // 정답 안정 구간(멜로디 노트 후보): 이웃과 이어지는 유성 프레임만
        val stable = BooleanArray(ref.size) { j ->
            val r = ref[j]
            if (!r.voiced || r.f0 <= 0f) false
            else {
                fun near(k: Int): Boolean {
                    val o = ref.getOrNull(k) ?: return false
                    return o.voiced && o.f0 > 0f && abs(rawCents(o.f0) - rawCents(r.f0)) <= 90.0
                }
                near(j - 1) || near(j + 1)
            }
        }

        // 노트열 추출(목소리는 중앙값 스무딩으로 옥타브 튐·흔들림 완화)
        val refNotes = extractNotes(ref) { j -> stable[j] }
        val micSm = medianSmooth(mic)
        val micNotes = extractNotes(micSm) { j -> micSm[j].voiced && micSm[j].f0 > 0f }
        if (refNotes.size < 10) {
            // 가이드 멜로디를 못 뽑는 영상 → 기존 자체 채점 폴백
            return ScoringEngine.score(mic, BeatTracker.detectBeatPeriod(aDs, aRate))
        }
        if (micNotes.size < 4) return noSing("멜로디 구간에서 목소리가 잡히지 않았어요.")

        // 전역 시간 오프셋(녹음 버퍼 시작 차이) — 프레임 유성 겹침이 최대가 되는 시차
        val offset = globalOffsetSec(mic, ref, stable)

        // DTW 노트 매칭. 우연 기준은 '음정만 섞은' 대조 — 같은 타이밍·음역에서 순서만 무작위.
        val real = dtwMatch(micNotes, refNotes, offset)
        val shuffled = micNotes.map { it.cents }.shuffled(kotlin.random.Random(1234))
        val ctrlNotes = micNotes.mapIndexed { i, n -> n.copy(cents = shuffled[i]) }
        val ctrl = dtwMatch(ctrlNotes, refNotes, offset)

        // 일치 '개수'가 아니라 정확도 가중(딱 맞으면 1, 100cents 경계면 0) — 우연 일치는 반토막 난다
        fun weighted(r: DtwResult) = r.matchedPairs.sumOf { 1.0 - it.dev / NOTE_MATCH_CENTS } / micNotes.size
        val q = weighted(real)
        val qRand = weighted(ctrl).coerceIn(0.02, 0.5)
        val qCount = real.matchedPairs.size.toDouble() / micNotes.size

        // 반주 유출(치팅) 적발: 노트가 거의 전부·초정밀로 맞고 음량 곡선까지 반주와 겹침
        val devMed = median(real.matchedPairs.map { it.dev })
        val envCorr = envelopeCorr(mic, ref, (offset / (SignalAnalysis.HOP_MS / 1000.0)).roundToInt())
        if (qCount > 0.6 && devMed < 15.0 && envCorr > 0.9) {
            return noSing("노래가 감지되지 않았어요(반주 소리만 녹음됨). 마이크에 대고 불러주세요!")
        }

        // ── 멜로디 40: 우연 기준선 대비 실력 환산 ──
        val ceiling = minOf(0.85, qRand + 0.45)
        val skill = ((q - qRand) / (ceiling - qRand)).coerceIn(0.0, 1.0)
        val pitch = (40 * skill).roundToInt()

        // ── 박자 25: 짝지어진 노트들의 시작 시각 편차의 흔들림(전역 지연은 무죄) ──
        val offs = real.matchedPairs.map { it.micT - it.refT }
        val timingShort = offs.size < 5
        val timingFraction = if (!timingShort) {
            val med = median(offs)
            val jitterMs = median(offs.map { abs(it - med) }) * 1000.0
            (1.0 - jitterMs / 350.0).coerceIn(0.0, 1.0)
        } else 0.7
        val timing = (25 * timingFraction).roundToInt()

        // ── 완창률 15: 정답 노트 중 짝지어진 비율 ──
        val coverage = real.matchedRefCount.toDouble() / refNotes.size
        val cover = (15 * ((coverage - 0.05) / 0.55).coerceIn(0.0, 1.0)).roundToInt()

        // 크게 자신있게 부르면 점수 — 성량(60%) + 강약 표현(40%)
        val voicedDb = mic.filter { it.voiced }.map { it.rmsDb.toDouble() }
        val loudness = if (voicedDb.isEmpty()) 0.0
        else ((median(voicedDb) + 35.0) / 23.0).coerceIn(0.0, 1.0)   // -35dB→0, -12dB→1
        val volume = (10 * (0.6 * loudness + 0.4 * ScoringEngine.volumeDynamicsFraction(mic)))
            .roundToInt().coerceIn(0, 10)
        val vibrato = (10 * ScoringEngine.vibratoReachFraction(mic.filter { it.voiced && it.f0 > 0f }))
            .roundToInt().coerceIn(0, 10)

        val raw = pitch + timing + cover + volume + vibrato
        // 주 사용자(아이) 기준 후하게: 웬만큼 부르면 80+, 아주 잘 부르면 100. 안 부르면 10.
        val total = (40 + raw * 0.75).roundToInt().coerceIn(40, 100)

        val bd = buildString {
            append("총점 ${total}점 — 🎼 멜로디 대조 채점\n")
            append("· 멜로디 일치 $pitch/40 — 부른 노트 ${micNotes.size}개 중 ${real.matchedPairs.size}개 일치")
            append(" (우연 기준 ${(qRand * 100).roundToInt()}%)\n")
            if (timingShort) append("· 박자 타이밍 $timing/25 — 가창이 짧아 대략 추정\n")
            else {
                val med = median(offs)
                val jitterMs = (median(offs.map { abs(it - med) }) * 1000).roundToInt()
                append("· 박자 타이밍 $timing/25 — 노트 타이밍 흔들림 약 ${jitterMs}ms\n")
            }
            append("· 완창률 $cover/15 — 멜로디 노트의 ${(coverage * 100).roundToInt()}% 소화\n")
            append("· 음량 표현 $volume/10\n")
            append("· 고음·비브라토 $vibrato/10\n")
            append("(반주 가이드 멜로디와 노트 단위 비교 · 옥타브 차이 허용)")
        }
        return ScoringEngine.Score(
            total = total,
            pitchAccuracy = pitch, pitchStability = cover,
            beatConsistency = timing, volumeDynamics = volume, vibratoReach = vibrato,
            voicedPct = (coverage * 100).roundToInt(), medianF0 = 0f, breakdown = bd,
        )
    }

    private fun noSing(msg: String) = ScoringEngine.Score(10, 0, 0, 0, 0, 0, 0, 0f, "총점 10점\n$msg")

    /** 유성 프레임 f0 에 3점 중앙값 필터 — 옥타브 튐·순간 흔들림 제거(아이 목소리 대응). */
    private fun medianSmooth(frames: List<SignalAnalysis.Frame>): List<SignalAnalysis.Frame> =
        frames.mapIndexed { i, fr ->
            if (!fr.voiced || fr.f0 <= 0f) fr
            else {
                val c = ArrayList<Float>(3)
                for (k in i - 1..i + 1) {
                    val o = frames.getOrNull(k) ?: continue
                    if (o.voiced && o.f0 > 0f) c.add(o.f0)
                }
                if (c.size < 2) fr else fr.copy(f0 = c.sorted()[c.size / 2])
            }
        }

    // ── 노트 추출: 연속(120cents 이내)으로 이어지는 유성 구간(≥3프레임 ≈ 70ms)을 한 노트로 ──
    private fun extractNotes(frames: List<SignalAnalysis.Frame>, ok: (Int) -> Boolean): List<Note> {
        val hop = SignalAnalysis.HOP_MS / 1000.0
        val notes = ArrayList<Note>()
        var startIdx = -1
        val cur = ArrayList<Double>()
        fun flush(endIdx: Int) {
            if (startIdx >= 0 && cur.size >= 3) {
                notes.add(Note(frames[startIdx].timeSec, (endIdx - startIdx) * hop, median(cur)))
            }
            startIdx = -1; cur.clear()
        }
        for (i in frames.indices) {
            if (ok(i)) {
                val c = rawCents(frames[i].f0)
                if (startIdx < 0) { startIdx = i; cur.add(c) }
                else if (abs(c - median(cur)) <= 120.0) cur.add(c)
                else { flush(i); startIdx = i; cur.add(c) }
            } else flush(i)
        }
        flush(frames.size)
        return notes
    }

    private data class Pairing(val micT: Double, val refT: Double, val dev: Double)
    private data class DtwResult(val matchedPairs: List<Pairing>, val matchedRefCount: Int)

    /**
     * 시간 밴드(±2s, offset 보정) 안에서 노트열 DTW 정렬 후, 옥타브 무시 100cents 이내로
     * 짝지어진 노트들을 돌려준다. 삽입/삭제 비용으로 정답의 잡음 노트는 건너뛸 수 있다.
     */
    private fun dtwMatch(micN: List<Note>, refN: List<Note>, offset: Double): DtwResult {
        val n = micN.size; val m = refN.size
        val inf = 1e18
        val gap = 1.1
        val dp = Array(n + 1) { DoubleArray(m + 1) { inf } }
        val from = Array(n + 1) { IntArray(m + 1) }   // 1=대각(짝) 2=위(mic 스킵) 3=왼쪽(ref 스킵)
        dp[0][0] = 0.0
        for (i in 0..n) for (j in 0..m) {
            if (dp[i][j] >= inf) continue
            val base = dp[i][j]
            if (i < n && j < m) {
                val within = abs(micN[i].t + offset - refN[j].t) <= BAND_SEC
                if (within) {
                    val dev = foldedCents(micN[i].cents - refN[j].cents)
                    val cost = (dev / 100.0).coerceAtMost(3.0)
                    if (base + cost < dp[i + 1][j + 1]) { dp[i + 1][j + 1] = base + cost; from[i + 1][j + 1] = 1 }
                }
            }
            if (i < n && base + gap < dp[i + 1][j]) { dp[i + 1][j] = base + gap; from[i + 1][j] = 2 }
            if (j < m && base + gap < dp[i][j + 1]) { dp[i][j + 1] = base + gap; from[i][j + 1] = 3 }
        }
        // 역추적
        val pairs = ArrayList<Pairing>()
        val refHit = HashSet<Int>()
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when (from[i][j]) {
                1 -> {
                    val dev = foldedCents(micN[i - 1].cents - refN[j - 1].cents)
                    if (dev <= NOTE_MATCH_CENTS) {
                        pairs.add(Pairing(micN[i - 1].t, refN[j - 1].t - offset, dev))
                        refHit.add(j - 1)
                    }
                    i--; j--
                }
                2 -> i--
                3 -> j--
                else -> { i = 0; j = 0 }
            }
        }
        return DtwResult(pairs, refHit.size)
    }

    /** 프레임 유성 겹침 최대의 전역 시차(초) — 녹음 버퍼 시작 차이 흡수. */
    private fun globalOffsetSec(
        mic: List<SignalAnalysis.Frame>, ref: List<SignalAnalysis.Frame>, stable: BooleanArray,
    ): Double {
        val hopSec = SignalAnalysis.HOP_MS / 1000.0
        val steps = LAG_SEARCH_MS / LAG_STEP_MS
        val hopPerStep = (LAG_STEP_MS / SignalAnalysis.HOP_MS).coerceAtLeast(1.0)
        var best = 0; var bestOverlap = -1
        for (s in -steps..steps) {
            val shift = (s * hopPerStep).roundToInt()
            var overlap = 0
            for (i in mic.indices) {
                val j = i + shift
                if (j < 0 || j >= ref.size) continue
                if (mic[i].voiced && mic[i].f0 > 0f && stable[j]) overlap++
            }
            if (overlap > bestOverlap) { bestOverlap = overlap; best = shift }
        }
        return best * hopSec
    }

    /** A1(55Hz) 기준 절대 cents. */
    private fun rawCents(f0: Float): Double = 1200.0 * ln(f0.toDouble() / 55.0) / ln(2.0)

    /** 옥타브 무시 편차(0..600 cents). */
    private fun foldedCents(diff: Double): Double {
        val folded = diff - 1200.0 * Math.round(diff / 1200.0)
        return abs(folded)
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

    /** 200~3500Hz 대역통과(멜로디 대역). */
    private fun bandpass(x: FloatArray, rate: Int): FloatArray {
        val out = x.copyOf()
        biquadHighpass(out, rate, 200.0)
        biquadLowpass(out, rate, 3500.0)
        return out
    }

    private fun biquadHighpass(x: FloatArray, rate: Int, fc: Double) {
        val w = 2.0 * Math.PI * fc / rate
        val alpha = Math.sin(w) / (2 * 0.707)
        val cw = Math.cos(w)
        val b0 = (1 + cw) / 2; val b1 = -(1 + cw); val b2 = (1 + cw) / 2
        val a0 = 1 + alpha; val a1 = -2 * cw; val a2 = 1 - alpha
        applyBiquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun biquadLowpass(x: FloatArray, rate: Int, fc: Double) {
        val w = 2.0 * Math.PI * fc / rate
        val alpha = Math.sin(w) / (2 * 0.707)
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

    /** 정수배 평균 데시메이션(박스 필터). */
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
