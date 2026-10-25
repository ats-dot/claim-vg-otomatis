package id.pribadi.vgklaim

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private var tab = 0
    private var tvStatus: TextView? = null
    private var tvLog: TextView? = null
    private var tvGame: TextView? = null
    private var tvSvc: TextView? = null
    private var lastLog = ""

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            h.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        Matcher.reload(applicationContext)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(0, dp(28), 0, 0)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setPadding(dp(8), dp(6), dp(8), dp(8))
        val names = listOf("Beranda", "Log", "Setelan", "Templat")
        for (i in names.indices) {
            val b = Button(this)
            b.text = names[i]
            b.isAllCaps = false
            style(b, false)
            b.setOnClickListener { show(i) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            nav.addView(b, lp)
        }
        root.addView(nav)
        setContentView(root)
        show(0)
    }

    override fun onResume() {
        super.onResume()
        h.post(ticker)
        if (tab == 3) show(3)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
    }

    private fun style(b: Button, selected: Boolean) {
        val g = GradientDrawable()
        g.setColor(if (selected) Color.WHITE else Color.BLACK)
        g.setStroke(dp(1), Color.WHITE)
        g.cornerRadius = dp(14).toFloat()
        b.background = g
        b.setTextColor(if (selected) Color.BLACK else Color.WHITE)
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        style(b, false)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun tv(t: String, size: Float = 14f, bold: Boolean = false, gray: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(if (gray) Color.parseColor("#9A9A9A") else Color.WHITE)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun card(vararg views: View): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        val g = GradientDrawable()
        g.setColor(Color.BLACK)
        g.setStroke(dp(1), Color.WHITE)
        g.cornerRadius = dp(16).toFloat()
        c.background = g
        for (v in views) c.addView(v)
        return c
    }

    private fun edit(hint: String, value: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.GRAY)
        e.hint = hint
        e.setText(value)
        e.gravity = Gravity.CENTER
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER
        return e
    }

    private fun lp(top: Int = 8): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, dp(top), 0, 0)
        return p
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(16))
        sv.addView(col)
        return Pair(sv, col)
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit): Button {
        val b = Button(this)
        b.isAllCaps = false
        fun render() {
            b.text = label + ": " + (if (get()) "AKTIF" else "MATI")
            style(b, get())
        }
        render()
        b.setOnClickListener {
            set(!get())
            render()
        }
        return b
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun pct(v: Float): String = String.format(java.util.Locale.US, "%.1f", v * 100f)

    private fun show(i: Int) {
        tab = i
        tvStatus = null
        tvLog = null
        tvGame = null
        tvSvc = null
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> home()
                1 -> logTab()
                2 -> settings()
                else -> templates()
            }
        )
        tick()
    }

    private fun gameLabel(): String {
        val p = Prefs.pkg
        if (p.isEmpty()) return "Otomatis (cari Mobile Legends)"
        for ((pk, lb) in Engine.listApps(this, true)) if (pk == p) return lb + "  (" + pk + ")"
        return p
    }

    private fun home(): View {
        val (sv, col) = page()
        col.addView(tv("VG Klaim", 28f, true))
        col.addView(tv("Klaim otomatis, tanpa internet", 13f, false, true))

        val g = tv(gameLabel(), 16f, true)
        tvGame = g
        val gc = card(tv("Game", 12f, false, true), g, tv("Ketuk untuk memilih aplikasi lain", 12f, false, true))
        gc.setOnClickListener { pickGame() }
        col.addView(gc, lp(16))

        val st = tv("", 15f)
        tvStatus = st
        col.addView(card(tv("Status", 12f, false, true), st), lp())

        val cnt = edit("kosong = hitung otomatis", if (Prefs.serverCount > 0) Prefs.serverCount.toString() else "", true)
        val sc = card(
            tv("Jumlah server (kosongkan = hitung otomatis)", 12f, false, true),
            cnt
        )
        col.addView(sc, lp())
        col.addView(toggle("Lewati server di atas posisi sekarang", { Prefs.skipAbove }, { Prefs.skipAbove = it }), lp(6))
        col.addView(toggle("Mode cepat (lewati pemindaian server)", { Prefs.fast }, { Prefs.fast = it }), lp(6))
        col.addView(toggle("Tutup game otomatis setelah selesai", { Prefs.closeGame }, { Prefs.closeGame = it }), lp(6))
        col.addView(toggle("Tampilkan semua aplikasi", { Prefs.showAll }, { Prefs.showAll = it }), lp(6))

        col.addView(btn("MULAI KLAIM") {
            Prefs.serverCount = cnt.text.toString().trim().toIntOrNull() ?: 0
            val r = Engine.start(applicationContext)
            if (r != null) toast(r) else toast("Klaim dimulai")
        }, lp(16))
        col.addView(btn("BERHENTI") { Engine.stop() }, lp(6))

        val speeds = listOf(1f, 1.25f, 1.5f, 2f)
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        val sbtns = ArrayList<Button>()
        for (sp in speeds) {
            val b = Button(this)
            b.isAllCaps = false
            b.text = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()) + "x"
            b.setOnClickListener {
                Prefs.speed = sp
                for (k in sbtns.indices) style(sbtns[k], speeds[k] == sp)
            }
            style(b, Prefs.speed == sp)
            sbtns.add(b)
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            sr.addView(b, p)
        }
        col.addView(card(tv("Kecepatan gerak (mempersingkat jeda antar ketukan)", 12f, false, true), sr), lp(16))

        val lw = edit("detik", Prefs.lobbyWait.toString(), true)
        col.addView(card(tv("Waktu tunggu LOBBY (detik, sebelum buka Event)", 12f, false, true), lw), lp())
        col.addView(btn("Simpan waktu") {
            Prefs.lobbyWait = lw.text.toString().trim().toIntOrNull() ?: 3
            toast("Tersimpan")
        }, lp(6))
        return sv
    }

    private fun pickGame() {
        val apps = Engine.listApps(this, Prefs.showAll)
        val labels = ArrayList<String>()
        labels.add("Otomatis (cari Mobile Legends)")
        for (a in apps) labels.add(a.second + "\n" + a.first)
        AlertDialog.Builder(this)
            .setTitle("Pilih game")
            .setItems(labels.toTypedArray()) { _, which ->
                Prefs.pkg = if (which == 0) "" else apps[which - 1].first
                tvGame?.text = gameLabel()
            }
            .show()
    }

    private fun logTab(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(8))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val b1 = btn("Salin log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text()))
            toast("Log disalin")
        }
        val b2 = btn("Hapus log") {
            AppLog.clear()
            tick()
        }
        val b3 = btn("Diagnosa") {
            Engine.diagnose(applicationContext)
            toast("Buka game ke lobby, tunggu 8 detik")
        }
        for (b in listOf(b1, b2, b3)) {
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            row.addView(b, p)
        }
        col.addView(row)
        val st = tv("", 14f, true)
        tvStatus = st
        col.addView(st, lp(8))
        val t = tv("", 11f)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        tvLog = t
        val sv = ScrollView(this)
        sv.addView(t)
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun settings(): View {
        val (sv, col) = page()
        col.addView(tv("Setelan", 24f, true))
        val svc = tv("", 13f, false, true)
        tvSvc = svc
        col.addView(svc, lp())
        col.addView(btn("1. Buka Pengaturan Aksesibilitas") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp())
        col.addView(btn("Ulangi dari awal (hapus catatan server selesai hari ini)") {
            Prefs.resetDone()
            toast("Catatan dihapus")
        }, lp(6))

        val keys = edit("kata kunci lobby", Prefs.lobbyKeys, false)
        val min = edit("minimal cocok", Prefs.lobbyMin.toString(), true)
        col.addView(
            card(
                tv("Kata kunci layar LOBBY (pisah koma, huruf kecil)", 12f, false, true), keys,
                tv("Minimal berapa kata harus terbaca", 12f, false, true), min
            ), lp(16)
        )
        col.addView(btn("Simpan kata kunci") {
            Prefs.lobbyKeys = keys.text.toString()
            Prefs.lobbyMin = min.text.toString().trim().toIntOrNull() ?: 3
            toast("Tersimpan")
        }, lp(6))

        val ax = edit("X (persen)", pct(Prefs.avatarX), false)
        val ay = edit("Y (persen)", pct(Prefs.avatarY), false)
        ax.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        ay.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        col.addView(
            card(
                tv("Posisi ketuk IKON PROFIL di lobby (persen layar, angka 0-100)", 12f, false, true),
                tv("X = jarak dari kiri", 12f, false, true), ax,
                tv("Y = jarak dari atas", 12f, false, true), ay
            ), lp(16)
        )
        col.addView(btn("Simpan posisi ikon profil") {
            val x = ax.text.toString().trim().replace(',', '.').toFloatOrNull()
            val y = ay.text.toString().trim().replace(',', '.').toFloatOrNull()
            if (x == null || y == null || x <= 0f || x >= 100f || y <= 0f || y >= 100f) {
                toast("Isi angka antara 0 dan 100")
                return@btn
            }
            Prefs.avatarX = x / 100f
            Prefs.avatarY = y / 100f
            toast("Tersimpan")
        }, lp(6))

        val os = edit("Skala baca layar (%)", Prefs.ocrScale.toString(), true)
        col.addView(
            card(
                tv("Kecepatan baca layar (30-100). Makin kecil makin cepat, tapi tulisan kecil bisa tidak terbaca. 100 = penuh. Awal: 60.", 12f, false, true),
                os
            ), lp(16)
        )
        col.addView(btn("Simpan skala baca layar") {
            val v = os.text.toString().trim().toIntOrNull()
            if (v == null || v < 30 || v > 100) {
                toast("Isi angka 30 sampai 100")
                return@btn
            }
            Prefs.ocrScale = v
            toast("Tersimpan")
        }, lp(6))
        col.addView(
            tv(
                "Aplikasi ini tidak memiliki izin internet. Layanan Aksesibilitas hanya dipakai untuk screenshot dan ketukan " +
                    "saat klaim berjalan. Matikan layanan ini setelah selesai dan jangan buka aplikasi bank saat aktif. " +
                    "Memakai bot melanggar aturan game dan bisa membuat akun dikenai sanksi.",
                12f, false, true
            ), lp(16)
        )
        return sv
    }

    private fun templates(): View {
        val (sv, col) = page()
        col.addView(tv("Templat gambar", 24f, true))
        col.addView(
            tv(
                "Template dipakai untuk mengenali ikon yang tidak berupa tulisan. Semuanya opsional:\n" +
                    "• x1, x2, ... = tombol tutup X pada popup (nama berawalan x diketuk otomatis)\n" +
                    "• event = ikon Event, hanya kalau tulisan Event tidak terbaca (jangan diawali x)\n" +
                    "• avatar = cadangan ikon profil, hanya dipakai kalau posisi tetap di Setelan gagal\n\n" +
                    "Nama hanya huruf kecil, angka, dan garis bawah. Tombol bertulisan (Klaim, OK, dll.) tidak perlu template.\n" +
                    "Pilih gambar yang sudah dipotong (tanpa kotak seleksi) = seluruh gambar disimpan. " +
                    "Atau pilih screenshot lalu kotaki bagiannya.",
                13f, false, true
            ), lp()
        )
        col.addView(btn("Buat template dari screenshot") {
            startActivity(Intent(this, TemplateActivity::class.java))
        }, lp(12))
        val names = Matcher.names(this)
        col.addView(tv("Template tersimpan (" + names.size + ")", 14f, true), lp(16))
        for (n in names) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val t = tv(n, 15f)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(btn("Hapus") {
                java.io.File(Matcher.dir(this), n + ".png").delete()
                Matcher.reload(this)
                show(3)
            })
            col.addView(r, lp(4))
        }
        return sv
    }

    private fun tick() {
        tvStatus?.text = if (tab == 0) {
            Engine.status + "\nJumlah: " + Engine.total + " server\nServer selesai: " + Engine.doneCount
        } else {
            Engine.status + " | Jumlah: " + Engine.total + " server | Selesai: " + Engine.doneCount
        }
        val lg = tvLog
        if (lg != null) {
            val t = AppLog.text()
            if (t != lastLog) {
                lastLog = t
                lg.text = t
            }
        }
        tvSvc?.text = if (ClaimService.inst != null) "Layanan Aksesibilitas: AKTIF" else
            "Layanan Aksesibilitas: BELUM AKTIF (nyalakan 'VG Klaim' di Aksesibilitas)"
    }
}
