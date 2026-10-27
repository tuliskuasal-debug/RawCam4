package com.rawcam

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Editor kurva besar. Tap kosong = tambah titik, drag = geser, tahan lama pada titik = hapus. */
class CurveView(c: Context, private val p: ColorProfile, private val onChange: () -> Unit) : View(c) {
    private val pad = 40f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); strokeWidth = 5f; style = Paint.Style.STROKE }
    private val grid = Paint().apply { color = 0x33FFFFFF; strokeWidth = 2f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var sel = -1; private var moved = false; private var dx = 0f; private var dy = 0f
    private val lp = Runnable {
        if (sel in 1 until p.pts.size - 1 && !moved) { p.pts.removeAt(sel); sel = -1; invalidate(); onChange() }
    }
    private fun sx(x: Float) = pad + x * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y * (height - 2 * pad)
    private fun ux(px: Float) = ((px - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
    private fun uy(py: Float) = ((height - pad - py) / (height - 2 * pad)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF111111.toInt())
        for (i in 0..4) { val t = i / 4f; c.drawLine(sx(t), sy(0f), sx(t), sy(1f), grid); c.drawLine(sx(0f), sy(t), sx(1f), sy(t), grid) }
        c.drawLine(sx(0f), sy(0f), sx(1f), sy(1f), grid)
        val path = Path()
        for (i in 0..100) { val x = i / 100f; if (i == 0) path.moveTo(sx(x), sy(p.user(x))) else path.lineTo(sx(x), sy(p.user(x))) }
        c.drawPath(path, line)
        p.pts.forEachIndexed { i, q -> c.drawCircle(sx(q[0]), sy(q[1]), if (i == sel) 20f else 14f, dot) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                moved = false; dx = e.x; dy = e.y
                fun dist(i: Int) = hypot(sx(p.pts[i][0]) - e.x, sy(p.pts[i][1]) - e.y)
                val near = p.pts.indices.minByOrNull { dist(it) }
                sel = if (near != null && dist(near) < 50f) near else -1
                if (sel < 0) {
                    val q = floatArrayOf(ux(e.x), uy(e.y)); p.pts.add(q); p.pts.sortBy { it[0] }; sel = p.pts.indexOf(q); onChange()
                } else postDelayed(lp, 700)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - dx, e.y - dy) > 15f) { moved = true; removeCallbacks(lp) }
                if (sel >= 0 && moved) {
                    val q = p.pts[sel]; q[1] = uy(e.y)
                    if (sel in 1 until p.pts.size - 1) q[0] = ux(e.x).coerceIn(p.pts[sel - 1][0] + 0.01f, p.pts[sel + 1][0] - 0.01f)
                    onChange()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(lp); sel = -1 }
        }
        invalidate(); return true
    }
}
