package com.cseini.byd.karaoke.scoring

/**
 * 반주(모노 PCM float)에서 비트 주기(초)를 추정한다.
 * 에너지 플럭스 온셋 엔벨로프의 자기상관(autocorrelation) 최고점을 비트 주기로 본다.
 * 비트가 뚜렷하지 않으면(신뢰도 낮음) null 을 돌려, 채점은 자기일관성 방식으로 폴백한다.
 */
object BeatTracker {

    fun detectBeatPeriod(samples: FloatArray, rate: Int): Double? {
        if (rate <= 0 || samples.size < rate * 4) return null   // 최소 4초
        val hop = (rate * 0.0116).toInt().coerceAtLeast(1)      // ~11.6ms
        val nFrames = samples.size / hop
        if (nFrames < 80) return null

        // 프레임별 RMS 의 양의 변화량(에너지 플럭스) = 온셋 강도.
        val env = DoubleArray(nFrames)
        var prevRms = 0.0
        for (i in 0 until nFrames) {
            val s = i * hop
            val e = minOf(s + hop, samples.size)
            var acc = 0.0
            for (j in s until e) { val v = samples[j].toDouble(); acc += v * v }
            val rms = Math.sqrt(acc / (e - s).coerceAtLeast(1))
            env[i] = Math.max(0.0, rms - prevRms)
            prevRms = rms
        }
        val mean = env.average()
        if (mean <= 0.0) return null
        for (i in env.indices) env[i] -= mean   // DC 제거

        val fps = rate.toDouble() / hop
        val minLag = (0.30 * fps).toInt().coerceAtLeast(1)          // 200 BPM
        val maxLag = (1.00 * fps).toInt().coerceAtLeast(minLag + 1) // 60 BPM
        if (maxLag >= nFrames) return null

        var bestLag = -1
        var best = 0.0
        var sum = 0.0
        var sumSq = 0.0
        var cnt = 0
        for (lag in minLag..maxLag) {
            var ac = 0.0
            var i = lag
            while (i < nFrames) { ac += env[i] * env[i - lag]; i++ }
            ac /= (nFrames - lag)   // lag 편향 제거(항 수로 정규화) — 느린 BPM도 공정 비교
            sum += ac; sumSq += ac * ac; cnt++
            if (ac > best) { best = ac; bestLag = lag }
        }
        if (bestLag <= 0 || cnt == 0) return null
        // 신뢰도: 최고점이 다른 lag 들보다 통계적으로 확 튀어야(z>=3) 뚜렷한 비트로 인정.
        val acMean = sum / cnt
        val acStd = Math.sqrt(Math.max(0.0, sumSq / cnt - acMean * acMean))
        if (acStd <= 0.0 || (best - acMean) / acStd < 4.5) return null
        return bestLag / fps
    }

    /**
     * 발성 온셋들이 주기 [period] 의 비트 격자에 얼마나 잘 맞는지(0.2~1.0).
     * 격자 위상(offset)을 훑어 최소 평균 오차를 찾는다 → 녹음 지연/절대위상 몰라도 됨.
     * 무작위 온셋의 기대 평균오차 = period/4 → 그걸 기준으로 정규화.
     */
    fun alignmentFraction(onsets: List<Double>, period: Double): Double {
        if (onsets.size < 4 || period <= 0.0) return 0.5
        var best = period
        val steps = 32
        for (s in 0 until steps) {
            val phi = period * s / steps
            var sum = 0.0
            for (t in onsets) {
                var r = ((t - phi) % period + period) % period
                if (r > period / 2) r = period - r
                sum += r
            }
            val avg = sum / onsets.size
            if (avg < best) best = avg
        }
        val norm = best / (period / 4.0)      // 0(정확)~1(무작위)
        return (1.0 - norm).coerceIn(0.2, 1.0)
    }
}
