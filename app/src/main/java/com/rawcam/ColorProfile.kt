package com.rawcam

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class Pt(val x: Float, val y: Float)

/**
 * Profil warna = kurva dasar (input: cahaya LINEAR sensor 0..1 -> output 0..1) + grading + saturasi.
 * Grading (-1..1) bekerja pada output kurva dasar: kontras, black, shadow, mid, highlight, white.
 */
class ColorProfile(
    var name: String,
    var saturation: Float = 1f,
    var points: MutableList<Pt> = mutableListOf(Pt(0f, 0f), Pt(1f, 1f)),
    var rgb: List<List<Pt>>? = null
) {
    var contrast = 0f
    var black = 0f
    var shadow = 0f
    var mid = 0f
    var highlight = 0f
    var white = 0f
    var sharpness = 1            // 0 = mati, 1 = standar (FAST), 2 = tinggi (HIGH_QUALITY)
    var evBias = 0f              // bias eksposur otomatis khusus profil ini (EV)
    var note: String? = null     // info sesaat (tidak disimpan)

    fun copy(): ColorProfile {
        val c = ColorProfile(name, saturation, points.toMutableList(), rgb)
        c.contrast = contrast; c.black = black; c.shadow = shadow
        c.mid = mid; c.highlight = highlight; c.white = white
        c.sharpness = sharpness; c.evBias = evBias
        return c
    }

    fun hasGrade() = contrast != 0f || black != 0f || shadow != 0f || mid != 0f || highlight != 0f || white != 0f

    fun resetGrade() { contrast = 0f; black = 0f; shadow = 0f; mid = 0f; highlight = 0f; white = 0f }

    private fun gauss(y: Float, c: Float, s: Float): Float { val d = (y - c) / s; return exp(-d * d) }

    /** Grading di domain output (display-referred). */
    fun grade(y0: Float): Float {
        var y = y0.coerceIn(0f, 1f)
        if (contrast != 0f) { val s = y * y * (3f - 2f * y); y += contrast * (s - y) }
        val d = black * (1f - y).pow(6) + shadow * gauss(y, 0.25f, 0.18f) + mid * gauss(y, 0.5f, 0.2f) +
                highlight * gauss(y, 0.75f, 0.18f) + white * y.pow(6)
        return (y + 0.15f * d).coerceIn(0f, 1f)
    }

    fun evalBase(x: Float): Float = pchip(points.sortedBy { it.x }, x).coerceIn(0f, 1f)
    fun eval(x: Float): Float = grade(evalBase(x))

    /** Sampel kurva sebagai pasangan (x,y) berurutan; titik lebih rapat di area gelap. */
    fun curves(n: Int, gammaIn: Boolean = false): Triple<FloatArray, FloatArray, FloatArray> {
        val c = rgb
        return if (c != null && c.size == 3) Triple(sample(c[0], n, gammaIn), sample(c[1], n, gammaIn), sample(c[2], n, gammaIn))
        else { val m = sample(points, n, gammaIn); Triple(m, m, m) }
    }

    /** gammaIn: HP yang menganggap input kurva sudah ter-gamma -> evaluasi kurva di u^2.2. */
    private fun sample(p: List<Pt>, n: Int, gammaIn: Boolean): FloatArray {
        val s = p.sortedBy { it.x }
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = if (i == n - 1) 1f else { val t = i / (n - 1f); t * t }
            out[2 * i] = x
            val xin = if (gammaIn) x.pow(2.2f) else x
            out[2 * i + 1] = grade(pchip(s, xin).coerceIn(0f, 1f))
        }
        for (i in 1 until n) out[2 * i + 1] = max(out[2 * i + 1], out[2 * (i - 1) + 1])   // jaga monoton
        return out
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("format", "rawcam-profile-v1")
        o.put("name", name)
        o.put("saturation", saturation.toDouble())
        o.put("points", arr(points))
        rgb?.let { o.put("r", arr(it[0])); o.put("g", arr(it[1])); o.put("b", arr(it[2])) }
        o.put("sharpness", sharpness)
        o.put("evBias", evBias.toDouble())
        o.put("grade", JSONObject().apply {
            put("contrast", contrast.toDouble()); put("black", black.toDouble()); put("shadow", shadow.toDouble())
            put("mid", mid.toDouble()); put("highlight", highlight.toDouble()); put("white", white.toDouble())
        })
        return o.toString(2)
    }

    companion object {
        private fun arr(p: List<Pt>) = JSONArray().also { a ->
            p.forEach { a.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
        }

        private fun parr(a: JSONArray): MutableList<Pt> = MutableList(a.length()) { i ->
            val e = a.getJSONArray(i)
            Pt(e.getDouble(0).toFloat(), e.getDouble(1).toFloat())
        }

        fun fromJson(text: String): ColorProfile? = try {
            val o = JSONObject(text)
            val pts = parr(o.getJSONArray("points")).sortedBy { it.x }.toMutableList()
            if (pts.size < 2) null else {
                val rgb = if (o.has("r") && o.has("g") && o.has("b"))
                    listOf(parr(o.getJSONArray("r")), parr(o.getJSONArray("g")), parr(o.getJSONArray("b")))
                else null
                val p = ColorProfile(o.optString("name", "Impor"), o.optDouble("saturation", 1.0).toFloat(), pts, rgb)
                p.sharpness = o.optInt("sharpness", 1).coerceIn(0, 2)
                p.evBias = o.optDouble("evBias", 0.0).toFloat()
                o.optJSONObject("grade")?.let { g ->
                    p.contrast = g.optDouble("contrast", 0.0).toFloat()
                    p.black = g.optDouble("black", 0.0).toFloat()
                    p.shadow = g.optDouble("shadow", 0.0).toFloat()
                    p.mid = g.optDouble("mid", 0.0).toFloat()
                    p.highlight = g.optDouble("highlight", 0.0).toFloat()
                    p.white = g.optDouble("white", 0.0).toFloat()
                }
                p
            }
        } catch (e: Exception) { null }

        /**
         * LUT .cube. 1D dipakai penuh (per-kanal). LUT 3D hanya bisa diaproksimasi lewat sumbu abu-abu
         * (R=G=B) karena pipeline kamera hanya punya kurva 1D per kanal.
         * input: 0 = LUT dibuat untuk data linear, 1 = untuk S-Log3, 2 = untuk Rec.709/gamma.
         */
        fun fromCube(name: String, text: String, input: Int = 0): ColorProfile? {
            var size1 = 0
            var size3 = 0
            val rows = ArrayList<FloatArray>()
            var idx3 = 0
            for (line in text.lineSequence()) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val parts = l.split(Regex("\\s+"))
                when {
                    l.startsWith("LUT_3D_SIZE") -> size3 = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    l.startsWith("LUT_1D_SIZE") -> size1 = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    else -> if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            if (size3 > 0) {
                                val stride = 1 + size3 + size3 * size3
                                if (idx3 % stride == 0) rows.add(floatArrayOf(r, g, b))
                                idx3++
                            } else rows.add(floatArrayOf(r, g, b))
                        }
                    }
                }
            }
            val is3d = size3 > 0
            val size = if (is3d) size3 else size1
            if (size < 2 || rows.size < size) return null
            fun lut(ch: Int, u: Float): Float {
                val pos = u.coerceIn(0f, 1f) * (size - 1)
                val i = pos.toInt().coerceAtMost(size - 2)
                val f = pos - i
                return rows[i][ch] * (1 - f) + rows[i + 1][ch] * f
            }
            fun enc(x: Float): Float = when (input) {
                1 -> slog3(x).coerceIn(0f, 1f)
                2 -> rec709(x).coerceIn(0f, 1f)
                else -> x
            }
            val n = 96
            val xs = FloatArray(n) { i -> if (i == n - 1) 1f else { val t = i / (n - 1f); t * t } }
            val chans = List(3) { ch -> xs.map { Pt(it, lut(ch, enc(it)).coerceIn(0f, 1f)) } }
            val master = List(12) { k ->
                val t = k / 11f
                val x = if (k == 11) 1f else t * t
                Pt(x, lut(1, enc(x)).coerceIn(0f, 1f))
            }.toMutableList()
            val p = ColorProfile(name, 1f, master, chans)
            p.note = if (is3d) "LUT 3D: hanya kurva sumbu abu-abu yang dipakai (aproksimasi, warna silang tidak ikut)"
            else "LUT 1D dimuat (per-kanal)"
            return p
        }

        // ---------- preset ----------
        private val XS = floatArrayOf(0f, .002f, .006f, .01125f, .02f, .04f, .07f, .12f, .18f, .3f, .5f, .75f, 1f)

        private fun fromFn(name: String, f: (Float) -> Float) =
            ColorProfile(name, 1f, XS.map { Pt(it, f(it).coerceIn(0f, 1f)) }.toMutableList())

        fun rec709(x: Float) = if (x < 0.018f) 4.5f * x else 1.099f * x.pow(0.45f) - 0.099f
        fun srgb(x: Float) = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
        fun slog3(x: Float): Float =
            if (x >= 0.01125f) (420f + log10((x + 0.01f) / 0.19f) * 261.5f) / 1023f
            else (x * (171.2102946929f - 95f) / 0.01125f + 95f) / 1023f
        fun softLog(x: Float) = ln(1f + 255f * x) / ln(256f)

        /**
         * Profil "Plain HDR": gain +1 stop, knee halus di highlight (tidak blown, roll-off lembut),
         * bayangan terangkat (area gelap tetap terlihat). Dipakai bersama bias AE -1 EV.
         */
        fun plainHdrFn(x: Float): Float {
            val u = 2f * x
            val v = u / sqrt(1f + u * u)
            val l = ln(1f + 40f * v) / ln(41f)
            return (0.65f * rec709(v) + 0.35f * l).coerceIn(0f, 1f)
        }

        private val XS_DENSE = floatArrayOf(0f, .001f, .003f, .006f, .012f, .02f, .035f, .06f, .1f, .16f, .25f, .4f, .6f, .8f, 1f)

        fun plainHdr(): ColorProfile {
            val p = ColorProfile("Plain HDR (default)", 1f,
                XS_DENSE.map { Pt(it, plainHdrFn(it)) }.toMutableList())
            p.evBias = -1f
            return p
        }

        fun presets(): List<ColorProfile> = listOf(
            plainHdr(),
            ColorProfile("Linear (tes kalibrasi)"),
            fromFn("Rec.709") { rec709(it) },
            fromFn("sRGB") { srgb(it) },
            fromFn("S-Log3") { slog3(it) },
            fromFn("Log lembut (highlight → putih)") { softLog(it) }
        )

        // ---------- interpolasi monoton (PCHIP) ----------
        private fun sgn(v: Float) = if (v > 0f) 1 else if (v < 0f) -1 else 0

        private fun endSlope(h0: Float, h1: Float, d0: Float, d1: Float): Float {
            var m = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
            if (sgn(m) != sgn(d0)) m = 0f
            else if (sgn(d0) != sgn(d1) && abs(m) > 3 * abs(d0)) m = 3 * d0
            return m
        }

        fun pchip(p: List<Pt>, x: Float): Float {
            val n = p.size
            if (n == 0) return x
            if (n == 1) return p[0].y
            if (x <= p[0].x) return p[0].y
            if (x >= p[n - 1].x) return p[n - 1].y
            val h = FloatArray(n - 1) { (p[it + 1].x - p[it].x).coerceAtLeast(1e-6f) }
            val d = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / h[it] }
            val m = FloatArray(n)
            if (n == 2) { m[0] = d[0]; m[1] = d[0] }
            else {
                for (i in 1 until n - 1) {
                    m[i] = if (d[i - 1] * d[i] <= 0f) 0f else {
                        val w1 = 2 * h[i] + h[i - 1]
                        val w2 = h[i] + 2 * h[i - 1]
                        (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                    }
                }
                m[0] = endSlope(h[0], h[1], d[0], d[1])
                m[n - 1] = endSlope(h[n - 2], h[n - 3], d[n - 2], d[n - 3])
            }
            var k = 0
            while (k < n - 2 && x > p[k + 1].x) k++
            val t = (x - p[k].x) / h[k]
            val t2 = t * t
            val t3 = t2 * t
            return (2 * t3 - 3 * t2 + 1) * p[k].y + (t3 - 2 * t2 + t) * h[k] * m[k] +
                    (-2 * t3 + 3 * t2) * p[k + 1].y + (t3 - t2) * h[k] * m[k + 1]
        }
    }
}
