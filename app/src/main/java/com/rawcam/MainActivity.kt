package com.rawcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var cam: CamController
    private lateinit var resSp: Spinner
    private lateinit var fpsSp: Spinner
    private lateinit var outSp: Spinner
    private lateinit var profBox: LinearLayout
    private lateinit var manBox: LinearLayout
    private lateinit var evBox: LinearLayout
    private lateinit var shutterBtn: Button
    private var sizes = listOf<android.util.Size>()
    private var fpsList = listOf<Int>()
    private var refreshing = false
    private val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun spin(items: List<String>, sel: Int, cb: (Int) -> Unit) = Spinner(this).also { s ->
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(sel, false)
        s.post { s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { if (!refreshing) cb(i) }
            override fun onNothingSelected(p: AdapterView<*>?) {} } }
    }
    private fun sw(label: String, init: Boolean, cb: (Boolean) -> Unit) =
        Switch(this).apply { text = label; isChecked = init; setOnCheckedChangeListener { _, c -> cb(c) } }
    private fun seek(label: String, init: Int, fmt: (Int) -> String, cb: (Int) -> Unit): LinearLayout {
        val t = TextView(this).apply { text = "$label: ${fmt(init)}" }
        val s = SeekBar(this).apply {
            max = 100; progress = init
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { t.text = "$label: ${fmt(p)}"; if (u) cb(p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(t); addView(s) }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tv = AutoFitTextureView(this)
        cam = CamController(this, tv)
        cam.onRecState = { shutterBtn.text = if (it) "■ STOP" else "● REC" }

        val preview = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(tv, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)) }

        val modeSp = spin(listOf("Foto", "Video"), 0) { cam.video = it == 1; outSp.visibility = if (cam.video) View.GONE else View.VISIBLE
            shutterBtn.text = if (cam.video) "● REC" else "◉"; refreshLists(); cam.restart() }
        val typeSp = spin(listOf("Default kamera", "SOOC / SOOS (bypass)"), 0) { cam.soo = it == 1
            cam.ev = if (cam.soo) -1f else 0f; (evBox.getChildAt(1) as SeekBar).progress = (50 + cam.ev / 3f * 50).toInt()
            profBox.visibility = if (cam.soo) View.VISIBLE else View.GONE; cam.restart() }
        outSp = spin(listOf("JPG", "RAW", "RAW + JPG"), 0) { cam.photoOut = listOf("JPG", "RAW", "RAW+JPG")[it]; cam.restart() }
        resSp = spin(listOf(), 0) { if (it < sizes.size) { cam.size = sizes[it]; cam.restart() } }
        fpsSp = spin(listOf(), 0) { if (it < fpsList.size) { cam.fpsValue = fpsList[it]; cam.restart() } }

        val gate = sw("Open gate (sensor penuh 4:3)", false) { cam.openGate = it; refreshLists(); cam.restart() }
        val ois = sw("OIS", true) { cam.ois = it; cam.updateRepeating() }.also { it.visibility = if (cam.hasOis()) View.VISIBLE else View.GONE }

        // profil
        val profSp = spin(listOf("Profil Default", "Profil Custom"), 0) { cam.profile.custom = it == 1; cam.updateRepeating() }
        val editBtn = Button(this).apply { text = "Edit profil"; setOnClickListener { cam.profile.custom = true; profSp.setSelection(1); showEditor() } }
        profBox = LinearLayout(this).apply { visibility = View.GONE; addView(profSp, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(editBtn, LinearLayout.LayoutParams(0, WRAP, 1f)) }

        // manual
        val ir = cam.isoRange(); val fmax = cam.minFocus()
        manBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun isoOf(p: Int) = (ir.lower * (ir.upper.toDouble() / ir.lower).pow(p / 100.0)).roundToInt()
        fun shutOf(p: Int) = (1.0 / 8000) * 4000.0.pow(p / 100.0)
        manBox.addView(seek("ISO", 0, { isoOf(it).toString() }) { cam.iso = isoOf(it); cam.updateRepeating() })
        manBox.addView(seek("Shutter", 50, { "1/${(1 / shutOf(it)).roundToInt()}" }) { cam.shutter = shutOf(it); cam.updateRepeating() })
        manBox.addView(seek("Fokus (0=jauh)", 0, { "%.1f dpt".format(fmax * it / 100f) }) { cam.focus = fmax * it / 100f; cam.updateRepeating() })
        manBox.addView(seek("WB", 53, { "${2500 + it * 65}K" }) { cam.kelvin = 2500 + it * 65; cam.updateRepeating() })
        val ap = cam.apertures()
        if (ap.size > 1) manBox.addView(spin(ap.map { "f/$it" }, 0) { cam.aperture = ap[it]; cam.updateRepeating() })
        evBox = seek("Target exposure", 50, { "%+.1f EV".format((it - 50) / 50.0 * 3) }) { cam.ev = (it - 50) / 50f * 3f; cam.updateRepeating() }
        val auto = sw("Auto exposure & WB", true) { cam.auto = it; manBox.visibility = if (it) View.GONE else View.VISIBLE
            evBox.visibility = if (it) View.VISIBLE else View.GONE; cam.updateRepeating() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(row(modeSp, typeSp)); addView(outSp); addView(row(resSp, fpsSp))
            addView(gate); addView(ois); addView(auto); addView(evBox); addView(manBox); addView(profBox)
        }
        shutterBtn = Button(this).apply { text = "◉"; textSize = 24f
            setOnClickListener { if (cam.video) cam.toggleRecord() else cam.shoot() } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            addView(preview, LinearLayout.LayoutParams(MATCH, 0, 5f))
            addView(ScrollView(this@MainActivity).apply { addView(panel) }, LinearLayout.LayoutParams(MATCH, 0, 3f))
            addView(shutterBtn, LinearLayout.LayoutParams(MATCH, dp(64)))
        }
        setContentView(root)
        refreshLists()
    }

    private fun refreshLists() {
        refreshing = true
        sizes = cam.sizes(); cam.size = sizes.first()
        resSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizes.map { "${it.width}x${it.height}" })
        fpsList = cam.fpsOptions(); cam.fpsValue = if (30 in fpsList) 30 else fpsList.last()
        fpsSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsList.map { "$it fps" })
        fpsSp.setSelection(fpsList.indexOf(cam.fpsValue))
        resSp.post { refreshing = false }
    }

    private fun showEditor() {
        val p = cam.profile
        val side = resources.displayMetrics.widthPixels - dp(64)
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val cv = CurveView(this, p) { cam.updateRepeating() }
        ll.addView(cv, LinearLayout.LayoutParams(side, side))
        ll.addView(Button(this).apply { text = "Reset kurva (linear)"; setOnClickListener { p.resetCurve(); cv.invalidate(); cam.updateRepeating() } })
        ll.addView(Button(this).apply { text = "Load profil (JSON)"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 77) } })
        ll.addView(Button(this).apply { text = "Simpan profil (JSON)"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "profil_warna.json") }, 78) } })
        ll.addView(seek("Contrast", ((p.contrast - 0.5f) * 100).toInt(), { "%.2f".format(0.5 + it / 100.0) }) { p.contrast = 0.5f + it / 100f; cam.updateRepeating() })
        ll.addView(seek("Saturasi", (p.saturation * 50).toInt(), { "%.2f".format(it / 50.0) }) { p.saturation = it / 50f; cam.updateRepeating() })
        ll.addView(seek("Tint (hijau–magenta)", ((p.tint + 1) * 50).toInt(), { "%.2f".format((it - 50) / 50.0) }) { p.tint = (it - 50) / 50f; cam.updateRepeating() })
        ll.addView(seek("Sharpness", (p.sharpness * 100).toInt(), { "%.2f".format(it / 100.0) }) { p.sharpness = it / 100f; cam.updateRepeating() })
        AlertDialog.Builder(this).setTitle("Editor profil warna").setView(ScrollView(this).apply { addView(ll) }).setPositiveButton("OK", null).show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 78 && res == RESULT_OK && data?.data != null) {
            try { contentResolver.openOutputStream(data.data!!)!!.use { it.write(cam.profile.save().toByteArray()) }
                Toast.makeText(this, "Profil tersimpan", Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { Toast.makeText(this, "Gagal simpan: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        if (req == 77 && res == RESULT_OK && data?.data != null) {
            try { cam.profile.load(contentResolver.openInputStream(data.data!!)!!.bufferedReader().readText()); cam.updateRepeating(); showEditor() }
            catch (e: Exception) { Toast.makeText(this, "Profil tidak valid: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun perms() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    override fun onResume() {
        super.onResume()
        if (perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) cam.start() else requestPermissions(perms().toTypedArray(), 1)
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { if (r.all { it == PackageManager.PERMISSION_GRANTED }) cam.start() }
    override fun onPause() { cam.stop(); super.onPause() }
}
