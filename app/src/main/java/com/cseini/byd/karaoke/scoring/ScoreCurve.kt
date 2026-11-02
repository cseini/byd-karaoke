package com.cseini.byd.karaoke.scoring

import kotlin.math.roundToInt

/**
 * 채점 기준(후한 정도) — 채점기가 낸 원점수(raw 0..100)를 표시 점수로 바꾸는 마지막 변환만 단계별로 다르다.
 * 노래방은 재미로 하는 것이라 아이들이 낮은 점수에 속상하지 않게 고를 수 있게 했다.
 *
 *  0 원래대로    35 + raw×기울기        (MelodyScorer 0.7, ScoringEngine 0.72 — 지금까지와 같다)
 *  1 후하게      60 + raw×0.45          (최저 60점, 웬만큼 부르면(raw 80) 96점, raw 88 이상이면 100점)
 *  2 아주 후하게 80 + raw×0.35          (못 불러도 80점 이상, raw 56 이상 — 평범하게 불러도 100점)
 * (ScoringEngine 의 0.72 는 v4.24 에서 0.55→0.72 로 넓힌 값 — 전형적 원점수 58 이 ≈77점이라 기존 점수대와 이어진다.)
 * 실차 보정(MelodyScorer): 엉망 ≈ raw 37 → 원래 61점·후하게 77점·아주 후하게 93점, 웬만큼 ≈ raw 80 → 91점·96점·100점.
 *
 * 아무 소리도 안 잡혀 0점이 나오는 경우는 마이크 점검 안내라서 이 변환을 거치지 않는다(채점기가 0점을 바로 돌려준다).
 */
object ScoreCurve {
    const val LEVELS = 3

    fun total(raw: Int, level: Int, baseSlope: Double): Int {
        val r = raw.coerceIn(0, 100)
        val v = when (level) {
            1 -> 60.0 + r * 0.45
            2 -> 80.0 + r * 0.35
            else -> 35.0 + r * baseSlope
        }
        return v.roundToInt().coerceIn(35, 100)
    }

    fun label(level: Int): String = when (level) {
        1 -> "후하게"
        2 -> "아주 후하게(아이용)"
        else -> "원래대로"
    }
}
