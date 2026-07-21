package com.cseini.byd.karaoke.audio

/**
 * PCM 샘플열 → 프레임별 특징(F0/유성/음량). 순수 Kotlin(테스트 가능).
 * 채점 엔진과 진단 도구가 공통으로 사용.
 */
object SignalAnalysis {

    const val FRAME_MS = 46.0
    const val HOP_MS = 23.0

    data class Frame(
        val timeSec: Double,
        val f0: Float,
        val voiced: Boolean,
        val rmsDb: Float,
    )

    /** samples: [-1,1] mono. */
    fun analyze(samples: FloatArray, sampleRate: Int): List<Frame> {
        val frameLen = (sampleRate * FRAME_MS / 1000).toInt().coerceAtLeast(64)
        val hop = (sampleRate * HOP_MS / 1000).toInt().coerceAtLeast(1)
        val detector = PitchDetector(sampleRate)
        val out = ArrayList<Frame>()
        var start = 0
        while (start + frameLen <= samples.size) {
            val frame = samples.copyOfRange(start, start + frameLen)
            val rms = PitchDetector.rmsDb(frame)
            val r = if (rms < -45f) PitchDetector.Result(0f, 0f, false) else detector.detect(frame)
            out.add(Frame(start.toDouble() / sampleRate, r.f0, r.voiced && rms >= -45f, rms))
            start += hop
        }
        return out
    }
}
