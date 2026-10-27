package com.rawcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pengendali Camera2.
 * Video : Default (bawaan HP) / RAW (HEVC kecil atau urutan DNG) / SOOC Plain / SOOC Custom.
 * Foto  : JPEG / RAW(DNG) / RAW+JPEG dengan look Bawaan / Plain / Kustom (untuk JPEG).
 */
class CameraController(
    private val ctx: Context,
    private val rotationProvider: () -> Int,
    private val textureProvider: () -> SurfaceTexture?,
    private val listener: Listener
) {
    interface Listener {
        fun onCameraReady()
        fun onPreviewSize(w: Int, h: Int)
        fun onStatus(text: String)
        fun onRecording(on: Boolean)
        fun onMessage(msg: String)
    }

    enum class PhotoFmt { JPEG, RAW, RAW_JPEG }
    enum class VideoFmt { DEFAULT, RAW, PLAIN, CUSTOM }
    enum class Look { DEFAULT, PLAIN, CUSTOM }
    enum class RawOut { VIDEO, DNG }
    private enum class Kind { PHOTO, VIDEO_IDLE, REC_VIDEO, REC_RAW }

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("rawcam").also { it.start() }
    private val handler = Handler(thread.looper)
    private val writers: ExecutorService = Executors.newFixedThreadPool(2)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private lateinit var chars: CameraCharacteristics
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var kind = Kind.PHOTO
    private var previewSize = Size(1920, 1080)
    private var quiet = false
    private val plainProfile = ColorProfile.plainHdr()

    var cameraId = ""
        private set

    // ---------------- pengaturan ----------------
    @Volatile var auto = true
        set(v) { field = v; update() }
    /** Kompensasi EV (indeks langkah kamera) dari pengguna; bias profil ditambahkan otomatis. */
    @Volatile var ev = 0
        set(v) { field = v; update() }
    @Volatile var aeLock = false
        set(v) { field = v; update() }
    @Volatile var iso = 100
        set(v) { field = v; update() }
    @Volatile var shutterNs = 16_666_667L
        set(v) { field = v; update() }
    @Volatile var aperture = 0f
        set(v) { field = v; update() }
    /** 0 = autofocus, 1..100 = fokus manual (1 = tak hingga, 100 = terdekat). */
    @Volatile var focusPos = 0
        set(v) { field = v; update() }
    @Volatile var awbAuto = true
        set(v) { field = v; update() }
    @Volatile var kelvin = 5500
        set(v) { field = v; update() }
    @Volatile var tint = 0
        set(v) { field = v; update() }
    @Volatile var ois = true
        set(v) { field = v; update() }
    /** Kalibrasi: HP yang memperlakukan input kurva tonemap sebagai gamma, bukan linear. */
    @Volatile var curveGammaInput = false
        set(v) { field = v; update() }
    @Volatile var profile: ColorProfile = ColorProfile.plainHdr()
        set(v) { field = v; update() }

    @Volatile var videoMode = false
        set(v) { field = v; restartIdle() }
    @Volatile var photoFmt = PhotoFmt.JPEG
        set(v) { field = v; if (!videoMode) restartIdle() }
    @Volatile var photoLook = Look.DEFAULT
        set(v) { field = v; update() }
    @Volatile var photoOpenGate = true
        set(v) {
            field = v
            if (!quiet) handler.post {
                if (device != null && !recording) {
                    closeSession(); setupReaders()
                    startSession(if (videoMode) Kind.VIDEO_IDLE else Kind.PHOTO)
                }
            }
        }
    @Volatile var videoFmt = VideoFmt.DEFAULT
        set(v) { field = v; if (videoMode) restartIdle() }
    @Volatile var rawOut = RawOut.VIDEO
        set(v) { field = v; if (videoMode) restartIdle() }
    @Volatile var videoSize = Size(1920, 1080)
        set(v) { field = v; if (videoMode) restartIdle() }
    @Volatile var fps = 30
        set(v) { field = v; if (videoMode) restartIdle() }
    /** Open gate video: rasio sensor penuh bila encoder mendukung. */
    @Volatile var openGate = false
        set(v) {
            field = v
            if (!quiet) {
                quiet = true
                videoSize = videoSizes().first()
                normalizeFps()
                quiet = false
                if (videoMode) restartIdle()
            }
        }
    @Volatile var recording = false
        private set
    @Volatile private var recDeg = 0

    // ---------------- turunan ----------------
    private fun isDng() = videoMode && videoFmt == VideoFmt.RAW && rawOut == RawOut.DNG && rawReader != null

    private fun look(): Look = if (videoMode) when (videoFmt) {
        VideoFmt.DEFAULT -> Look.DEFAULT
        VideoFmt.RAW, VideoFmt.PLAIN -> Look.PLAIN
        VideoFmt.CUSTOM -> Look.CUSTOM
    } else photoLook

    private fun biasEv(): Float {
        val active = if (videoMode) !isDng() else photoFmt != PhotoFmt.RAW
        if (!active) return 0f
        return when (look()) { Look.PLAIN -> -1f; Look.CUSTOM -> profile.evBias; else -> 0f }
    }
    val evBiasNow: Float get() = biasEv()

    private fun evIndex(): Int =
        (ev + (biasEv() / evStepF).roundToInt()).coerceIn(evRange.lower, evRange.upper)

    // ---------------- kapabilitas ----------------
    private val rawCap: Boolean
        get() = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
    val rawSupported: Boolean get() = rawReader != null
    val curveSupported: Boolean
        get() = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            ?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true
    val oisSupported: Boolean
        get() = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
    val satSupported: Boolean
        get() = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
    val wbManualSupported: Boolean
        get() = satSupported && WbMath.compute(chars, 5500f, 0f) != null
    val isoRange: Range<Int>
        get() = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
    private val expRange: Range<Long>
        get() = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
    val shutterMin: Long get() = expRange.lower
    val shutterMax: Long
        get() = if (videoMode) max(expRange.lower, 1_000_000_000L / fps)
        else min(expRange.upper, 30_000_000_000L)
    val apertures: FloatArray
        get() = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf()
    val evRange: Range<Int>
        get() = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val evStepF: Float
        get() = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
    private val minFocus: Float
        get() = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    private val hevcSupported: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals("video/hevc", true) }
        }
    }

    fun backCameras(): List<Pair<String, String>> = mgr.cameraIdList.mapNotNull { id ->
        val c = mgr.getCameraCharacteristics(id)
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@mapNotNull null
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@mapNotNull null
        val eq = f * 43.27f / hypot(sz.width, sz.height)
        id to "Kamera $id · ${eq.roundToInt()}mm"
    }

    fun availablePhotoFmts(): List<PhotoFmt> =
        if (rawSupported) listOf(PhotoFmt.JPEG, PhotoFmt.RAW, PhotoFmt.RAW_JPEG) else listOf(PhotoFmt.JPEG)

    fun availableLooks(): List<Look> =
        if (curveSupported) listOf(Look.DEFAULT, Look.PLAIN, Look.CUSTOM) else listOf(Look.DEFAULT)

    fun availableVideoFmts(): List<VideoFmt> =
        if (curveSupported) listOf(VideoFmt.DEFAULT, VideoFmt.RAW, VideoFmt.PLAIN, VideoFmt.CUSTOM)
        else listOf(VideoFmt.DEFAULT, VideoFmt.RAW)

    fun availableRawOuts(): List<RawOut> =
        if (rawSupported) listOf(RawOut.VIDEO, RawOut.DNG) else listOf(RawOut.VIDEO)

    // ---------------- ukuran video ----------------
    fun videoSizes(): List<Size> =
        if (openGate) openGateSizes().ifEmpty { normalSizes() } else normalSizes()

    val openGateAvailable: Boolean get() = openGateSizes().isNotEmpty()

    private fun videoCaps(): MediaCodecInfo.VideoCapabilities? {
        val mime = if (hevcSupported) "video/hevc" else "video/avc"
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { ci -> ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } }
            ?.getCapabilitiesForType(mime)?.videoCapabilities
    }

    private fun openGateSizes(): List<Size> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val all = map.getOutputSizes(MediaRecorder::class.java) ?: return emptyList()
        val arr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return emptyList()
        val ratio = arr.width().toFloat() / arr.height()
        val caps = videoCaps()
        return all.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }
            .filter { caps?.isSizeSupported(it.width, it.height) ?: true }
            .sortedByDescending { it.width.toLong() * it.height }
            .take(3)
    }

    fun normalizeFps() {
        val fo = fpsOptions(videoSize)
        if (fps !in fo) fps = fo.lastOrNull { it <= 30 } ?: fo.first()
    }

    private fun normalSizes(): List<Size> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val all = map.getOutputSizes(MediaRecorder::class.java) ?: return listOf(Size(1920, 1080))
        val wanted = listOf(Size(3840, 2160), Size(2560, 1440), Size(1920, 1080), Size(1280, 720))
        return wanted.filter { w -> all.any { it.width == w.width && it.height == w.height } }
            .ifEmpty { listOf(all.maxByOrNull { it.width * it.height }!!) }
    }

    fun fpsOptions(sz: Size): List<Int> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val minDur = map.getOutputMinFrameDuration(MediaRecorder::class.java, sz)
        val maxFps = if (minDur > 0) (1e9 / minDur).toInt() else 30
        return listOf(24, 25, 30, 50, 60, 120).filter { it <= maxFps + 1 }.ifEmpty { listOf(30) }
    }

    // ---------------- buka / tutup ----------------
    @SuppressLint("MissingPermission")
    fun open(id: String) {
        handler.post {
            closeInternal()
            try {
                chars = mgr.getCameraCharacteristics(id)
                cameraId = id
                setupReaders()
                normalize()
                mgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        listener.onCameraReady()
                        startSession(if (videoMode) Kind.VIDEO_IDLE else Kind.PHOTO)
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                    override fun onError(d: CameraDevice, error: Int) {
                        d.close(); device = null
                        listener.onMessage("Kamera error ($error)")
                    }
                }, handler)
            } catch (e: Exception) {
                listener.onMessage("Gagal membuka kamera: ${e.message}")
            }
        }
    }

    fun close() { handler.post { closeInternal() } }

    private fun closeInternal() {
        if (recording) stopRecordingInternal(restart = false)
        closeSession()
        try { device?.close() } catch (_: Exception) {}
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
    }

    private fun normalize() {
        quiet = true
        if (openGate && openGateSizes().isEmpty()) openGate = false
        val vs = videoSizes()
        if (videoSize !in vs) videoSize = vs.first()
        val fo = fpsOptions(videoSize)
        if (fps !in fo) fps = fo.lastOrNull { it <= 30 } ?: fo.first()
        iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        shutterNs = shutterNs.coerceIn(shutterMin, max(shutterMin, shutterMax))
        val ap = apertures
        if (ap.isNotEmpty() && ap.none { it == aperture }) aperture = ap[0]
        ev = ev.coerceIn(evRange.lower, evRange.upper)
        if (photoFmt != PhotoFmt.JPEG && !rawSupported) photoFmt = PhotoFmt.JPEG
        if (rawOut == RawOut.DNG && !rawSupported) rawOut = RawOut.VIDEO
        if (!curveSupported) {
            if (photoLook != Look.DEFAULT) photoLook = Look.DEFAULT
            if (videoFmt == VideoFmt.PLAIN || videoFmt == VideoFmt.CUSTOM) videoFmt = VideoFmt.DEFAULT
        }
        quiet = false
    }

    private fun photoSize(map: android.hardware.camera2.params.StreamConfigurationMap): Size {
        val all = map.getOutputSizes(ImageFormat.JPEG)
        val arr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorRatio = if (arr != null) arr.width().toFloat() / arr.height() else 4f / 3f
        val target = if (photoOpenGate) sensorRatio else 16f / 9f
        return all.filter { abs(it.width.toFloat() / it.height - target) < 0.02f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: all.maxByOrNull { it.width.toLong() * it.height }!!
    }

    private fun setupReaders() {
        jpegReader?.close(); rawReader?.close()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val js = photoSize(map)
        jpegReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 3).also {
            it.setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
        }
        rawReader = null
        if (rawCap) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { rs ->
                rawReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 6).also {
                    it.setOnImageAvailableListener({ r -> onRaw(r) }, handler)
                }
            }
        }
    }

    // ---------------- sesi capture ----------------
    private fun restartIdle() {
        if (quiet) return
        handler.post {
            if (device != null && !recording) startSession(if (videoMode) Kind.VIDEO_IDLE else Kind.PHOTO)
        }
    }

    private fun update() {
        if (quiet) return
        handler.post { updateRepeating() }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        images.values.forEach { try { it.close() } catch (_: Exception) {} }
        images.clear(); results.clear()
    }

    private fun aspectSource(): Size {
        val rr = rawReader
        return when {
            !videoMode -> jpegReader!!.let { Size(it.width, it.height) }
            isDng() && rr != null -> Size(rr.width, rr.height)
            else -> videoSize
        }
    }

    private fun choosePreview(target: Size): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        val ratio = target.width.toFloat() / target.height
        return sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.minByOrNull { abs(it.width.toFloat() / it.height - ratio) }
            ?: sizes.first()
    }

    @Suppress("DEPRECATION")
    private fun startSession(k: Kind, onReady: (() -> Unit)? = null, onFail: (() -> Unit)? = null) {
        val d = device ?: return
        kind = k
        closeSession()
        val st = textureProvider() ?: return
        previewSize = choosePreview(aspectSource())
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        listener.onPreviewSize(previewSize.width, previewSize.height)
        val ps = Surface(st)
        val outs = mutableListOf(ps)
        when (k) {
            Kind.PHOTO -> {
                if (photoFmt != PhotoFmt.RAW) outs += jpegReader!!.surface
                if (photoFmt != PhotoFmt.JPEG) rawReader?.let { outs += it.surface }
            }
            Kind.VIDEO_IDLE -> {}
            Kind.REC_VIDEO -> outs += recorder!!.surface
            Kind.REC_RAW -> outs += rawReader!!.surface
        }
        try {
            d.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null) { s.close(); return }
                    session = s
                    previewSurface = ps
                    updateRepeating()
                    onReady?.invoke()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onMessage("Kombinasi stream tidak didukung perangkat ini untuk mode tersebut")
                    onFail?.invoke()
                }
            }, handler)
        } catch (e: Exception) {
            listener.onMessage("Gagal membuat sesi: ${e.message}")
            onFail?.invoke()
        }
    }

    private val previewCb = Cb(false)
    private val rawCb = Cb(true)

    private fun updateRepeating() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(
                if (kind == Kind.REC_VIDEO || kind == Kind.REC_RAW) CameraDevice.TEMPLATE_RECORD
                else CameraDevice.TEMPLATE_PREVIEW
            )
            b.addTarget(ps)
            if (kind == Kind.REC_VIDEO) b.addTarget(recorder!!.surface)
            if (kind == Kind.REC_RAW) b.addTarget(rawReader!!.surface)
            applyParams(b)
            s.setRepeatingRequest(b.build(), if (kind == Kind.REC_RAW) rawCb else previewCb, handler)
        } catch (_: Exception) {
        }
    }

    // ---------------- parameter ----------------
    private fun applyParams(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        applyWb(b)

        if (focusPos <= 0) {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (videoMode) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (focusPos - 1) / 99f * minFocus)
        }

        if (auto) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex())
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val frameNs = 1_000_000_000L / fps
            val t = if (videoMode) min(shutterNs, frameNs) else shutterNs
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (videoMode) frameNs else max(t, 33_333_333L))
            val ap = apertures
            if (ap.size > 1 && aperture > 0f) b.set(CaptureRequest.LENS_APERTURE, aperture)
        }
        if (videoMode) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange())
        if (rawCap) b.set(
            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
        )
        if (oisSupported) b.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )
        applyLook(b)
    }

    private fun fpsRange(): Range<Int> {
        val rs = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
        return rs.firstOrNull { it.lower == fps && it.upper == fps }
            ?: rs.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: rs.maxByOrNull { it.upper } ?: Range(fps, fps)
    }

    private fun curvePts(): Int {
        val maxPts = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 64
        return min(maxPts, 128).coerceAtLeast(16)
    }

    /** Look: Default = tidak diubah. Plain/Custom = kurva DR tinggi / kurva pengguna + NR ringan + sharpness. */
    private fun applyLook(b: CaptureRequest.Builder) {
        val l = look()
        if (l == Look.DEFAULT) return
        val nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
        val nrMode = when {
            !videoMode && nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) ->
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST) -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        if (nrMode != null) b.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)

        val edge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
        val sharp = if (l == Look.CUSTOM) profile.sharpness else 1
        val wantEdge = when (sharp) {
            0 -> CameraMetadata.EDGE_MODE_OFF
            2 -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            else -> CameraMetadata.EDGE_MODE_FAST
        }
        if (edge.contains(wantEdge)) b.set(CaptureRequest.EDGE_MODE, wantEdge)
        if (videoMode) b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        val modes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        if (modes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)) {
            val p = if (l == Look.PLAIN) plainProfile else profile
            val (r, g, bl) = p.curves(curvePts(), curveGammaInput)
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(r, g, bl))
        }
    }

    // ---------------- white balance (manual + saturasi) ----------------
    @Volatile private var wbFrozen = false
    @Volatile private var wbResampling = false
    private var wbGains: RggbChannelVector? = null
    private var wbXform: ColorSpaceTransform? = null

    /** Ambil ulang WB dari scene saat ini (dipakai bila saturasi != 100%, WB otomatis dibekukan). */
    fun resampleWb() {
        wbResampling = true; wbFrozen = false; update()
        handler.postDelayed({ wbResampling = false; update() }, 800)
    }

    private fun matOf(t: ColorSpaceTransform) = Array(3) { r -> FloatArray(3) { c -> t.getElement(c, r).toFloat() } }

    private fun cst(m: Array<FloatArray>): ColorSpaceTransform {
        val out = arrayOfNulls<Rational>(9)
        for (r in 0..2) for (c in 0..2) out[r * 3 + c] = Rational((m[r][c] * 10000).roundToInt(), 10000)
        return ColorSpaceTransform(out.requireNoNulls())
    }

    private fun satMul(s: Float, m: Array<FloatArray>): Array<FloatArray> {
        val lw = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val sat = Array(3) { r -> FloatArray(3) { c -> (1 - s) * lw[c] + (if (r == c) s else 0f) } }
        return Array(3) { r ->
            FloatArray(3) { c ->
                var v = 0f
                for (k in 0..2) v += sat[r][k] * m[k][c]
                v
            }
        }
    }

    private fun applyWb(b: CaptureRequest.Builder) {
        val sat = if (look() == Look.CUSTOM) profile.saturation else 1f
        val satOn = abs(sat - 1f) > 0.01f && satSupported

        if (!awbAuto && satSupported) {
            val wb = WbMath.compute(chars, kelvin.toFloat(), tint.toFloat())
            if (wb != null) {
                wbFrozen = false
                val m = if (satOn) satMul(sat, wb.matrix) else wb.matrix
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(wb.gains[0], wb.gains[1], wb.gains[2], wb.gains[3])
                )
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, cst(m))
                return
            }
        }

        val g = wbGains
        val x = wbXform
        if (!satOn || wbResampling || g == null || x == null) {
            wbFrozen = false
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            return
        }
        wbFrozen = true
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        b.set(CaptureRequest.COLOR_CORRECTION_GAINS, g)
        b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, cst(satMul(sat, matOf(x))))
    }

    // ---------------- hasil capture ----------------
    private var lastStatus = 0L

    private inner class Cb(val storeRaw: Boolean) : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            if (!wbFrozen && result.get(CaptureResult.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { wbGains = it }
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { wbXform = it }
            }
            if (storeRaw) {
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = result; tryWrite(it) }
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastStatus > 200) { lastStatus = now; publishStatus(result) }
        }
    }

    private fun publishStatus(res: TotalCaptureResult) {
        val i = res.get(CaptureResult.SENSOR_SENSITIVITY)
        val e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val a = res.get(CaptureResult.LENS_APERTURE)
        val sb = StringBuilder("ISO ${i ?: "?"}  ")
        sb.append(
            if (e == null) "?"
            else if (e >= 500_000_000L) String.format(Locale.US, "%.1fs", e / 1e9)
            else "1/${(1e9 / e).roundToInt()}"
        )
        if (a != null) sb.append(String.format(Locale.US, "  f/%.1f", a))
        if (recording && kind == Kind.REC_RAW)
            sb.append("\nREC DNG: ${frameNo.get()} frame · drop ${dropped.get()} · antre ${pending.get()}")
        else if (recording) sb.append("\nREC ●")
        listener.onStatus(sb.toString())
    }

    // ---------------- penyimpanan ----------------
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveStream(collection: Uri, name: String, mime: String, rel: String): OutputStream {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val uri = ctx.contentResolver.insert(collection, cv) ?: throw IllegalStateException("MediaStore insert gagal")
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Tidak bisa menulis file")
        return BufferedOutputStream(os, 1 shl 20)
    }

    private fun deviceDeg() = rotationProvider() * 90
    private fun outOrientation() = ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) - deviceDeg() + 360) % 360
    private fun exifOf(deg: Int) = when (deg) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }

    private var photoStamp = ""

    private fun onJpeg(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
        img.close()
        val name = "IMG_${photoStamp.ifEmpty { stamp() }}.jpg"
        writers.execute {
            try {
                saveStream(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/jpeg", "DCIM/RawCam")
                    .use { it.write(bytes) }
                listener.onMessage("JPEG tersimpan (DCIM/RawCam)")
            } catch (e: Exception) { listener.onMessage("Gagal simpan JPEG: ${e.message}") }
        }
    }

    private val images = ConcurrentHashMap<Long, Image>()
    private val results = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val frameNo = AtomicInteger(0)
    @Volatile private var clipDir: String? = null

    private fun onRaw(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (kind == Kind.REC_RAW && pending.get() >= 5) {
            dropped.incrementAndGet()
            results.remove(img.timestamp)
            img.close()
            return
        }
        images[img.timestamp] = img
        tryWrite(img.timestamp)
    }

    private fun tryWrite(ts: Long) {
        val img = images[ts] ?: return
        val res = results[ts] ?: return
        images.remove(ts); results.remove(ts)
        images.keys.filter { it < ts }.forEach { images.remove(it)?.close() }
        results.keys.filter { it < ts }.forEach { results.remove(it) }
        val dir = clipDir
        val n = if (dir != null) frameNo.incrementAndGet() else 0
        val ch = chars
        val orient = exifOf(if (dir != null) recDeg else outOrientation())
        val photoName = "RAW_${photoStamp.ifEmpty { stamp() }}.dng"
        pending.incrementAndGet()
        writers.execute {
            var dng: DngCreator? = null
            try {
                dng = DngCreator(ch, res).also { it.setOrientation(orient) }
                val name = if (dir != null) String.format(Locale.US, "frame_%06d.dng", n) else photoName
                val rel = if (dir != null) "DCIM/RawCam/$dir" else "DCIM/RawCam"
                saveStream(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, name, "image/x-adobe-dng", rel).use { out ->
                    dng.writeImage(out, img)
                }
                if (dir == null) listener.onMessage("DNG tersimpan (DCIM/RawCam)")
            } catch (e: Exception) {
                listener.onMessage("Gagal simpan DNG: ${e.message}")
            } finally {
                try { dng?.close() } catch (_: Exception) {}
                try { img.close() } catch (_: Exception) {}
                pending.decrementAndGet()
            }
        }
    }

    // ---------------- foto ----------------
    fun takePhoto() {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            if (videoMode || kind != Kind.PHOTO) return@post
            val wantJpeg = photoFmt != PhotoFmt.RAW
            val wantRaw = photoFmt != PhotoFmt.JPEG && rawReader != null
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyParams(b)
                if (wantJpeg) {
                    b.addTarget(jpegReader!!.surface)
                    b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    b.set(CaptureRequest.JPEG_ORIENTATION, outOrientation())
                }
                if (wantRaw) b.addTarget(rawReader!!.surface)
                photoStamp = stamp()
                s.capture(b.build(), if (wantRaw) rawCb else previewCb, handler)
            } catch (e: Exception) {
                listener.onMessage("Gagal mengambil foto: ${e.message}")
            }
        }
    }

    // ---------------- video ----------------
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null

    private fun prepareRecorder(): Boolean {
        val hasAudio = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}_${videoFmt.name}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/RawCam")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return false
        recUri = uri; recPfd = pfd
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        if (hasAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(pfd.fileDescriptor)
        val flat = look() != Look.DEFAULT
        val hevc = (flat || openGate) && hevcSupported
        val px = videoSize.width.toLong() * videoSize.height
        // profil datar butuh bitrate lebih tinggi agar gradasi bayangan tidak pecah
        val bitrate = if (px >= 8_000_000L) (if (flat) 100_000_000 else 80_000_000)
        else (if (flat) 60_000_000 else 40_000_000)
        r.setVideoEncodingBitRate(bitrate)
        r.setVideoFrameRate(fps)
        r.setVideoSize(videoSize.width, videoSize.height)
        r.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (hasAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(192_000)
            r.setAudioSamplingRate(48_000)
        }
        r.setOrientationHint(outOrientation())
        r.prepare()
        recorder = r
        return true
    }

    private fun releaseRecorder(deleteFile: Boolean = false) {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        if (deleteFile) recUri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
        recUri = null
    }

    fun startRecording() {
        handler.post {
            if (recording || device == null || !videoMode) return@post
            if (isDng()) {
                recDeg = outOrientation()
                frameNo.set(0); dropped.set(0); pending.set(0)
                clipDir = "CLIP_${stamp()}"
                startSession(Kind.REC_RAW, onReady = {
                    recording = true
                    listener.onRecording(true)
                }, onFail = { clipDir = null })
                return@post
            }
            try {
                if (!prepareRecorder()) { listener.onMessage("Gagal membuat file video"); return@post }
            } catch (e: Exception) {
                listener.onMessage("Gagal menyiapkan perekam: ${e.message}")
                releaseRecorder(true)
                return@post
            }
            startSession(Kind.REC_VIDEO, onReady = {
                try {
                    recorder?.start()
                    recording = true
                    listener.onRecording(true)
                } catch (e: Exception) {
                    listener.onMessage("Gagal mulai merekam: ${e.message}")
                    releaseRecorder(true)
                    startSession(Kind.VIDEO_IDLE)
                }
            }, onFail = { releaseRecorder(true) })
        }
    }

    fun stopRecording() { handler.post { stopRecordingInternal(restart = true) } }

    private fun stopRecordingInternal(restart: Boolean) {
        if (!recording) return
        recording = false
        if (kind == Kind.REC_RAW) {
            clipDir = null
            listener.onRecording(false)
            listener.onMessage("Selesai: ${frameNo.get()} frame DNG (drop ${dropped.get()}) di DCIM/RawCam")
            if (restart) startSession(Kind.VIDEO_IDLE)
            return
        }
        try { session?.stopRepeating() } catch (_: Exception) {}
        var ok = true
        try { recorder?.stop() } catch (_: RuntimeException) { ok = false }
        releaseRecorder(!ok)
        listener.onRecording(false)
        listener.onMessage(if (ok) "Video tersimpan di Movies/RawCam" else "Rekaman terlalu pendek, dibuang")
        if (restart) startSession(Kind.VIDEO_IDLE)
    }
}
