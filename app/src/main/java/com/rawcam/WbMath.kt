package com.rawcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import kotlin.math.abs

/**
 * White balance manual (Kelvin + tint) untuk Camera2: menghitung gain R/G/G/B dan matriks
 * sensor -> sRGB linear dari data kalibrasi sensor (ColorMatrix / ForwardMatrix gaya DNG).
 */
object WbMath {
    class Result(val gains: FloatArray, val matrix: Array<FloatArray>)

    private val XYZ50_TO_SRGB = arrayOf(
        floatArrayOf(3.1338561f, -1.6168667f, -0.4906146f),
        floatArrayOf(-0.9787684f, 1.9161415f, 0.0334540f),
        floatArrayOf(0.0719453f, -0.2289914f, 1.4052427f)
    )

    private fun cct(illum: Int?): Float? = when (illum) {
        1 -> 5500f; 2 -> 4200f; 3 -> 2850f; 4 -> 5500f
        9 -> 6500f; 10 -> 6500f; 11 -> 7500f
        12 -> 6400f; 13 -> 5000f; 14 -> 4200f; 15 -> 3450f
        17 -> 2856f; 18 -> 4874f; 19 -> 6774f; 20 -> 5503f; 21 -> 6504f; 22 -> 7504f; 23 -> 5003f
        24 -> 3200f
        else -> null
    }

    private fun toMat(t: ColorSpaceTransform?): Array<FloatArray>? =
        t?.let { tt -> Array(3) { r -> FloatArray(3) { c -> tt.getElement(c, r).toFloat() } } }

    private fun ident() = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))

    private fun mul(a: Array<FloatArray>, b: Array<FloatArray>) = Array(3) { r ->
        FloatArray(3) { c -> a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] }
    }

    private fun mulV(a: Array<FloatArray>, v: FloatArray) =
        FloatArray(3) { r -> a[r][0] * v[0] + a[r][1] * v[1] + a[r][2] * v[2] }

    private fun mix(a: Array<FloatArray>, b: Array<FloatArray>, w: Float) =
        Array(3) { r -> FloatArray(3) { c -> a[r][c] * w + b[r][c] * (1 - w) } }

    private fun inv(m: Array<FloatArray>): Array<FloatArray>? {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]
        val d = m[1][0]; val e = m[1][1]; val f = m[1][2]
        val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-9f) return null
        val s = 1f / det
        return arrayOf(
            floatArrayOf((e * i - f * h) * s, (c * h - b * i) * s, (b * f - c * e) * s),
            floatArrayOf((f * g - d * i) * s, (a * i - c * g) * s, (c * d - a * f) * s),
            floatArrayOf((d * h - e * g) * s, (b * g - a * h) * s, (a * e - b * d) * s)
        )
    }

    private fun planckXY(t: Float): Pair<Float, Float> {
        val k = t.coerceIn(1700f, 24000f)
        val x = if (k <= 4000f)
            -0.2661239e9f / (k * k * k) - 0.2343589e6f / (k * k) + 0.8776956e3f / k + 0.179910f
        else
            -3.0258469e9f / (k * k * k) + 2.1070379e6f / (k * k) + 0.2226347e3f / k + 0.240390f
        val y = when {
            k <= 2222f -> -1.1063814f * x * x * x - 1.34811020f * x * x + 2.18555832f * x - 0.20219683f
            k <= 4000f -> -0.9549476f * x * x * x - 1.37418593f * x * x + 2.09137015f * x - 0.16748867f
            else -> 3.0817580f * x * x * x - 5.87338670f * x * x + 3.75112997f * x - 0.37001483f
        }
        return x to y
    }

    /** tint: -100 (hijau) .. +100 (magenta). Hasil null bila sensor tidak punya data kalibrasi. */
    fun compute(c: CameraCharacteristics, kelvin: Float, tint: Float): Result? {
        val cm1 = toMat(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: return null
        val cm2 = toMat(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)) ?: cm1
        val fm1 = toMat(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)) ?: inv(cm1) ?: return null
        val fm2 = toMat(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)) ?: inv(cm2) ?: fm1
        val cc1 = toMat(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)) ?: ident()
        val cc2 = toMat(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)) ?: cc1
        val i1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt()
        val i2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        val t1 = cct(i1) ?: 2856f
        val t2 = cct(i2) ?: t1
        val w = if (abs(t1 - t2) < 1f) 1f
        else ((1f / kelvin - 1f / t2) / (1f / t1 - 1f / t2)).coerceIn(0f, 1f)

        val cm = mul(mix(cc1, cc2, w), mix(cm1, cm2, w))
        val fm = mix(fm1, fm2, w)

        // titik putih dari Kelvin + tint (offset v pada diagram CIE 1960)
        val (x0, y0) = planckXY(kelvin)
        val den = -2f * x0 + 12f * y0 + 3f
        val u = 4f * x0 / den
        val v = 6f * y0 / den - (tint / 100f) * 0.02f
        val d2 = 2f * u - 8f * v + 4f
        val x = 3f * u / d2
        val y = 2f * v / d2
        if (y <= 0.01f) return null
        val xyz = floatArrayOf(x / y, 1f, (1f - x - y) / y)

        val n = mulV(cm, xyz)
        if (n[1] <= 1e-6f) return null
        val nr = n[0] / n[1]
        val nb = n[2] / n[1]
        if (!(nr > 0.05f) || !(nb > 0.05f)) return null
        val gains = floatArrayOf(1f / nr, 1f, 1f, 1f / nb)
        return Result(gains, mul(XYZ50_TO_SRGB, fm))
    }
}
