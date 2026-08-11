package com.cseini.byd.karaoke.scoring

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 유출 차감 기반 내 음정(스테이트풀) — 노트 레인용.
 *
 * 스피커→실내→마이크 경로는 주파수마다 감쇠가 달라 단일 α 차감(v4.54)으로는 유출 잔차가
 * 남아 '안 부르는데 노트가 찍히는' 오탐이 생긴다. 에코 서프레서의 최소값 추적처럼
 * 빈별 유출 전달비 L[k] 를 학습(안 부르는 순간의 |V|/|A| 로 수렴, 천천히 상승·빠르게 하강)
 * 하고, 잔차 |V|−margin·L·|A| 가 충분할 때만 하모닉합으로 음정을 뽑는다.
 */
class VoicePitchTracker {

    private val n = 1024
    private val nFft = 2048
    private var vr = 0
    // 유출은 스피커 출력 지연만큼 늦게 마이크에 온다 → 지연 후보별로 빈별 전달비를 학습하고
    // 잔차가 최소인 후보로 차감(자동 정렬). 후보: 0/60/120/180ms.
    private val delaysMs = intArrayOf(0, 60, 120, 180)
    private var leaks: Array<DoubleArray> = emptyArray()
    private var warm = 0

    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)

    private fun magOf(x: FloatArray): DoubleArray {
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1))
            re[i] = x[i] * w; im[i] = 0.0
        }
        for (i in n until nFft) { re[i] = 0.0; im[i] = 0.0 }
        MelodyScorer.fft(re, im)
        return DoubleArray(nFft / 2 + 1) { Math.hypot(re[it], im[it]) }
    }

    /** (cents, singingness). 부르지 않으면 cents=0. */
    fun process(voice: FloatArray, voiceRate: Int, accomp: FloatArray, accompRate: Int): Pair<Float, Float> {
        val (vdAll, r) = MelodyScorer.decimatePublic(voice, voiceRate)
        if (vdAll.size < n) return 0f to 0f
        if (vr != r) {
            vr = r
            leaks = Array(delaysMs.size) { DoubleArray(nFft / 2 + 1) { 4.0 } }
            warm = 0
        }
        val vd = vdAll.copyOfRange(vdAll.size - n, vdAll.size)
        // 반주는 최대 지연만큼 여유 있게 리샘플해 후보별 구간을 잘라 쓴다
        val maxDelay = vr * delaysMs.last() / 1000
        val adFull = MelodyScorer.resampleTailPublic(accomp, accompRate, vr, n + maxDelay) ?: return 0f to 0f
        val mV = magOf(vd)

        val kLo = (150.0 * nFft / vr).toInt().coerceAtLeast(1)
        val kHi = (3500.0 * nFft / vr).toInt().coerceAtMost(nFft / 2)
        var eV = 0.0
        for (k in kLo..kHi) eV += mV[k] * mV[k]

        // 후보 지연마다: 전달비 학습 + 잔차 계산 → 잔차 최소 후보 채택
        var bestE = Double.MAX_VALUE
        var bestRes: DoubleArray? = null
        for (di in delaysMs.indices) {
            val d = vr * delaysMs[di] / 1000
            val from = adFull.size - n - d
            if (from < 0) continue
            val mA = magOf(adFull.copyOfRange(from, from + n))
            val leak = leaks[di]
            var eR = 0.0
            val res = DoubleArray(nFft / 2 + 1)
            for (k in kLo..kHi) {
                val a2 = mA[k]
                if (a2 > 1e-7) {
                    val ratio = mV[k] / a2
                    leak[k] = if (ratio < leak[k]) ratio else (leak[k] * 1.02).coerceAtMost(16.0)
                }
                val rr = mV[k] - 1.6 * leak[k] * mA[k]
                res[k] = if (rr > 0) rr else 0.0
                eR += res[k] * res[k]
            }
            if (eR < bestE) { bestE = eR; bestRes = res }
        }
        if (warm < 8) { warm++; return 0f to 0f }   // 학습 워밍업(~1초)
        val res = bestRes ?: return 0f to 0f
        val sing = if (eV > 1e-12) (bestE / eV).toFloat() else 0f
        if (sing < 0.25f) return 0f to sing

        // 잔차 하모닉합 음정(140~800Hz, 1/4반음)
        val fMin = 140.0; val fMax = 800.0
        val nCand = (12 * ln(fMax / fMin) / ln(2.0) * 4).toInt()
        val ws = doubleArrayOf(1.0, 0.7, 0.5, 0.35, 0.25)
        val sal = DoubleArray(nCand)
        var best = -1; var bestS = 0.0
        for (c in 0 until nCand) {
            val f = fMin * Math.pow(2.0, c / 48.0)
            var v2 = 0.0
            for (h in 1..5) {
                val k = (f * h * nFft / vr).roundToInt()
                if (k in 1..nFft / 2) v2 += ws[h - 1] * res[k]
            }
            sal[c] = v2
            if (v2 > bestS) { bestS = v2; best = c }
        }
        val med = MelodyScorer.medianOf(sal)
        if (best < 0 || bestS < 3.0 * med) return 0f to sing
        val f0 = fMin * Math.pow(2.0, best / 48.0)
        return (1200.0 * ln(f0 / 55.0) / ln(2.0)).toFloat() to sing
    }
}
