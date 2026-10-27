package com.rawcam

import android.hardware.camera2.params.TonemapCurve
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Profil warna: base curve log + soft shoulder (DR tinggi), lalu opsi custom (contrast, tint, kurva user). */
class ColorProfile {
    var custom = false
    var contrast = 1f
    var saturation = 1f
    var tint = 0f
    var sharpness = 0f
    val pts = mutableListOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))

    fun resetCurve() { pts.clear(); pts.add(floatArrayOf(0f, 0f)); pts.add(floatArrayOf(1f, 1f)) }

    /** Kurva user: monotone cubic (PCHIP), tidak overshoot. */
    fun user(x: Float): Float {
        val p = pts; val n = p.size
        if (n < 2) return x
        if (x <= p[0][0]) return p[0][1]
        if (x >= p[n - 1][0]) return p[n - 1][1]
        val h = FloatArray(n - 1) { max(p[it + 1][0] - p[it][0], 1e-4f) }
        val d = FloatArray(n - 1) { (p[it + 1][1] - p[it][1]) / h[it] }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f } else {
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val s = a * a + b * b
                if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
            }
        }
        var i = 0
        while (i < n - 2 && x > p[i + 1][0]) i++
        val t = (x - p[i][0]) / h[i]; val t2 = t * t; val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * p[i][1] + (t3 - 2 * t2 + t) * h[i] * m[i] +
                (-2 * t3 + 3 * t2) * p[i + 1][1] + (t3 - t2) * h[i] * m[i + 1]
    }

    /** Linear sensor -> log (angkat shadow) -> shoulder lembut (highlight tidak clip). */
    private fun base(x: Float): Float {
        var y = ln(1f + 24f * x) / ln(25f)
        if (y > 0.82f) y = 0.82f + 0.17f * tanh((y - 0.82f) / 0.17f)
        return y
    }

    fun out(x: Float): Float {
        var y = base(x)
        if (custom) { y = (0.5f + (y - 0.5f) * contrast).coerceIn(0f, 1f); y = user(y) }
        return y.coerceIn(0f, 1f)
    }

    fun tonemap(): TonemapCurve {
        val n = 128
        val r = FloatArray(n * 2); val g = FloatArray(n * 2); val b = FloatArray(n * 2)
        val t = if (custom) tint else 0f
        for (i in 0 until n) {
            val x = i / (n - 1f); val y = out(x)
            r[2 * i] = x; g[2 * i] = x; b[2 * i] = x
            r[2 * i + 1] = (y * (1f + 0.06f * t)).coerceIn(0f, 1f)
            g[2 * i + 1] = (y * (1f - 0.12f * t)).coerceIn(0f, 1f)
            b[2 * i + 1] = (y * (1f + 0.06f * t)).coerceIn(0f, 1f)
        }
        return TonemapCurve(r, g, b)
    }

    fun save(): String = JSONObject().apply {
        put("contrast", contrast.toDouble()); put("saturation", saturation.toDouble())
        put("tint", tint.toDouble()); put("sharpness", sharpness.toDouble())
        put("curve", JSONArray().apply { pts.forEach { put(JSONArray().put(it[0].toDouble()).put(it[1].toDouble())) } })
    }.toString(2)

    /** Format JSON: {"contrast":1,"saturation":1,"tint":0,"sharpness":0,"curve":[[0,0],[0.5,0.55],[1,1]]} */
    fun load(json: String) {
        val o = JSONObject(json)
        contrast = o.optDouble("contrast", 1.0).toFloat()
        saturation = o.optDouble("saturation", 1.0).toFloat()
        tint = o.optDouble("tint", 0.0).toFloat()
        sharpness = o.optDouble("sharpness", 0.0).toFloat()
        o.optJSONArray("curve")?.let { a ->
            val l = (0 until a.length()).map { floatArrayOf(a.getJSONArray(it).getDouble(0).toFloat(), a.getJSONArray(it).getDouble(1).toFloat()) }
                .sortedBy { it[0] }
            if (l.size >= 2) { pts.clear(); pts.addAll(l) }
        }
        custom = true
    }
}
