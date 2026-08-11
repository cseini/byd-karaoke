package com.cseini.byd.karaoke.scoring

import com.cseini.byd.karaoke.audio.SignalAnalysis
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 실시간(스트리밍) 가이드 멜로디 추적 — 채점(MelodyScorer.salienceMelody)과 같은
 * 배음합산 + 비터비 전방 누적을 곡 진행에 따라 이어간다.
 * 250ms 조각 독립 분석(v4.45~4.49)은 채점용 추출과 결과가 달라 화면 노트가 어긋났다.
 */
class MelodyTracker(srcRate: Int) {

    private val factor = Math.ceil(srcRate / 12000.0).toInt().coerceAtLeast(1)
    private val rate = srcRate / factor
    private val n = 1024
    private val nFft = 2048          // 제로패딩 — 저음역 반음 분해능 확보
    private val hop = (rate * SignalAnalysis.HOP_MS / 1000).toInt().coerceAtLeast(1)

    private val fMin = 180.0
    private val fMax = 1000.0
    private val nCand = (12 * ln(fMax / fMin) / ln(2.0) * 2).toInt()
    private val cand = DoubleArray(nCand) { fMin * Math.pow(2.0, it / 24.0) }
    private val weights = doubleArrayOf(1.0, 0.8, 0.6, 0.45, 0.35, 0.25)
    private val binOf = Array(6) { h ->
        IntArray(nCand) { c -> (cand[c] * (h + 1) * nFft / rate).roundToInt().coerceIn(0, nFft / 2) }
    }
    private val win = DoubleArray(n) { 0.5 - 0.5 * Math.cos(2 * Math.PI * it / (n - 1)) }
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)
    private val mag = DoubleArray(nFft / 2 + 1)
    private val sal = DoubleArray(nCand)

    // 비터비 전방 누적(직전 프레임 dp 만 유지, 매 프레임 최대값으로 재정규화)
    private val dp = DoubleArray(nCand)
    private val dpNext = DoubleArray(nCand)
    private var started = false
    private val jumpPen = 0.275
    private val span = 8
    private val threshRatio = 3.0   // 표시용 신뢰 문턱(압축 스케일 기준, 채점 2.2 보다 엄격)

    // 데시메이션 캐리 + 프레임 조립 버퍼
    private var carrySum = 0f
    private var carryCnt = 0
    private var work = FloatArray(0)

    @Volatile private var lastCents = 0f

    /** 원 샘플레이트(srcRate)의 새 반주 조각을 공급. 완성된 프레임만큼 추적을 전진시킨다. */
    fun feed(raw: FloatArray) {
        // 정수배 평균 데시메이션(캐리 유지)
        val dec = FloatArray((raw.size + carryCnt) / factor + 1)
        var m = 0
        for (v in raw) {
            carrySum += v; carryCnt++
            if (carryCnt == factor) { dec[m++] = carrySum / factor; carrySum = 0f; carryCnt = 0 }
        }
        if (m == 0) return
        val merged = FloatArray(work.size + m)
        System.arraycopy(work, 0, merged, 0, work.size)
        System.arraycopy(dec, 0, merged, work.size, m)
        work = merged
        var off = 0
        while (work.size - off >= n) {
            process(work, off)
            off += hop
        }
        if (off > 0) work = work.copyOfRange(off, work.size)
    }

    fun currentCents(): Float = lastCents

    private fun process(x: FloatArray, off: Int) {
        for (i in 0 until n) { re[i] = x[off + i] * win[i]; im[i] = 0.0 }
        for (i in n until nFft) { re[i] = 0.0; im[i] = 0.0 }
        MelodyScorer.fft(re, im)
        // 0.6제곱 압축 — 큰 베이스·타악기가 salience 를 독식하는 것 억제(Melodia 방식)
        for (k in 0..nFft / 2) mag[k] = Math.pow(Math.hypot(re[k], im[k]), 0.6)
        var bestS = 0.0
        for (c in 0 until nCand) {
            var v = 0.0
            for (h in 0 until 6) v += weights[h] * mag[binOf[h][c]]
            sal[c] = v
            if (v > bestS) bestS = v
        }
        val med = MelodyScorer.medianOf(sal)
        val confident = bestS > threshRatio * med
        if (bestS > 0) for (c in 0 until nCand) sal[c] /= bestS
        if (!started) {
            for (c in 0 until nCand) dp[c] = Math.log(sal[c] + 1e-6)
            started = true
        } else {
            for (c in 0 until nCand) {
                var best = -1e18
                val lo = (c - span).coerceAtLeast(0)
                val hi = (c + span).coerceAtMost(nCand - 1)
                for (p in lo..hi) {
                    val v = dp[p] - Math.abs(c - p) * jumpPen
                    if (v > best) best = v
                }
                dpNext[c] = best + Math.log(sal[c] + 1e-6)
            }
            var mx = dpNext[0]
            for (c in 1 until nCand) if (dpNext[c] > mx) mx = dpNext[c]
            for (c in 0 until nCand) dp[c] = dpNext[c] - mx   // 재정규화(무한 누적 방지)
        }
        var bc = 0
        for (c in 1 until nCand) if (dp[c] > dp[bc]) bc = c
        lastCents = if (confident) (1200.0 * ln(cand[bc] / 55.0) / ln(2.0)).toFloat() else 0f
    }
}
