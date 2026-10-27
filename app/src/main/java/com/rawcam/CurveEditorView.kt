package com.rawcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Editor kurva: seret titik, ketuk area kosong untuk menambah titik, ketuk 2x titik untuk menghapus.
 * Sumbu X = cahaya sensor (linear) ditampilkan dalam skala gamma 2.2 agar area gelap lebih lebar.
 */
class CurveEditorView(ctx: Context) : View(ctx) {
    var profile: ColorProfile = ColorProfile.presets()[0]
        set(v) { field = v; invalidate() }
    var onChanged: (() -> Unit)? = null

    private val g = 2.2f
    private val dens = resources.displayMetrics.density
    private val pad = 28f * dens
    private var drag = -1
    private var moved = false
    private var lastTapIdx = -1
    private var lastTapTime = 0L

    private val pBg = Paint().apply { color = 0xFF111111.toInt() }
    private val pGrid = Paint().apply { color = 0x44FFFFFF; strokeWidth = 1f }
    private val pCurve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 255, 160); strokeWidth = 3f * dens; style = Paint.Style.STROKE
    }
    private val pBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; strokeWidth = 2f * dens; style = Paint.Style.STROKE
    }
    private val pPt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = 11f * dens }

    private fun sx(x: Float) = pad + x.coerceIn(0f, 1f).pow(1f / g) * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y.coerceIn(0f, 1f) * (height - 2 * pad)

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pBg)
        for (i in 0..4) {
            val u = i / 4f
            val gx = pad + u * (width - 2 * pad)
            val gy = pad + u * (height - 2 * pad)
            c.drawLine(gx, pad, gx, height - pad, pGrid)
            c.drawLine(pad, gy, width - pad, gy, pGrid)
        }
        fun build(f: (Float) -> Float): Path {
            val path = Path()
            val steps = 120
            for (i in 0..steps) {
                val x = (i / steps.toFloat()).pow(g)
                val px = sx(x)
                val py = sy(f(x))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            return path
        }
        if (profile.hasGrade()) c.drawPath(build { profile.evalBase(it) }, pBase)
        c.drawPath(build { profile.eval(it) }, pCurve)
        profile.points.forEach { c.drawCircle(sx(it.x), sy(it.y), 10f * dens, pPt) }
        c.drawText("input sensor (linear, skala gamma) →", pad, height - 6f * dens, pTxt)
        c.drawText("output", 4f * dens, pad - 8f * dens, pTxt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width - 2 * pad
        val h = height - 2 * pad
        fun toX(px: Float) = ((px - pad) / w).coerceIn(0f, 1f).pow(g)
        fun toY(py: Float) = (1f - (py - pad) / h).coerceIn(0f, 1f)
        val pts = profile.points
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = -1
                var bd = 48f * dens
                pts.forEachIndexed { i, p ->
                    val d = hypot(sx(p.x) - e.x, sy(p.y) - e.y)
                    if (d < bd) { bd = d; best = i }
                }
                if (best < 0 && pts.size < 24) {
                    val nx = toX(e.x)
                    var idx = pts.indexOfFirst { it.x > nx }
                    if (idx < 0) idx = pts.size
                    if (idx in 1 until pts.size) {
                        val lo = pts[idx - 1].x + 0.004f
                        val hi = pts[idx].x - 0.004f
                        if (lo <= hi) { pts.add(idx, Pt(nx.coerceIn(lo, hi), toY(e.y))); best = idx }
                    }
                }
                drag = best
                moved = false
                if (best >= 0) profile.rgb = null   // mengedit = pakai kurva master
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                moved = true
                val last = pts.size - 1
                val nx = when (drag) {
                    0 -> 0f
                    last -> 1f
                    else -> toX(e.x).coerceIn(pts[drag - 1].x + 0.004f, pts[drag + 1].x - 0.004f)
                }
                pts[drag] = Pt(nx, toY(e.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag >= 0) {
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (lastTapIdx == drag && now - lastTapTime < 350 && drag != 0 && drag != pts.size - 1) {
                            pts.removeAt(drag); lastTapIdx = -1
                        } else { lastTapIdx = drag; lastTapTime = now }
                    }
                    invalidate()
                    onChanged?.invoke()
                }
                drag = -1
            }
        }
        return true
    }
}
