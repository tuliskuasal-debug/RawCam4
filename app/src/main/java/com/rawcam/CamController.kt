package com.rawcam

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** TextureView yang menjaga rasio preview (tidak stretch). Portrait: rasio = sisi pendek / sisi panjang sensor. */
class AutoFitTextureView(c: Context) : TextureView(c) {
    private var ar = 0f
    fun setAspect(w: Int, h: Int) { ar = min(w, h).toFloat() / max(w, h); requestLayout() }
    override fun onMeasure(ws: Int, hs: Int) {
        val mw = MeasureSpec.getSize(ws); val mh = MeasureSpec.getSize(hs)
        if (ar == 0f) setMeasuredDimension(mw, mh)
        else if (mw > mh * ar) setMeasuredDimension((mh * ar).toInt(), mh)
        else setMeasuredDimension(mw, (mw / ar).toInt())
    }
}

class CamController(private val act: Activity, private val tv: AutoFitTextureView) {
    // ---- setting yang diubah UI ----
    var video = false; var soo = false; var openGate = false; var ois = true
    var photoOut = "JPG"                       // JPG | RAW | RAW+JPG
    var auto = true; var ev = 0f; var iso = 100; var shutter = 1.0 / 125; var focus = 0f; var aperture = 0f; var kelvin = 5500
    var size = Size(4000, 3000); var fpsValue = 30
    val profile = ColorProfile()
    var recording = false
    var onRecState: (Boolean) -> Unit = {}

