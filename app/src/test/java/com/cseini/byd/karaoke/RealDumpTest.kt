package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.scoring.MelodyScorer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실차 채점 디버그 덤프(score-debug.zip 내용)를 로컬에서 재생해 채점을 재현.
 * SCORE_DUMP_DIR 환경변수가 없으면 스킵(리포에 덤프를 넣지 않기 위함).
 */
class RealDumpTest {

    private fun readWav(f: File): Pair<FloatArray, Int> {
        val b = f.readBytes()
        fun le16(o: Int) = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8))
        fun le32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or
            ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
        val rate = le32(24)
        val n = (b.size - 44) / 2
        val out = FloatArray(n) { (le16(44 + it * 2).toShort().toInt()) / 32768f }
        return out to rate
    }

    @Test
    fun replayDump() {
        val dir = System.getenv("SCORE_DUMP_DIR")
        assumeTrue(dir != null && File(dir, "voice-11k.wav").exists())
        val (v, vr) = readWav(File(dir, "voice-11k.wav"))
        val (a, ar) = readWav(File(dir, "accomp-11k.wav"))
        val s = MelodyScorer.score(v, vr, a, ar)
        println("REPLAY total=${s.total}")
        println(s.breakdown)
    }
}
