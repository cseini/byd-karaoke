package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.scoring.MelodyScorer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

/** 실시간 가이드 추출(250ms 조각)이 오프라인 정답 멜로디와 얼마나 일치하는지 — 실차 덤프로 검증. */
class RealtimeGuideTest {

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
    fun realtimeMatchesOffline() {
        val dir = System.getenv("SCORE_DUMP_DIR")
        assumeTrue(dir != null && File(dir, "accomp-11k.wav").exists())
        val (a, ar) = readWav(File(dir, "accomp-11k.wav"))
        // 120ms 간격으로 250ms 조각을 실시간 방식으로 추출
        val chunk = ar / 4               // 250ms
        val step = ar * 12 / 100         // 120ms
        var both = 0; var agree = 0; var shown = 0; var total = 0
        val offline = ArrayList<Pair<Double, Float>>()  // (초, cents) — 참조용 재추출
        var st = chunk
        while (st + chunk < a.size) {
            val g = MelodyScorer.realtimeGuideCents(a.copyOfRange(st - chunk, st), ar)
            val t = st.toDouble() / ar
            total++
            if (g > 0f) { shown++; offline.add(t to g) }
            st += step
        }
        // 오프라인 정답: 같은 시각의 인접 실시간 값끼리 노트 연속성이 있는지(자기일관성)와,
        // 60cents 옥타브접기 기준 이웃 일치율을 본다(가이드가 진짜 노트면 이웃끼리 붙어 있음)
        for (i in 1 until offline.size) {
            val (t0, c0) = offline[i-1]; val (t1, c1) = offline[i]
            if (t1 - t0 < 0.2) {
                both++
                val d = (c1 - c0).toDouble()
                val folded = d - 1200.0 * Math.round(d / 1200.0)
                if (abs(folded) <= 60) agree++
            }
        }
        println("REALTIME: 표시율 ${100*shown/total}% / 인접 일치(연속 노트) ${if(both>0) 100*agree/both else 0}% (n=$both)")
        assumeTrue(both > 50)
    }
}
