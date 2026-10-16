package com.mathai.studio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : Activity() {

    companion object {
        const val REQ_PHOTO = 1
        const val REQ_EXPORT_MODEL = 2
        const val REQ_EXPORT_DATA = 3
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var store: DataStore
    private lateinit var modelFile: File
    private var net: Mlp? = null
    private var labels: List<String> = emptyList()
    private val stop = AtomicBoolean(false)
    @Volatile private var busy = false

    // tab Baca
    private lateinit var draw: DrawingView
    private lateinit var overlay: ImageView
    private lateinit var detail: TextView
    private lateinit var fix: EditText
    private lateinit var status: TextView
    private var curPx: List<ByteArray> = emptyList()

    // tab Latih
    private lateinit var charsEt: EditText
    private lateinit var perClassEt: EditText
    private lateinit var epochsEt: EditText
    private lateinit var progress: ProgressBar
    private lateinit var logTv: TextView

    // tab Data
    private lateinit var statsTv: TextView
    private lateinit var modelInfo: TextView

    private lateinit var pages: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DataStore(File(filesDir, "dataset.bin"))
        store.load()
        modelFile = File(filesDir, "model.bin")
        if (modelFile.exists()) {
            try {
                modelFile.inputStream().use {
                    val r = ModelIO.read(it)
                    net = r.first
                    labels = r.second
                }
            } catch (e: Exception) {
                net = null
            }
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val frame = FrameLayout(this)
        val p1 = page { buildRead(it) }
        val p2 = page { buildTrain(it) }
        val p3 = page { buildData(it) }
        pages = listOf(p1, p2, p3)
        for (p in pages) frame.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        listOf("Baca", "Latih", "Data").forEachIndexed { i, name ->
            val b = Button(this)
            b.text = name
            b.isAllCaps = false
            b.setOnClickListener { showPage(i) }
            bar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        showPage(0)
        refreshStats()
        updateModelInfo()
    }

    override fun onDestroy() {
        stop.set(true)
        super.onDestroy()
    }

    // ---------- helper UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page(build: (LinearLayout) -> Unit): ScrollView {
        val sv = ScrollView(this)
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(12), dp(12), dp(12), dp(12))
        build(ll)
        sv.addView(ll, ViewGroup.LayoutParams(MATCH, WRAP))
        return sv
    }

    private fun tv(t: String, size: Float = 14f): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setPadding(0, dp(6), 0, dp(6))
        return v
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg v: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (x in v) r.addView(x, LinearLayout.LayoutParams(0, WRAP, 1f))
        return r
    }

    private fun num(def: String): EditText {
        val e = EditText(this)
        e.setText(def)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setSingleLine(true)
        return e
    }

    private fun showPage(i: Int) {
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun setStatus(m: String) { status.text = m }

    // ---------- tab Baca ----------

    private fun buildRead(ll: LinearLayout) {
        ll.addView(tv("Tulis satu baris, tiap simbol terpisah jelas dan cukup besar (belum mendukung pecahan/pangkat bertumpuk). Atau impor foto. Lalu tekan Baca.", 13f))
        val wrap = FrameLayout(this)
        wrap.setBackgroundColor(Color.GRAY)
        wrap.setPadding(dp(1), dp(1), dp(1), dp(1))
        draw = DrawingView(this)
        wrap.addView(draw, FrameLayout.LayoutParams(MATCH, dp(220)))
        ll.addView(wrap, LinearLayout.LayoutParams(MATCH, WRAP))

        ll.addView(row(
            btn("Hapus") {
                draw.clear()
                overlay.setImageDrawable(null)
                detail.text = ""
                fix.setText("")
                curPx = emptyList()
                setStatus("")
            },
            btn("Impor foto") { pickPhoto() },
            btn("Baca") {
                val s = draw.snapshot()
                if (s == null || !draw.hasInk) setStatus("Kanvas kosong.") else doRead(s)
            }
        ))

        overlay = ImageView(this)
        overlay.adjustViewBounds = true
        overlay.scaleType = ImageView.ScaleType.FIT_CENTER
        ll.addView(overlay, LinearLayout.LayoutParams(MATCH, WRAP))

        detail = tv("", 12f)
        detail.typeface = Typeface.MONOSPACE
        ll.addView(detail)

        ll.addView(tv("Hasil bacaan (koreksi di sini; × untuk kali, ÷ untuk bagi, * otomatis jadi ×):", 13f))
        fix = EditText(this)
        fix.setSingleLine(true)
        fix.textSize = 22f
        fix.hint = "hasil bacaan"
        ll.addView(fix, LinearLayout.LayoutParams(MATCH, WRAP))
        ll.addView(btn("Simpan sebagai data latih (benar / sudah dikoreksi)") { saveCorrection() })
        status = tv("", 13f)
        ll.addView(status)
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Pilih foto soal"), REQ_PHOTO)
    }

    private fun doRead(src: Bitmap) {
        val ink = toInk(src)
        val boxes = segment(ink)
        if (boxes.isEmpty()) {
            setStatus("Tidak ada tulisan terdeteksi.")
            return
        }
        val px = boxes.map { normalizeBox(ink, it) }
        curPx = px
        val sb = StringBuilder()
        val text = StringBuilder()
        val n = net
        px.forEachIndexed { i, p ->
            if (n == null) {
                sb.append("${i + 1}: ?\n")
            } else {
                val x = FloatArray(DIM)
                fillInput(p, x, 0, 0, null)
                val pr = n.predict(x)
                val order = pr.indices.sortedByDescending { pr[it] }
                val top = order[0]
                val c1 = pr[top]
                text.append(labels[top])
                val warn = if (c1 < 0.7f) " (ragu)" else ""
                val alt = if (order.size > 1) "  | ${labels[order[1]]} ${(pr[order[1]] * 100).toInt()}%" else ""
                sb.append("${i + 1}: ${labels[top]} ${(c1 * 100).toInt()}%$warn$alt\n")
            }
        }

        // gambar bantu: kotak merah + nomor tiap simbol
        val base = Bitmap.createScaledBitmap(src, ink.w, ink.h, true).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.RED
        paint.strokeWidth = max(2f, ink.h / 150f)
        paint.textSize = max(16f, ink.h / 12f)
        boxes.forEachIndexed { i, b ->
            paint.style = Paint.Style.STROKE
            c.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), paint)
            paint.style = Paint.Style.FILL
            val ty = if (b.t > paint.textSize) b.t - 4f else b.b + paint.textSize
            c.drawText("${i + 1}", b.l.toFloat(), ty, paint)
        }
        overlay.setImageBitmap(base)
        detail.text = sb.toString()
        fix.setText(text.toString())
        if (n == null) {
            setStatus("Model belum dilatih. Ketik label tiap simbol berurutan kiri ke kanan (${boxes.size} simbol), lalu simpan.")
        } else {
            setStatus("Terbaca ${boxes.size} simbol. Cek kotak merah, koreksi jika perlu, lalu simpan.")
        }
    }

    private fun saveCorrection() {
        if (curPx.isEmpty()) {
            setStatus("Tekan Baca dulu.")
            return
        }
        val chars = splitChars(fix.text.toString().replace('*', '×'))
        if (chars.size != curPx.size) {
            setStatus("Jumlah simbol di teks (${chars.size}) tidak sama dengan jumlah kotak merah (${curPx.size}).")
            return
        }
        store.add(chars.indices.map { Sample(chars[it], curPx[it]) })
        curPx = emptyList()
        setStatus("Tersimpan ${chars.size} sampel. Total ${store.samples.size}. Latih ulang di tab Latih kalau data sudah cukup.")
        refreshStats()
    }

    // ---------- tab Latih ----------

    private fun buildTrain(ll: LinearLayout) {
        ll.addView(tv("1) Generate data cetak dari font untuk mulai.\n2) Latih.\n3) Tambah tulisan tanganmu dari tab Baca, lalu latih ulang.", 13f))
        ll.addView(tv("Karakter kelas cetak:", 13f))
        charsEt = EditText(this)
        charsEt.setText(Synth.DEFAULT_CHARS)
        charsEt.setSingleLine(true)
        ll.addView(charsEt, LinearLayout.LayoutParams(MATCH, WRAP))
        ll.addView(tv("Sampel per kelas:", 13f))
        perClassEt = num("60")
        ll.addView(perClassEt, LinearLayout.LayoutParams(MATCH, WRAP))
        ll.addView(btn("Generate data cetak") { generate() })
        ll.addView(tv("Epoch:", 13f))
        epochsEt = num("30")
        ll.addView(epochsEt, LinearLayout.LayoutParams(MATCH, WRAP))
        ll.addView(row(btn("Mulai latih") { startTrain() }, btn("Stop") { stop.set(true) }))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        ll.addView(progress, LinearLayout.LayoutParams(MATCH, WRAP))
        logTv = tv("", 12f)
        logTv.typeface = Typeface.MONOSPACE
        ll.addView(logTv)
    }

    private fun appendLog(m: String) {
        logTv.append(m + "\n")
        if (logTv.text.length > 20000) logTv.text = logTv.text.toString().takeLast(10000)
    }

    private fun generate() {
        if (busy) {
            appendLog("Sedang sibuk, tunggu selesai.")
            return
        }
        val chars = splitChars(charsEt.text.toString()).distinct()
        val per = (perClassEt.text.toString().toIntOrNull() ?: 60).coerceIn(5, 500)
        if (chars.isEmpty()) {
            appendLog("Isi karakter kelas dulu.")
            return
        }
        busy = true
        appendLog("Generate ${chars.size} kelas x $per sampel ...")
        Thread {
            val list = try {
                Synth.generate(chars, per, Random(System.nanoTime())) { d, t ->
                    runOnUiThread {
                        progress.max = t
                        progress.progress = d
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { appendLog("Error: ${t.message}") }
                emptyList()
            }
            store.add(list)
            runOnUiThread {
                appendLog("Selesai: ${list.size} sampel ditambahkan.")
                busy = false
                refreshStats()
            }
        }.start()
    }

    private fun startTrain() {
        if (busy) {
            appendLog("Sedang sibuk, tunggu selesai.")
            return
        }
        val data = store.snapshot()
        if (data.isEmpty()) {
            appendLog("Belum ada data. Generate data cetak atau simpan koreksi dulu.")
            return
        }
        val ep = (epochsEt.text.toString().toIntOrNull() ?: 30).coerceIn(1, 300)
        stop.set(false)
        busy = true
        val cfg = TrainConfig(epochs = ep)
        Thread {
            val res = try {
                Trainer.train(
                    data, cfg, stop,
                    { m -> runOnUiThread { appendLog(m) } },
                    { e, n -> runOnUiThread { progress.max = n; progress.progress = e } }
                )
            } catch (t: Throwable) {
                runOnUiThread { appendLog("Error: ${t.message}") }
                null
            }
            if (res != null) {
                try {
                    modelFile.outputStream().use { ModelIO.write(res.net, res.labels, it) }
                } catch (e: Exception) {
                    runOnUiThread { appendLog("Gagal menyimpan model: ${e.message}") }
                }
            }
            runOnUiThread {
                if (res != null) {
                    net = res.net
                    labels = res.labels
                    appendLog("Model aktif dan tersimpan. Ekspor dari tab Data.")
                    updateModelInfo()
                }
                busy = false
            }
        }.start()
    }

    // ---------- tab Data ----------

    private fun buildData(ll: LinearLayout) {
        statsTv = tv("", 13f)
        ll.addView(statsTv)
        modelInfo = tv("", 13f)
        ll.addView(modelInfo)
        ll.addView(btn("Ekspor model.bin (untuk app final)") {
            if (net == null) toast("Latih model dulu.") else exportFile(REQ_EXPORT_MODEL, "model.bin", "application/octet-stream")
        })
        ll.addView(btn("Ekspor dataset.zip (untuk training di PC)") {
            if (store.samples.isEmpty()) toast("Belum ada data.") else exportFile(REQ_EXPORT_DATA, "dataset.zip", "application/zip")
        })
        ll.addView(btn("Hapus satu kelas") { askDeleteClass() })
        ll.addView(btn("Hapus semua data") { confirmClearAll() })
    }

    private fun refreshStats() {
        val c = store.counts()
        val sb = StringBuilder("Total sampel: ${store.samples.size} | kelas: ${c.size}\n")
        sb.append(c.entries.joinToString("  ") { "${it.key}:${it.value}" })
        val low = c.filter { it.value < 20 }.keys
        if (low.isNotEmpty()) sb.append("\nData sedikit (<20): ${low.joinToString(" ")}")
        statsTv.text = sb.toString()
    }

    private fun updateModelInfo() {
        val n = net
        modelInfo.text = if (n == null) "Belum ada model (latih dulu)."
        else "Model aktif: ${labels.size} kelas (${labels.joinToString("")})"
    }

    private fun exportFile(req: Int, name: String, mime: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    private fun askDeleteClass() {
        val et = EditText(this)
        et.setSingleLine(true)
        et.hint = "satu simbol, mis. x"
        AlertDialog.Builder(this)
            .setTitle("Hapus semua sampel kelas")
            .setView(et)
            .setPositiveButton("Hapus") { _, _ ->
                val l = splitChars(et.text.toString().replace('*', '×')).firstOrNull()
                if (l != null) {
                    val n = store.removeLabel(l)
                    toast("$n sampel dihapus")
                    refreshStats()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Hapus semua data latih?")
            .setMessage("Model yang sudah dilatih tidak ikut terhapus.")
            .setPositiveButton("Hapus") { _, _ ->
                store.clear()
                refreshStats()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- hasil activity ----------

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PHOTO -> {
                val bmp = decodeSampled(uri, 1600)
                if (bmp == null) {
                    setStatus("Gagal membuka foto.")
                } else {
                    draw.clear()
                    doRead(bmp)
                }
            }
            REQ_EXPORT_MODEL -> {
                val n = net
                try {
                    if (n != null) contentResolver.openOutputStream(uri)?.use { ModelIO.write(n, labels, it) }
                    toast("model.bin tersimpan")
                } catch (e: Exception) {
                    toast("Gagal: ${e.message}")
                }
            }
            REQ_EXPORT_DATA -> {
                Thread {
                    val msg = try {
                        contentResolver.openOutputStream(uri)?.use { store.exportZip(it) }
                        "dataset.zip tersimpan"
                    } catch (e: Exception) {
                        "Gagal: ${e.message}"
                    }
                    runOnUiThread { toast(msg) }
                }.start()
            }
        }
    }

    private fun decodeSampled(uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > maxDim) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            val rot = contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val deg = when (rot) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (bmp != null && deg != 0f) {
                val m = Matrix()
                m.postRotate(deg)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } else bmp
        } catch (e: Exception) {
            null
        }
    }
}
