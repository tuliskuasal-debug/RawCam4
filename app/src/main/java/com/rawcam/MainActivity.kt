package com.rawcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.rawcam.CameraController.Look
import com.rawcam.CameraController.PhotoFmt
import com.rawcam.CameraController.RawOut
import com.rawcam.CameraController.VideoFmt
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener {

    private lateinit var root: FrameLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var tv: AutoFitTextureView
    private lateinit var ctl: CameraController
    private lateinit var prefs: SharedPreferences

    private lateinit var tvStatus: TextView
    private lateinit var spCam: Spinner
    private lateinit var spFmt: Spinner
    private lateinit var spLook: Spinner
    private lateinit var spRaw: Spinner
    private lateinit var spSize: Spinner
    private lateinit var spFps: Spinner
    private lateinit var btnPhoto: Button
    private lateinit var btnVideo: Button
    private lateinit var btnAuto: Button
    private lateinit var btnManual: Button
    private lateinit var btnCapture: Button
    private lateinit var btnProfile: Button
    private lateinit var cbGate: CheckBox
    private lateinit var cbOis: CheckBox
    private lateinit var grpAuto: LinearLayout
    private lateinit var grpManual: LinearLayout
    private lateinit var grpVideo: LinearLayout
    private lateinit var sbEv: SeekBar
    private lateinit var sbIso: SeekBar
    private lateinit var sbShutter: SeekBar
    private lateinit var sbAp: SeekBar
    private lateinit var sbFocus: SeekBar
    private lateinit var lbEv: TextView
    private lateinit var lbIso: TextView
    private lateinit var lbShutter: TextView
    private lateinit var lbAp: TextView
    private lateinit var lbFocus: TextView
    private lateinit var cbAeLock: CheckBox
    private lateinit var cbAwb: CheckBox
    private lateinit var lbWb: TextView
    private lateinit var lbTint: TextView
    private lateinit var sbWb: SeekBar
    private lateinit var sbTint: SeekBar

    private var camIds = listOf<String>()
    private var photoFmts = listOf<PhotoFmt>()
    private var videoFmts = listOf<VideoFmt>()
    private var looks = listOf<Look>()
    private var rawOuts = listOf<RawOut>()
    private var sizes = listOf<Size>()
    private var fpsList = listOf<Int>()

    // editor profil
    private var editing: ColorProfile = ColorProfile.plainHdr()
    private var editorLoad: ((ColorProfile) -> Unit)? = null
    private val REQ_IMPORT = 21
    private val REQ_EXPORT = 22

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        prefs = getSharedPreferences("rawcam", MODE_PRIVATE)
        tv = AutoFitTextureView(this)
        ctl = CameraController(this, { rotation() }, { tv.surfaceTexture }, this)
        prefs.getString("_last", null)?.let { ColorProfile.fromJson(it) }?.let { ctl.profile = it }
        ctl.curveGammaInput = prefs.getBoolean("_gamma", false)
        buildUi()
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (!hasCam()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(dl, null)
        if (tv.isAvailable) openCamera()
    }

    override fun onPause() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(dl)
        ctl.close()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::panelScroll.isInitialized) applyLayout()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasCam()) openCamera() else toast("Izin kamera diperlukan")
    }

    private val dl = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { tv.post { if (::panelScroll.isInitialized) applyLayout() } }
    }

    private fun hasCam() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        if (!hasCam() || !tv.isAvailable) return
        val id = ctl.cameraId.ifEmpty { ctl.backCameras().firstOrNull()?.first ?: return }
        ctl.open(id)
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = windowManager.defaultDisplay.rotation

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ------------------------------------------------------------ listener dari controller
    override fun onCameraReady() { runOnUiThread { refreshAll() } }
    override fun onPreviewSize(w: Int, h: Int) {
        runOnUiThread { tv.setAspectRatio(w, h); tv.updateTransform(rotation()) }
    }
    override fun onStatus(text: String) { runOnUiThread { tvStatus.text = text } }
    override fun onRecording(on: Boolean) {
        runOnUiThread { btnCapture.text = if (on) "■" else "●"; setPanelEnabled(!on); applyVisibility() }
    }
    override fun onMessage(msg: String) { runOnUiThread { toast(msg) } }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun toastLong(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ UI helpers
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 12f }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 12f; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun seek(max: Int, cb: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) cb(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun spinner(cb: (Int) -> Unit) = Spinner(this).apply {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { cb(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setItems(sp: Spinner, items: List<String>, sel: Int) {
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        if (sel >= 0) sp.setSelection(sel, false)
    }

    private fun LinearLayout.add(v: View) = addView(v, LinearLayout.LayoutParams(-1, -2))

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        addView(a, LinearLayout.LayoutParams(0, -2, 1f))
        addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ------------------------------------------------------------ bangun UI
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(tv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tv.updateTransform(rotation()) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xB3000000.toInt())
        }
        panelScroll = ScrollView(this).apply { addView(panel) }
        root.addView(panelScroll, FrameLayout.LayoutParams(dp(300), -1, Gravity.END))

        tvStatus = TextView(this).apply {
            setTextColor(Color.rgb(120, 255, 160)); textSize = 12f; typeface = Typeface.MONOSPACE; text = "…"
        }
        panel.add(tvStatus)

        spCam = spinner { pos ->
            val id = camIds.getOrNull(pos)
            if (id != null && id != ctl.cameraId && !ctl.recording) ctl.open(id)
        }
        panel.add(spCam)

        btnPhoto = btn("FOTO") { setMode(false) }
        btnVideo = btn("VIDEO") { setMode(true) }
        panel.add(row(btnPhoto, btnVideo))

        // foto: format file | video: mode kamera
        spFmt = spinner { pos ->
            if (ctl.recording) return@spinner
            if (ctl.videoMode) {
                val f = videoFmts.getOrNull(pos) ?: return@spinner
                if (f != ctl.videoFmt) { ctl.videoFmt = f; refreshVideoOpts(); applyVisibility(); updateEvLabel() }
            } else {
                val f = photoFmts.getOrNull(pos) ?: return@spinner
                if (f != ctl.photoFmt) { ctl.photoFmt = f; updateEvLabel() }
            }
        }
        panel.add(spFmt)

        spLook = spinner { pos ->
            val l = looks.getOrNull(pos) ?: return@spinner
            if (l != ctl.photoLook) { ctl.photoLook = l; applyVisibility(); updateEvLabel() }
        }
        panel.add(spLook)

        spRaw = spinner { pos ->
            val o = rawOuts.getOrNull(pos) ?: return@spinner
            if (o != ctl.rawOut && !ctl.recording) { ctl.rawOut = o; refreshVideoOpts(); applyVisibility(); updateEvLabel() }
        }
        panel.add(spRaw)

        spSize = spinner { pos ->
            val s = sizes.getOrNull(pos) ?: return@spinner
            if (s != ctl.videoSize && !ctl.recording) { ctl.videoSize = s; refreshVideoOpts() }
        }
        spFps = spinner { pos ->
            val f = fpsList.getOrNull(pos) ?: return@spinner
            if (f != ctl.fps && !ctl.recording) { ctl.fps = f; refreshShutter() }
        }
        grpVideo = LinearLayout(this)
        grpVideo.addView(spSize, LinearLayout.LayoutParams(0, -2, 1f))
        grpVideo.addView(spFps, LinearLayout.LayoutParams(0, -2, 1f))
        panel.add(grpVideo)

        cbGate = CheckBox(this).apply {
            text = "Open gate (rasio sensor penuh)"; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c ->
                if (!ctl.recording && ctl.cameraId.isNotEmpty()) {
                    if (ctl.videoMode) {
                        if (c != ctl.openGate) { ctl.openGate = c; refreshVideoOpts() }
                    } else if (c != ctl.photoOpenGate) ctl.photoOpenGate = c
                }
            }
        }
        panel.add(cbGate)

        btnProfile = btn("Edit profil warna…") { showProfileDialog() }
        panel.add(btnProfile)

        cbOis = CheckBox(this).apply {
            text = "OIS bawaan HP"; isChecked = true; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> if (c != ctl.ois) ctl.ois = c }
        }
        panel.add(cbOis)

        btnAuto = btn("AUTO") { ctl.auto = true; applyVisibility() }
        btnManual = btn("MANUAL") { ctl.auto = false; if (ctl.cameraId.isNotEmpty()) refreshShutter(); applyVisibility() }
        panel.add(row(btnAuto, btnManual))

        // --- AUTO: target eksposur (EV) + kunci AE
        grpAuto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lbEv = label("Target eksposur: 0.0 EV")
        sbEv = seek(1) { p -> ctl.ev = p + ctl.evRange.lower; updateEvLabel() }
        cbAeLock = CheckBox(this).apply {
            text = "Kunci AE"; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> ctl.aeLock = c }
        }
        grpAuto.add(lbEv); grpAuto.add(sbEv); grpAuto.add(cbAeLock)
        panel.add(grpAuto)

        // --- MANUAL: ISO, shutter, aperture
        grpManual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lbIso = label("ISO")
        sbIso = seek(1000) { p -> ctl.iso = progToIso(p); lbIso.text = "ISO ${ctl.iso}" }
        lbShutter = label("Shutter")
        sbShutter = seek(1000) { p -> ctl.shutterNs = progToShutter(p); lbShutter.text = "Shutter " + fmtShutter(ctl.shutterNs) }
        lbAp = label("Aperture")
        sbAp = seek(1) { p ->
            val ap = ctl.apertures
            if (p in ap.indices) { ctl.aperture = ap[p]; lbAp.text = String.format(Locale.US, "Aperture f/%.1f", ap[p]) }
        }
        grpManual.add(lbIso); grpManual.add(sbIso)
        grpManual.add(lbShutter); grpManual.add(sbShutter)
        grpManual.add(lbAp); grpManual.add(sbAp)
        panel.add(grpManual)

        lbFocus = label("Fokus: AF")
        sbFocus = seek(100) { p -> ctl.focusPos = p; lbFocus.text = if (p == 0) "Fokus: AF" else "Fokus: MF $p%" }
        panel.add(lbFocus); panel.add(sbFocus)

        // --- White balance (berlaku di mode AUTO maupun MANUAL)
        cbAwb = CheckBox(this).apply {
            text = "WB otomatis"; isChecked = true; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> if (c != ctl.awbAuto) ctl.awbAuto = c; updateWbEnabled() }
        }
        lbWb = label("WB 5500K")
        sbWb = seek(80) { p -> ctl.kelvin = 2000 + p * 100; lbWb.text = "WB ${ctl.kelvin}K" }.apply { progress = 35 }
        lbTint = label("Tint 0")
        sbTint = seek(200) { p -> ctl.tint = p - 100; lbTint.text = tintText() }.apply { progress = 100 }
        panel.add(cbAwb); panel.add(lbWb); panel.add(sbWb); panel.add(lbTint); panel.add(sbTint)

        btnCapture = Button(this).apply {
            text = "●"; textSize = 28f; setTextColor(Color.RED)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setOnClickListener {
                if (!ctl.videoMode) ctl.takePhoto()
                else if (ctl.recording) ctl.stopRecording() else ctl.startRecording()
            }
        }
        root.addView(btnCapture, FrameLayout.LayoutParams(dp(84), dp(84)))

        setContentView(root)
        applyLayout()
        applyVisibility()
    }

    /** Landscape = panel kanan, portrait = panel bawah. */
    private fun applyLayout() {
        val r = rotation()
        val portrait = r == Surface.ROTATION_0 || r == Surface.ROTATION_180
        val hpx = resources.displayMetrics.heightPixels
        val plp = if (portrait) FrameLayout.LayoutParams(-1, (hpx * 0.42f).toInt(), Gravity.BOTTOM)
        else FrameLayout.LayoutParams(dp(300), -1, Gravity.END)
        panelScroll.layoutParams = plp
        val clp = FrameLayout.LayoutParams(
            dp(84), dp(84),
            if (portrait) Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM else Gravity.START or Gravity.CENTER_VERTICAL
        )
        if (portrait) clp.bottomMargin = plp.height + dp(12) else clp.leftMargin = dp(24)
        btnCapture.layoutParams = clp
        tv.layoutParams = FrameLayout.LayoutParams(
            -1, -1, if (portrait) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        )
        tv.updateTransform(r)
    }

    private fun setPanelEnabled(en: Boolean) {
        listOf<View>(spCam, spFmt, spLook, spRaw, spSize, spFps, btnPhoto, btnVideo, cbGate).forEach { it.isEnabled = en }
    }

    private fun applyVisibility() {
        val video = ctl.videoMode
        val dng = video && ctl.videoFmt == VideoFmt.RAW && ctl.rawOut == RawOut.DNG
        spLook.visibility = if (!video) View.VISIBLE else View.GONE
        spRaw.visibility = if (video && ctl.videoFmt == VideoFmt.RAW) View.VISIBLE else View.GONE
        grpVideo.visibility = if (video) View.VISIBLE else View.GONE
        spSize.isEnabled = !dng && !ctl.recording
        cbGate.visibility = if (dng) View.GONE else View.VISIBLE
        val custom = if (video) ctl.videoFmt == VideoFmt.CUSTOM else ctl.photoLook == Look.CUSTOM
        btnProfile.visibility = if (custom) View.VISIBLE else View.GONE
        grpAuto.visibility = if (ctl.auto) View.VISIBLE else View.GONE
        grpManual.visibility = if (ctl.auto) View.GONE else View.VISIBLE
        btnPhoto.alpha = if (video) 0.5f else 1f
        btnVideo.alpha = if (video) 1f else 0.5f
        btnAuto.alpha = if (ctl.auto) 1f else 0.5f
        btnManual.alpha = if (ctl.auto) 0.5f else 1f
    }

    private fun setMode(video: Boolean) {
        if (ctl.recording || ctl.cameraId.isEmpty()) return
        ctl.videoMode = video
        refreshFormats(); refreshVideoOpts(); refreshShutter(); applyVisibility(); updateEvLabel()
    }

    // ------------------------------------------------------------ sinkronisasi UI <-> controller
    private fun refreshAll() {
        val cams = ctl.backCameras()
        camIds = cams.map { it.first }
        setItems(spCam, cams.map { it.second }, camIds.indexOf(ctl.cameraId))
        refreshFormats(); refreshVideoOpts()

        val r = ctl.evRange
        sbEv.max = (r.upper - r.lower).coerceAtLeast(1)
        sbEv.progress = ctl.ev - r.lower
        sbEv.isEnabled = r.upper > r.lower
        updateEvLabel()

        sbIso.progress = isoToProg(ctl.iso)
        lbIso.text = "ISO ${ctl.iso}"
        refreshShutter()

        val ap = ctl.apertures
        if (ap.size > 1) {
            sbAp.visibility = View.VISIBLE
            sbAp.max = ap.size - 1
            sbAp.progress = ap.indexOfFirst { it == ctl.aperture }.coerceAtLeast(0)
            lbAp.text = String.format(Locale.US, "Aperture f/%.1f", ctl.aperture)
        } else {
            sbAp.visibility = View.GONE
            lbAp.text = if (ap.size == 1) String.format(Locale.US, "Aperture f/%.1f (tetap, lensa ini)", ap[0])
            else "Aperture: tidak tersedia"
        }
        sbFocus.progress = ctl.focusPos
        lbFocus.text = if (ctl.focusPos == 0) "Fokus: AF" else "Fokus: MF ${ctl.focusPos}%"

        sbWb.progress = ((ctl.kelvin - 2000) / 100).coerceIn(0, 80)
        sbTint.progress = (ctl.tint + 100).coerceIn(0, 200)
        if (cbAwb.isChecked != ctl.awbAuto) cbAwb.isChecked = ctl.awbAuto
        lbWb.text = if (ctl.wbManualSupported) "WB ${ctl.kelvin}K" else "WB manual tidak didukung perangkat ini"
        lbTint.text = tintText()
        updateWbEnabled()

        cbOis.isEnabled = ctl.oisSupported
        cbOis.text = if (ctl.oisSupported) "OIS bawaan HP" else "OIS tidak tersedia di kamera ini"
        if (cbOis.isChecked != ctl.ois) cbOis.isChecked = ctl.ois
        applyVisibility()
    }

    private fun tintText(): String {
        val t = ctl.tint
        return String.format(Locale.US, "Tint %+d", t) + if (t > 0) " (magenta)" else if (t < 0) " (hijau)" else ""
    }

    private fun updateWbEnabled() {
        val ok = ctl.cameraId.isNotEmpty() && ctl.wbManualSupported
        cbAwb.isEnabled = ok
        sbWb.isEnabled = ok && !cbAwb.isChecked
        sbTint.isEnabled = ok && !cbAwb.isChecked
    }

    private fun refreshFormats() {
        if (ctl.videoMode) {
            videoFmts = ctl.availableVideoFmts()
            setItems(spFmt, videoFmts.map { videoLabel(it) }, videoFmts.indexOf(ctl.videoFmt))
        } else {
            photoFmts = ctl.availablePhotoFmts()
            setItems(spFmt, photoFmts.map { photoLabel(it) }, photoFmts.indexOf(ctl.photoFmt))
        }
        looks = ctl.availableLooks()
        setItems(spLook, looks.map { lookLabel(it) }, looks.indexOf(ctl.photoLook))
        rawOuts = ctl.availableRawOuts()
        setItems(spRaw, rawOuts.map { rawOutLabel(it) }, rawOuts.indexOf(ctl.rawOut))
    }

    private fun refreshVideoOpts() {
        ctl.normalizeFps()
        sizes = ctl.videoSizes()
        setItems(spSize, sizes.map { "${it.width}x${it.height}" }, sizes.indexOf(ctl.videoSize))
        fpsList = ctl.fpsOptions(ctl.videoSize)
        setItems(spFps, fpsList.map { "$it fps" }, fpsList.indexOf(ctl.fps))
        val gate = if (ctl.videoMode) ctl.openGate else ctl.photoOpenGate
        cbGate.isEnabled = (!ctl.videoMode || ctl.openGateAvailable) && !ctl.recording
        if (cbGate.isChecked != gate) cbGate.isChecked = gate
    }

    private fun refreshShutter() {
        val mn = ctl.shutterMin.toDouble()
        val mx = maxOf(ctl.shutterMax.toDouble(), mn)
        val ns = ctl.shutterNs.coerceIn(ctl.shutterMin, mx.toLong())
        if (ns != ctl.shutterNs) ctl.shutterNs = ns
        sbShutter.progress = if (mx <= mn) 0 else (ln(ns / mn) / ln(mx / mn) * 1000).roundToInt().coerceIn(0, 1000)
        lbShutter.text = "Shutter " + fmtShutter(ns)
    }

    private fun updateEvLabel() {
        val r = ctl.evRange
        val bias = ctl.evBiasNow
        lbEv.text = if (r.upper <= r.lower) "Target eksposur: tidak didukung"
        else String.format(Locale.US, "Target eksposur: %+.1f EV", ctl.ev * ctl.evStepF) +
                (if (bias != 0f) String.format(Locale.US, "  (+ bias profil %+.1f)", bias) else "")
    }

    private fun videoLabel(f: VideoFmt) = when (f) {
        VideoFmt.DEFAULT -> "Default (profil bawaan HP)"
        VideoFmt.RAW -> "RAW (minim olahan, DR tinggi)"
        VideoFmt.PLAIN -> "SOOC Plain (DR tinggi)"
        VideoFmt.CUSTOM -> "SOOC Custom (profil sendiri)"
    }

    private fun rawOutLabel(o: RawOut) = when (o) {
        RawOut.VIDEO -> "Output: video HEVC (ukuran kecil)"
        RawOut.DNG -> "Output: urutan DNG (RAW asli, sangat besar)"
    }

    private fun lookLabel(l: Look) = when (l) {
        Look.DEFAULT -> "Warna JPEG: bawaan HP"
        Look.PLAIN -> "Warna JPEG: Plain (DR tinggi)"
        Look.CUSTOM -> "Warna JPEG: kustom (profil sendiri)"
    }

    private fun photoLabel(f: PhotoFmt) = when (f) {
        PhotoFmt.JPEG -> "JPEG"
        PhotoFmt.RAW -> "RAW (DNG)"
        PhotoFmt.RAW_JPEG -> "RAW + JPEG"
    }

    // ------------------------------------------------------------ pemetaan slider (logaritmik)
    private fun progToIso(p: Int): Int {
        val r = ctl.isoRange
        return (r.lower * (r.upper.toDouble() / r.lower).pow(p / 1000.0)).roundToInt().coerceIn(r.lower, r.upper)
    }

    private fun isoToProg(iso: Int): Int {
        val r = ctl.isoRange
        if (r.upper <= r.lower) return 0
        return (ln(iso.toDouble() / r.lower) / ln(r.upper.toDouble() / r.lower) * 1000).roundToInt().coerceIn(0, 1000)
    }

    private fun progToShutter(p: Int): Long {
        val mn = ctl.shutterMin.toDouble()
        val mx = maxOf(ctl.shutterMax.toDouble(), mn)
        return (mn * (mx / mn).pow(p / 1000.0)).toLong().coerceIn(ctl.shutterMin, mx.toLong())
    }

    private fun fmtShutter(ns: Long) =
        if (ns >= 500_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"

    // ------------------------------------------------------------ editor profil warna (layar penuh)
    private val gradeNames = listOf("Kontras", "Black", "Shadow", "Mid", "Highlight", "White")

    private fun gradeGet(i: Int) = when (i) {
        0 -> editing.contrast; 1 -> editing.black; 2 -> editing.shadow
        3 -> editing.mid; 4 -> editing.highlight; else -> editing.white
    }

    private fun gradeSet(i: Int, v: Float) {
        when (i) {
            0 -> editing.contrast = v; 1 -> editing.black = v; 2 -> editing.shadow = v
            3 -> editing.mid = v; 4 -> editing.highlight = v; else -> editing.white = v
        }
    }

    private fun savedNames(): List<String> =
        prefs.all.keys.filter { it.startsWith("p_") }.map { it.removePrefix("p_") }.sorted()

    private fun showProfileDialog() {
        editing = ctl.profile
        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val portrait = rotation().let { it == Surface.ROTATION_0 || it == Surface.ROTATION_180 }
        val dm = resources.displayMetrics

        val rootV = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF000000.toInt())
        }
        val curve = CurveEditorView(this).apply { profile = editing }
        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(ctrl) }
        if (portrait) {
            rootV.addView(curve, LinearLayout.LayoutParams(-1, minOf(dm.widthPixels, (dm.heightPixels * 0.55f).toInt())))
            rootV.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            rootV.addView(curve, LinearLayout.LayoutParams(0, -1, 1.25f))
            rootV.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }

        val titleTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        ctrl.add(titleTv)

        val syncs = mutableListOf<() -> Unit>()
        fun commit() {
            ctl.profile = editing
            prefs.edit().putString("_last", editing.toJson()).apply()
        }
        fun slider(max: Int, get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String): SeekBar {
            val lb = label("")
            val sb = seek(max) { p -> set(p); lb.text = fmt(p); curve.invalidate(); commit() }
            syncs.add { sb.progress = get(); lb.text = fmt(get()) }
            ctrl.add(lb); ctrl.add(sb)
            return sb
        }
        fun title() {
            titleTv.text = "Profil: ${editing.name}" + if (editing.rgb != null) "  [per-kanal dari LUT]" else ""
        }
        fun loadLocal(p: ColorProfile) {
            editing = p
            curve.profile = p
            title()
            syncs.forEach { it() }
            commit()
        }
        curve.onChanged = { commit() }
        editorLoad = { p -> loadLocal(p) }

        slider(200, { (editing.contrast * 100).roundToInt() + 100 },
            { editing.contrast = (it - 100) / 100f }, { String.format(Locale.US, "Kontras %+d", it - 100) })
        val sbSat = slider(200, { (editing.saturation * 100).roundToInt().coerceIn(0, 200) },
            { editing.saturation = it / 100f },
            { "Saturasi $it%" + if (!ctl.satSupported) " (tidak didukung perangkat ini)" else "" })
        sbSat.isEnabled = ctl.satSupported
        val sharpNames = arrayOf("Sharpness: mati", "Sharpness: standar", "Sharpness: tinggi")
        slider(2, { editing.sharpness }, { editing.sharpness = it }, { sharpNames[it.coerceIn(0, 2)] })
        slider(30, { ((editing.evBias + 2f) * 10).roundToInt().coerceIn(0, 30) },
            { editing.evBias = -2f + it / 10f }, { String.format(Locale.US, "Bias eksposur auto %+.1f EV", -2f + it / 10f) })

        ctrl.add(label("— Tonal (lanjutan) —").apply { alpha = 0.7f })
        for (i in gradeNames.indices) {
            slider(200, { (gradeGet(i) * 100).roundToInt() + 100 },
                { gradeSet(i, (it - 100) / 100f) },
                { String.format(Locale.US, "%s %+d", gradeNames[i], it - 100) })
        }

        ctrl.add(row(btn("Reset grading") {
            editing.resetGrade(); editing.contrast = 0f
            syncs.forEach { it() }; curve.invalidate(); commit()
        }, btn("Kembali ke Plain HDR") { loadLocal(ColorProfile.plainHdr()) }))
        ctrl.add(row(btn("Muat…") { pickProfile() }, btn("Simpan") { saveProfile() }))
        ctrl.add(row(btn("Impor (.json / .cube)") { importProfile() }, btn("Ekspor") { exportProfile() }))
        ctrl.add(row(btn("Hapus tersimpan") { deleteProfile() }, btn("Ambil ulang WB") { ctl.resampleWb() }))

        val cbGamma = CheckBox(this).apply {
            text = "Kalibrasi: input kurva = gamma (aktifkan bila hasil terlalu gelap / kontras)"
            setTextColor(Color.WHITE); textSize = 12f
            isChecked = ctl.curveGammaInput
            setOnCheckedChangeListener { _, c ->
                ctl.curveGammaInput = c
                prefs.edit().putBoolean("_gamma", c).apply()
            }
        }
        ctrl.add(cbGamma)
        ctrl.add(label(
            "Seret titik untuk mengubah kurva, ketuk area kosong untuk menambah titik, ketuk 2x titik untuk menghapus. " +
            "Garis putih redup = kurva dasar, hijau = hasil setelah kontras/tonal. Kurva bekerja pada data linear sensor. " +
            "Sharpness memakai mode edge kamera (mati / standar / tinggi), bukan skala kontinu."
        ).apply { textSize = 11f; alpha = 0.7f })
        ctrl.add(btn("Tutup") { dlg.dismiss() })

        title()
        syncs.forEach { it() }
        dlg.setContentView(rootV)
        dlg.setOnDismissListener { editorLoad = null }
        dlg.show()
    }

    private fun pickProfile() {
        val presets = ColorProfile.presets()
        val saved = savedNames()
        val names = presets.map { it.name } + saved.map { "★ $it" }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Muat profil")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < presets.size) editorLoad?.invoke(presets[i].copy())
                else {
                    val name = saved[i - presets.size]
                    val p = prefs.getString("p_$name", null)?.let { ColorProfile.fromJson(it) }
                    if (p != null) editorLoad?.invoke(p) else toast("Profil rusak")
                }
            }.show()
    }

    private fun saveProfile() {
        val et = EditText(this).apply { setText(editing.name); setSingleLine() }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("Simpan profil sebagai").setView(et)
            .setPositiveButton("Simpan") { _, _ ->
                val n = et.text.toString().trim().ifEmpty { "Profil" }
                editing.name = n
                prefs.edit().putString("p_$n", editing.toJson()).putString("_last", editing.toJson()).apply()
                toast("Tersimpan: $n")
            }.setNegativeButton("Batal", null).show()
    }

    private fun deleteProfile() {
        if (prefs.contains("p_${editing.name}")) {
            prefs.edit().remove("p_${editing.name}").apply()
            toast("Dihapus: ${editing.name}")
        } else toast("Profil ini belum tersimpan")
    }

    private fun importProfile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_IMPORT)
    }

    private fun exportProfile() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${editing.name}.rawcam.json")
        }, REQ_EXPORT)
    }

    private fun askCubeInput(name: String, text: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
            .setTitle("LUT ini dibuat untuk input apa?")
            .setItems(arrayOf("Linear (cahaya sensor)", "S-Log3", "Rec.709 / gamma")) { _, i ->
                val p = ColorProfile.fromCube(name, text, i)
                if (p == null) toastLong("File .cube tidak valid / tidak lengkap")
                else { editorLoad?.invoke(p); toastLong(p.note ?: "LUT dimuat") }
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == REQ_EXPORT) {
                contentResolver.openOutputStream(uri)?.use { it.write(editing.toJson().toByteArray()) }
                toast("Profil diekspor")
            } else if (requestCode == REQ_IMPORT) {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val base = (uri.lastPathSegment ?: "Impor").substringAfterLast('/').substringBeforeLast('.')
                if (text.trimStart().startsWith("{")) {
                    val p = ColorProfile.fromJson(text)
                    if (p == null) toastLong("JSON profil tidak valid")
                    else { editorLoad?.invoke(p); toast("Profil dimuat: ${p.name}") }
                } else askCubeInput(base, text)
            }
        } catch (e: Exception) {
            toastLong("Gagal: ${e.message}")
        }
    }
}