    private val mgr = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camId = mgr.cameraIdList.first { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
    val ch: CameraCharacteristics = mgr.getCameraCharacteristics(camId)
    private val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    private val sensorOrient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private val supportsRaw = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    private val thread = HandlerThread("cam").apply { start() }
    private val h = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rec: MediaRecorder? = null
    private var recSurface: Surface? = null
    private var recUri: Uri? = null
    private var recPfd: android.os.ParcelFileDescriptor? = null
    private var lastCct: ColorSpaceTransform? = null
    private var lastGains: RggbChannelVector? = null
    private var rawImg: Image? = null
    private var rawRes: TotalCaptureResult? = null
    private var shotName = ""
    private val wantRaw get() = !video && supportsRaw && photoOut != "JPG"
    private val wantJpg get() = !video && (photoOut != "RAW" || !supportsRaw)

    init {
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, hh: Int) { start() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, hh: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    fun toast(s: String) = act.runOnUiThread { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }

    // ---- opsi yang tersedia ----
    /** Open gate = sensor penuh 4:3; tutup = 16:9 (crop). */
    fun sizes(): List<Size> {
        val all = (if (video) map.getOutputSizes(MediaRecorder::class.java) else map.getOutputSizes(ImageFormat.JPEG)).toList()
        val t = if (openGate) 4f / 3 else 16f / 9
        val f = all.filter { abs(it.width.toFloat() / it.height - t) < 0.02f }
        return (if (f.isEmpty()) all else f).sortedByDescending { it.width * it.height }.take(10)
    }
    fun fpsOptions(): List<Int> {
        val u = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)!!.map { it.upper }.distinct().sorted()
        return u.filter { it >= 24 }.ifEmpty { u }
    }
    fun fpsRange(): Range<Int> {
        val r = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)!!
        return r.filter { it.upper == fpsValue }.maxByOrNull { it.lower } ?: r.maxByOrNull { it.upper }!!
    }
    fun isoRange(): Range<Int> = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
    fun minFocus(): Float = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    fun apertures(): List<Float> = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
    fun hasOis() = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1) == true

    private fun previewSize(): Size {
        val a = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }
        val ar = size.width.toFloat() / size.height
        return a.filter { abs(it.width.toFloat() / it.height - ar) < 0.02f }.maxByOrNull { it.width * it.height }
            ?: a.minByOrNull { abs(it.width.toFloat() / it.height - ar) } ?: size
    }

    // ---- lifecycle ----
    @SuppressLint("MissingPermission")
    fun start() = h.post {
        if (act.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || !tv.isAvailable) return@post
        if (device != null) { createSession(); return@post }
        mgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; createSession() }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, e: Int) { d.close(); device = null; toast("Camera error $e") }
        }, h)
    }
    fun restart() = h.post { createSession() }
    fun stop() = h.post {
        if (recording) stopRecInternal(false)
        closeSession(); device?.close(); device = null
    }

    private fun closeSession() {
        session?.close(); session = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
        if (!recording) cleanupRecorder()
    }
    private fun cleanupRecorder() {
        try { rec?.release() } catch (_: Exception) {}
        rec = null; recSurface?.release(); recSurface = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { act.contentResolver.delete(it, null, null) }; recUri = null
    }

    private fun createSession() {
        val d = device ?: return
        if (recording) return
        closeSession()
        val st = tv.surfaceTexture ?: return
        val ps = previewSize()
        act.runOnUiThread { tv.setAspect(ps.width, ps.height) }
        st.setDefaultBufferSize(ps.width, ps.height)
        val pSurf = Surface(st); previewSurface = pSurf
        val targets = mutableListOf(pSurf)
        try {
            if (video) {
                prepareRecorder(); targets.add(recSurface!!)
            } else {
                if (wantJpg) {
                    jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3).also {
                        it.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { img -> saveJpeg(img) } }, h)
                        targets.add(it.surface)
                    }
                }
                if (wantRaw) {
                    val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width * it.height }!!
                    rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3).also {
                        it.setOnImageAvailableListener({ r -> synchronized(this) { rawImg?.close(); rawImg = r.acquireNextImage() }; tryDng() }, h)
                        targets.add(it.surface)
                    }
                } else if (photoOut != "JPG") toast("Perangkat tidak mendukung RAW, memakai JPG")
            }
            @Suppress("DEPRECATION")
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; updateRepeating() }
                override fun onConfigureFailed(s: CameraCaptureSession) { toast("Konfigurasi ${size.width}x${size.height}@$fpsValue tidak didukung") }
            }, h)
        } catch (e: Exception) { toast("Gagal: ${e.message}") }
    }

    // ---- request ----
    private fun has(k: CameraCharacteristics.Key<IntArray>, v: Int) = ch.get(k)?.contains(v) == true

    private fun applyCommon(b: CaptureRequest.Builder) {
        val fr = fpsRange()
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fr)
        if (hasOis()) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (ois) 1 else 0)
        if (video) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 0)
        if (auto) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AF_MODE, if (video) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)!!.toFloat()
            val r = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)!!
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (ev / step).roundToInt().coerceIn(r.lower, r.upper))
        } else {
            val er = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            var ns = (shutter * 1e9).toLong().coerceIn(er.lower, er.upper)
            if (video) ns = min(ns, (1e9 / fr.upper).toLong())
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange().lower, isoRange().upper))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            if (aperture > 0f) b.set(CaptureRequest.LENS_APERTURE, aperture)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, manualGains(kelvin) ?: lastGains ?: RggbChannelVector(2f, 1f, 1f, 1.6f))
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastCct ?: fallbackCct(kelvin))
        }
        if (soo) { // bypass pemrosesan ISP semaksimal yang diizinkan HAL
            if (has(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, 0)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, 0)
            val edge = if (profile.custom && profile.sharpness > 0.01f) CaptureRequest.EDGE_MODE_HIGH_QUALITY else CaptureRequest.EDGE_MODE_OFF
            if (has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, edge)) b.set(CaptureRequest.EDGE_MODE, edge)
            if (has(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, 0)) b.set(CaptureRequest.HOT_PIXEL_MODE, 0)
            if (has(CameraCharacteristics.SHADING_AVAILABLE_MODES, 0)) b.set(CaptureRequest.SHADING_MODE, 0)
            if (has(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, 0)) b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, 0)
            if (has(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, 0)) b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, 0)
            if (has(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)) {
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, profile.tonemap())
            }
        }
    }

    // ---- WB manual berbasis matriks kalibrasi sensor (cara DNG) ----
    private fun m3(t: ColorSpaceTransform?): DoubleArray? =
        t?.let { x -> DoubleArray(9) { x.getElement(it % 3, it / 3).toDouble() } }
    private fun illK(i: Int?) = when (i) {
        17 -> 2856.0; 18 -> 4874.0; 19 -> 6774.0; 20 -> 5503.0; 21 -> 6504.0; 22 -> 7504.0; 23 -> 5003.0
        1, 4, 9 -> 5500.0; 2 -> 4200.0; 3 -> 2856.0; 10 -> 6500.0; 11 -> 7500.0; 24 -> 3200.0; else -> 6504.0
    }
    private fun interp(a: ColorSpaceTransform?, b: ColorSpaceTransform?, k: Double): DoubleArray? {
        val m1 = m3(a); val m2 = m3(b)
        if (m1 == null) return m2; if (m2 == null) return m1
        val k1 = illK(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
        val k2 = illK(ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
        val w = if (k1 == k2) 0.0 else ((1 / k - 1 / k1) / (1 / k2 - 1 / k1)).coerceIn(0.0, 1.0)
        return DoubleArray(9) { m1[it] * (1 - w) + m2[it] * w }
    }
    private fun kelvinXyz(k: Double): DoubleArray {
        val t = k.coerceIn(1700.0, 25000.0)
        val x = if (t <= 4000) -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
                else -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
        val y = when {
            t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
    }
    private fun manualGains(k: Int): RggbChannelVector? {
        val cm = interp(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1), ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2), k.toDouble()) ?: return null
        val xyz = kelvinXyz(k.toDouble())
        val n = DoubleArray(3) { i -> cm[i * 3] * xyz[0] + cm[i * 3 + 1] * xyz[1] + cm[i * 3 + 2] * xyz[2] }
        if (n.any { it <= 1e-6 }) return null
        return RggbChannelVector((n[1] / n[0]).toFloat(), 1f, 1f, (n[1] / n[2]).toFloat())
    }
    private fun fallbackCct(k: Int): ColorSpaceTransform {
        val fm = interp(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1), ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2), k.toDouble())
        val r = if (fm == null) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) else {
            val x2s = doubleArrayOf(3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427)
            DoubleArray(9) { val i = it / 3; val j = it % 3; (0..2).sumOf { c -> x2s[i * 3 + c] * fm[c * 3 + j] } }
        }
        return ColorSpaceTransform(IntArray(18) { if (it % 2 == 0) (r[it / 2] * 10000).roundToInt() else 10000 })
    }
    fun evStep() = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)!!.toFloat()

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            if (auto) {
                res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastCct = it }
                res.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastGains = it }
            }
        }
    }

    fun updateRepeating() = h.post {
        val s = session ?: return@post; val d = device ?: return@post; val ps = previewSurface ?: return@post
        try {
            val b = d.createCaptureRequest(if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps); if (video) recSurface?.let { b.addTarget(it) }
            applyCommon(b)
            s.setRepeatingRequest(b.build(), previewCb, h)
        } catch (e: Exception) { toast("Request gagal: ${e.message}") }
    }

    // ---- foto ----
    fun shoot() = h.post {
        val d = device ?: return@post; val s = session ?: return@post
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            applyCommon(b)
            b.set(CaptureRequest.JPEG_QUALITY, 97); b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrient)
            shotName = "IMG_" + stamp()
            if (wantJpg) b.addTarget(jpegReader!!.surface)
            if (wantRaw) b.addTarget(rawReader!!.surface)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (wantRaw) { synchronized(this@CamController) { rawRes = res }; tryDng() }
                }
            }, h)
        } catch (e: Exception) { toast("Capture gagal: ${e.message}") }
    }

    private fun saveJpeg(img: Image) {
        val buf = img.planes[0].buffer; var bytes = ByteArray(buf.remaining()); buf.get(bytes); img.close()
        if (soo && profile.custom && abs(profile.saturation - 1f) > 0.01f) { // saturasi untuk foto: post-process
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val src = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(sensorOrient.toFloat()) }, true)
            val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(res).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(profile.saturation) }) })
            val bo = ByteArrayOutputStream(); res.compress(Bitmap.CompressFormat.JPEG, 97, bo); bytes = bo.toByteArray()
        }
        val uri = newUri("$shotName.jpg", "image/jpeg", false)
        act.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        publish(uri); toast("Tersimpan: DCIM/RawCam/$shotName.jpg")
    }

    private fun tryDng() {
        val img: Image; val res: TotalCaptureResult
        synchronized(this) { img = rawImg ?: return; res = rawRes ?: return; rawImg = null; rawRes = null }
        val uri = newUri("$shotName.dng", "image/x-adobe-dng", false)
        val orient = when (sensorOrient) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }
        act.contentResolver.openOutputStream(uri)!!.use { os ->
            DngCreator(ch, res).apply { setOrientation(orient); writeImage(os, img); close() }
        }
        img.close(); publish(uri); toast("Tersimpan: DCIM/RawCam/$shotName.dng")
    }

    // ---- video ----
    private fun prepareRecorder() {
        cleanupRecorder()
        val uri = newUri("VID_${stamp()}.mp4", "video/mp4", true); recUri = uri
        val pfd = act.contentResolver.openFileDescriptor(uri, "w")!!; recPfd = pfd
        val surf = MediaCodec.createPersistentInputSurface(); recSurface = surf
        val rate = fpsRange().upper
        val bitrate = (size.width.toLong() * size.height * rate * (if (soo) 0.35 else 0.2)).toLong().coerceAtMost(250_000_000L).toInt()
        var ok = false
        for (enc in listOf(MediaRecorder.VideoEncoder.HEVC, MediaRecorder.VideoEncoder.H264)) {
            try {
                @Suppress("DEPRECATION") val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(pfd.fileDescriptor)
                r.setVideoEncoder(enc); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setVideoSize(size.width, size.height); r.setVideoFrameRate(rate); r.setVideoEncodingBitRate(bitrate)
                r.setAudioEncodingBitRate(256_000); r.setAudioSamplingRate(48000)
                r.setOrientationHint(sensorOrient)
                r.setInputSurface(surf)
                r.prepare(); rec = r; ok = true; break
            } catch (e: Exception) { if (enc == MediaRecorder.VideoEncoder.H264) toast("Recorder gagal: ${e.message}") }
        }
        if (!ok) throw IllegalStateException("Tidak bisa menyiapkan encoder")
    }

    fun toggleRecord() = h.post {
        if (!recording) {
            try { rec?.start(); recording = true; act.runOnUiThread { onRecState(true) } } catch (e: Exception) { toast("Gagal rekam: ${e.message}") }
        } else stopRecInternal(true)
    }

    private fun stopRecInternal(rebuild: Boolean) {
        try { rec?.stop() } catch (_: RuntimeException) {}
        recording = false
        try { rec?.release() } catch (_: Exception) {}
        rec = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { publish(it); toast("Video tersimpan di DCIM/RawCam") }
        recUri = null; recSurface?.release(); recSurface = null
        act.runOnUiThread { onRecState(false) }
        if (rebuild) createSession()
    }

    // ---- MediaStore -> DCIM/RawCam ----
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun newUri(name: String, mime: String, vid: Boolean): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/RawCam"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (vid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return act.contentResolver.insert(coll, cv)!!
    }
    private fun publish(uri: Uri) {
        act.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
}
