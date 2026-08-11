package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.scoring.MelodyScorer
import com.cseini.byd.karaoke.scoring.MelodyTracker
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 화면(노트 레인) 로직을 그대로 시뮬레이션 — 실차 덤프에서 '화면에 초록이 얼마나 뜨는가'. */
class LaneSimTest {

    private fun readWav(f: File): Pair<FloatArray, Int> {
        val b = f.readBytes()
        fun le32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or
            ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
        val rate = le32(24)
        val n = (b.size - 44) / 2
        val out = FloatArray(n) {
            (((b[44 + it*2].toInt() and 0xFF) or (b[45 + it*2].toInt() shl 8)).toShort().toInt()) / 32768f
        }
        return out to rate
    }

    @Test
    fun laneMatchOnDumps() {
        val dirs = System.getenv("SCORE_DUMP_DIRS")?.split(":") ?: return
        for (dir in dirs) {
            val vf = File(dir, "voice-11k.wav"); val af = File(dir, "accomp-11k.wav")
            if (!vf.exists()) continue
            val (v, vr) = readWav(vf)
            val (a, ar) = readWav(af)
            val tracker = MelodyTracker(ar)
            val tickA = ar * 12 / 100      // 120ms
            val tickV = vr * 12 / 100
            val winV = vr * 12 / 100       // latestVoice(120)
            var ai = 0
            val gs = ArrayList<Float>(); val ms = ArrayList<Float>()
            val winA = ar * 19 / 100   // latestAccomp(190)
            val winV2 = vr * 19 / 100
            var t = 0
            while ((t + 1) * tickA < a.size && (t + 1) * tickV + winV < v.size) {
                val end = (t + 1) * tickA
                tracker.feed(a.copyOfRange(ai, end)); ai = end
                gs.add(tracker.currentCents())
                val vs = (t + 1) * tickV
                val vFrom = (vs - winV2).coerceAtLeast(0)
                val aTo = ((t + 1) * tickA).coerceAtMost(a.size)
                val aFrom = (aTo - winA).coerceAtLeast(0)
                val (vRaw, _) = MelodyScorer.realtimeVoiceSub(
                    v.copyOfRange(vFrom, vs), vr, a.copyOfRange(aFrom, aTo), ar)
                ms.add(vRaw)
                t++
            }
            val name = File(dir).name
            fun foldOk(a1: Float, b1: Float, tol: Float): Boolean {
                if (a1 <= 0f || b1 <= 0f) return false
                var d = a1 - b1; d -= 1200f * Math.round(d / 1200f)
                return Math.abs(d) <= tol
            }
            for (delay in intArrayOf(0, 1, 2, 3)) for (tol in intArrayOf(60, 100)) {
                var both = 0; var hit = 0
                for (i in gs.indices) {
                    val gi = i - delay
                    if (gi < 0) continue
                    val mv = ms[i]
                    // ±1틱 최선 짝(프레임 경계 관용)
                    var g0 = gs[gi]
                    if (mv > 0f) {
                        var ok = foldOk(g0, mv, tol.toFloat())
                        if (!ok && gi > 0 && foldOk(gs[gi-1], mv, tol.toFloat())) { ok = true; g0 = gs[gi-1] }
                        if (!ok && gi < gs.size-1 && foldOk(gs[gi+1], mv, tol.toFloat())) { ok = true }
                        if (g0 > 0f) { both++; if (ok) hit++ }
                    }
                }
                println("LANE[$name] delay=$delay tol=$tol → 초록 ${if(both>0)100*hit/both else 0}% (겹침 $both)")
            }
        }
        assumeTrue(true)
    }
}
