package com.cseini.byd.karaoke.audio

import kotlin.math.sqrt

/**
 * YIN 기본주파수(F0) 검출기. 순수 Kotlin(android 의존 없음) → JVM 단위테스트 가능.
 * 참고: de Cheveigné & Kawahara (2002), "YIN, a fundamental frequency estimator".
 *
 * TarsosDSP 대신 자체 구현한 이유: 비-Maven-Central·GPL 의존 제거 + 즉시 테스트 가능.
 * 필요 시 be.tarsos.dsp:core 로 교체 가능(settings.gradle 에 repo 유지).
 */
class PitchDetector(
    private val sampleRate: Int,
    private val threshold: Float = 0.15f,
    private val fMin: Float = 80f,
    private val fMax: Float = 1000f,
) {
    /** 검출 결과. voiced=false 면 f0 는 무의미. */
    data class Result(val f0: Float, val probability: Float, val voiced: Boolean)

    private val tauMin = (sampleRate / fMax).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / fMin).toInt()

    /** 한 프레임(권장 길이 >= 2*tauMax) 에서 F0 추정. */
    fun detect(frame: FloatArray): Result {
        val w = frame.size
        val maxTau = minOf(tauMax, w / 2)
        if (maxTau <= tauMin) return Result(0f, 0f, false)

        // 1) 차분 함수 d(tau)
        val diff = FloatArray(maxTau + 1)
        for (tau in 1..maxTau) {
            var sum = 0f
            var i = 0
            while (i < w - tau) {
                val delta = frame[i] - frame[i + tau]
                sum += delta * delta
                i++
            }
            diff[tau] = sum
        }

        // 2) 누적평균 정규화 차분 d'(tau)
        val cmnd = FloatArray(maxTau + 1)
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..maxTau) {
            running += diff[tau]
            cmnd[tau] = if (running > 0f) diff[tau] * tau / running else 1f
        }

        // 3) 절대 임계 — 임계 밑으로 처음 내려가는 지점의 국소 최소
        var tauEstimate = -1
        var tau = tauMin
        while (tau <= maxTau) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= maxTau && cmnd[tau + 1] < cmnd[tau]) tau++
                tauEstimate = tau
                break
            }
            tau++
        }
        // 임계 밑이 없으면 최소값 지점 사용(약한 신뢰)
        if (tauEstimate == -1) {
            var best = tauMin
            for (t in tauMin..maxTau) if (cmnd[t] < cmnd[best]) best = t
            tauEstimate = best
        }

        // 4) 포물선 보간
        val betterTau = parabolicInterp(cmnd, tauEstimate, maxTau)
        val f0 = if (betterTau > 0) sampleRate / betterTau else 0f
        val clarity = (1f - cmnd[tauEstimate]).coerceIn(0f, 1f)
        val voiced = f0 in fMin..fMax && clarity >= (1f - threshold) * 0.6f
        return Result(f0, clarity, voiced)
    }

    private fun parabolicInterp(cmnd: FloatArray, tau: Int, maxTau: Int): Float {
        if (tau <= 0 || tau >= maxTau) return tau.toFloat()
        val a = cmnd[tau - 1]
        val b = cmnd[tau]
        val c = cmnd[tau + 1]
        val denom = a + c - 2f * b
        return if (denom != 0f) tau + 0.5f * (a - c) / denom else tau.toFloat()
    }

    companion object {
        fun rmsDb(frame: FloatArray): Float {
            if (frame.isEmpty()) return -120f
            var sum = 0.0
            for (s in frame) sum += (s * s).toDouble()
            val r = sqrt(sum / frame.size)
            return (20.0 * Math.log10(r + 1e-9)).toFloat()
        }
    }
}
