package com.cseini.byd.karaoke

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 실시간 음정 노트 레인(TV 노래방식).
 * 가이드 멜로디(금색)와 내 음정(청록, 일치하면 초록)을 최근 6초 창으로 흘려 보여준다.
 * 세로축 = 옥타브 접은 음높이(채점과 동일하게 옥타브 무시), 오른쪽 끝이 '지금'.
 */
class PitchLaneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class P(val t: Long, val refCents: Float, val micCents: Float, val match: Boolean)

    private val pts = ArrayDeque<P>()
    private val windowMs = 6000L

    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6C15A"); strokeWidth = 9f; strokeCap = Paint.Cap.ROUND
    }
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DD7E6"); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5CE65C"); strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint().apply { color = Color.parseColor("#66050510") }
    private val linePaint = Paint().apply { color = Color.parseColor("#22FFFFFF"); strokeWidth = 1f }

    /** cents 는 절대값(예: 1200*log2(f/55)). 0 이면 없음. */
    fun push(refCents: Float, micCents: Float, match: Boolean) {
        val now = System.currentTimeMillis()
        pts.addLast(P(now, refCents, micCents, match))
        while (pts.isNotEmpty() && now - pts.first().t > windowMs) pts.removeFirst()
        invalidate()
    }

    fun clear() { pts.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        canvas.drawRoundRect(0f, 0f, w, h, 14f, 14f, bgPaint)
        // 옥타브 격자(4분할)
        for (k in 1..3) canvas.drawLine(0f, h * k / 4, w, h * k / 4, linePaint)
        val now = System.currentTimeMillis()
        val pad = h * 0.08f
        fun y(cents: Float): Float {
            val fold = ((cents % 1200f) + 1200f) % 1200f
            return h - pad - (fold / 1200f) * (h - 2 * pad)
        }
        fun x(t: Long): Float = w * (1f - (now - t).toFloat() / windowMs)
        for (p in pts) {
            val px = x(p.t)
            if (px < 0) continue
            if (p.refCents > 0f) canvas.drawPoint(px, y(p.refCents), refPaint)
            if (p.micCents > 0f) canvas.drawPoint(px, y(p.micCents), if (p.match) hitPaint else micPaint)
        }
    }
}
