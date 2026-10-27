package id.pribadi.vgklaim

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

// Tema tunggal: biru malam dan emas
private val C_BG = Color.parseColor("#0A0F1E")
private val C_FG = Color.parseColor("#E9ECF5")
private val C_MUT = Color.parseColor("#9AA6C4")
private val C_DIV = Color.parseColor("#1B2744")
private val C_POP = Color.parseColor("#111A30")
private val C_LINE = Color.parseColor("#2C3A5E")
private val C_ACC = Color.parseColor("#E2C275")
private val C_ONACC = Color.parseColor("#1A1405")
private val C_SURF = Color.parseColor("#111A30")

// Ikon kembali (chevron) yang sama dengan rancangan
private class BackIcon(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = C_FG
        p.strokeWidth = 2.4f * ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val path = Path()
        path.moveTo(w * 0.60f, h * 0.30f)
        path.lineTo(w * 0.40f, h * 0.50f)
        path.lineTo(w * 0.60f, h * 0.70f)
        c.drawPath(path, p)
    }
}

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private var tab = 0
    private var help = false
    private var tvStatus: TextView? = null
    private var tvProg: TextView? = null
    private var tvLog: TextView? = null
    private var tvSvc: TextView? = null
    private var barFill: View? = null
    private var barRest: View? = null
    private var lastFrac = -1f
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
        render()
    }

    override fun onResume() {
        super.onResume()
        h.post(ticker)
        if (tab == 2 && !help) render()
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (help) {
            help = false
            render()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- bahan tampilan ----------

    private fun shape(fill: Int, stroke: Int, radiusDp: Int, strokeDp: Float = 1.5f): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        if (strokeDp > 0f) g.setStroke(maxOf(1, (strokeDp * resources.displayMetrics.density).toInt()), stroke)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun styleBtn(b: Button, primary: Boolean) {
        b.background = shape(if (primary) C_ACC else Color.TRANSPARENT, if (primary) C_ACC else C_LINE, 30)
        b.setTextColor(if (primary) C_ONACC else C_FG)
    }

    private fun btn(t: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 15f
        b.setTypeface(null, Typeface.BOLD)
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        styleBtn(b, primary)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun tv(t: String, size: Float = 15f, bold: Boolean = false, color: Int = C_FG): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun card(vararg views: View): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(6), dp(18), dp(6))
        c.background = shape(C_SURF, C_LINE, 24)
        for (v in views) c.addView(v)
        return c
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(C_DIV)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1)))
        return v
    }

    // Satu baris: label di kiri, kontrol di kanan
    private fun row(label: String, right: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(52)
        r.addView(tv(label, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rp = (right.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rp.leftMargin = dp(12)
        r.addView(right, rp)
        return r
    }

    private fun linkRow(label: String, value: TextView, onClick: () -> Unit): View {
        val r = row(label, value)
        r.setOnClickListener { onClick() }
        return r
    }

    private fun switchRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val sw = Switch(this)
        sw.isChecked = get()
        sw.thumbTintList = null
        sw.trackTintList = null
        // Gelap/emas penuh seperti rancangan: nyala = jalur emas + bulatan gelap, mati = jalur garis + bulatan abu
        val trOn = GradientDrawable()
        trOn.setColor(C_ACC)
        trOn.setStroke(dp(2), C_ACC)
        trOn.cornerRadius = dp(16).toFloat()
        trOn.setSize(dp(56), dp(32))
        val trOff = GradientDrawable()
        trOff.setColor(Color.TRANSPARENT)
        trOff.setStroke(dp(2), C_LINE)
        trOff.cornerRadius = dp(16).toFloat()
        trOff.setSize(dp(56), dp(32))
        val track = StateListDrawable()
        track.addState(intArrayOf(android.R.attr.state_checked), trOn)
        track.addState(intArrayOf(), trOff)
        val knOn = GradientDrawable()
        knOn.shape = GradientDrawable.OVAL
        knOn.setColor(C_ONACC)
        knOn.setSize(dp(22), dp(22))
        val knOff = GradientDrawable()
        knOff.shape = GradientDrawable.OVAL
        knOff.setColor(C_MUT)
        knOff.setSize(dp(22), dp(22))
        val thumb = StateListDrawable()
        thumb.addState(intArrayOf(android.R.attr.state_checked), InsetDrawable(knOn, dp(4)))
        thumb.addState(intArrayOf(), InsetDrawable(knOff, dp(4)))
        sw.trackDrawable = track
        sw.thumbDrawable = thumb
        sw.switchMinWidth = dp(56)
        sw.setOnCheckedChangeListener { _, c -> set(c) }
        return row(label, sw)
    }

    private fun numBox(value: String, onChange: ((String) -> Unit)? = null): EditText {
        val e = EditText(this)
        e.setText(value)
        e.setTextColor(C_FG)
        e.setHintTextColor(C_MUT)
        e.gravity = Gravity.CENTER
        e.textSize = 17f
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setSingleLine(true)
        e.background = shape(Color.TRANSPARENT, C_LINE, 22)
        e.setPadding(dp(8), 0, dp(8), 0)
        e.layoutParams = LinearLayout.LayoutParams(dp(96), dp(44))
        if (onChange != null) {
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    onChange(s?.toString() ?: "")
                }
            })
        }
        return e
    }

    private fun styleSeg(b: Button, on: Boolean) {
        b.background = shape(if (on) C_ACC else Color.TRANSPARENT, if (on) C_ACC else C_LINE, 22)
        b.setTextColor(if (on) C_ONACC else C_FG)
    }

    private fun seg(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val bs = ArrayList<Button>()
        for (k in options.indices) {
            val b = Button(this)
            b.isAllCaps = false
            b.text = options[k]
            b.textSize = 14f
            b.setTypeface(null, Typeface.BOLD)
            b.stateListAnimator = null
            b.minHeight = 0
            b.minimumHeight = 0
            b.setPadding(dp(6), 0, dp(6), 0)
            styleSeg(b, k == selected)
            b.setOnClickListener {
                for (j in bs.indices) styleSeg(bs[j], j == k)
                onPick(k)
            }
            bs.add(b)
            val p = LinearLayout.LayoutParams(0, dp(44), 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            r.addView(b, p)
        }
        return r
    }

    private fun lpw(hDp: Int, topDp: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (hDp > 0) dp(hDp) else ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(0, dp(topDp), 0, 0)
        return p
    }

    private fun section(title: String, body: View): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val t = tv(title, 15f, true, C_MUT)
        t.setPadding(dp(12), 0, 0, dp(8))
        c.addView(t)
        c.addView(body)
        return c
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(16))
        sv.addView(col)
        return Pair(sv, col)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- kerangka: header, isi tab, navigasi ----------

    private fun header(title: String, logo: Boolean, menu: Boolean, back: Boolean): View {
        val hd = LinearLayout(this)
        hd.orientation = LinearLayout.HORIZONTAL
        hd.gravity = Gravity.CENTER_VERTICAL
        hd.setPadding(dp(24), dp(8), dp(12), 0)
        hd.minimumHeight = dp(64)
        if (back) {
            val b = BackIcon(this)
            b.contentDescription = "Kembali"
            b.setOnClickListener {
                help = false
                render()
            }
            val bp = LinearLayout.LayoutParams(dp(48), dp(48))
            bp.leftMargin = -dp(12)
            hd.addView(b, bp)
        }
        if (logo) {
            val iv = ImageView(this)
            iv.setImageResource(R.drawable.ic_logo)
            iv.setColorFilter(C_ACC)
            val ip = LinearLayout.LayoutParams(dp(32), dp(32))
            ip.rightMargin = dp(12)
            hd.addView(iv, ip)
        }
        hd.addView(tv(title, 24f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (menu) {
            val m = TextView(this)
            m.text = "\u22EE"
            m.textSize = 28f
            m.setTextColor(C_FG)
            m.gravity = Gravity.CENTER
            m.contentDescription = "Menu"
            m.setOnClickListener { showMenu() }
            hd.addView(m, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        return hd
    }

    private fun render() {
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        tvStatus = null
        tvProg = null
        tvLog = null
        tvSvc = null
        barFill = null
        barRest = null
        lastFrac = -1f

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.setPadding(0, dp(28), 0, 0)

        if (help) {
            root.addView(header("Fungsi pengaturan", false, false, true))
            root.addView(helpPage(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(root)
            return
        }

        val titles = listOf("VG Klaim", "Log", "Pengaturan")
        if (tab == 0) root.addView(modeBar()) else root.addView(header(titles[tab], false, tab == 2, false))
        val body: View = when (tab) {
            0 -> if (Prefs.homeMode == 1) bbcHome() else home()
            1 -> logTab()
            else -> settings()
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setPadding(dp(16), dp(10), dp(16), dp(14))
        val names = listOf("Beranda", "Log", "Pengaturan")
        for (i in names.indices) {
            val b = Button(this)
            b.text = names[i]
            b.isAllCaps = false
            b.textSize = 13f
            b.setTypeface(null, Typeface.BOLD)
            b.stateListAnimator = null
            b.minHeight = 0
            b.minimumHeight = 0
            val on = i == tab
            b.background = shape(if (on) C_ACC else Color.TRANSPARENT, if (on) C_ACC else C_LINE, 24)
            b.setTextColor(if (on) C_ONACC else C_FG)
            b.setOnClickListener {
                tab = i
                render()
            }
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            nav.addView(b, lp)
        }
        root.addView(nav)
        // Saat keyboard tampil, tab bawah disembunyikan (tidak ikut naik di atas keyboard)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val sh = root.rootView.height
            nav.visibility = if (sh - r.bottom > sh * 0.15f) View.GONE else View.VISIBLE
        }
        setContentView(root)
        tick()
    }

    // ---------- tab Beranda ----------

    private fun home(): View {
        val (sv, col) = page()

        val st = tv("", 22f, true, C_ACC)
        tvStatus = st
        val prog = tv("", 17f, false, C_FG)
        tvProg = prog

        val barBox = LinearLayout(this)
        barBox.orientation = LinearLayout.HORIZONTAL
        barBox.weightSum = 1f
        barBox.background = shape(C_DIV, C_DIV, 6, 0f)
        val fill = View(this)
        fill.background = shape(C_ACC, C_ACC, 6, 0f)
        val rest = View(this)
        barFill = fill
        barRest = rest
        barBox.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        barBox.addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val stBox = LinearLayout(this)
        stBox.orientation = LinearLayout.VERTICAL
        stBox.setPadding(0, dp(12), 0, dp(14))
        stBox.addView(tv("Status", 14f, false, C_MUT))
        stBox.addView(st, lpw(0, 2))
        stBox.addView(divider(), lpw(1, 12))
        stBox.addView(tv("Selesai", 14f, false, C_MUT), lpw(0, 12))
        stBox.addView(prog, lpw(0, 2))
        stBox.addView(barBox, lpw(10, 10))
        col.addView(card(stBox), lpw(0, 4))

        val cnt = numBox(if (Prefs.serverCount > 0) Prefs.serverCount.toString() else "") { t ->
            t.trim().toIntOrNull()?.let { Prefs.serverCount = it }
        }
        cnt.hint = "Wajib diisi"
        col.addView(card(row("Jumlah server", cnt)), lpw(0, 12))

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(btn("MULAI KLAIM", true) {
            val n = cnt.text.toString().trim().toIntOrNull() ?: 0
            if (n < 2) {
                toast("Isi Jumlah server dulu (angka 2 atau lebih)")
                return@btn
            }
            Prefs.serverCount = n
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
            }
            val r = Engine.start(applicationContext)
            if (r != null) toast(r) else toast("Klaim dimulai")
        }, lpw(60, 16))
        col.addView(btn("BERHENTI") { Engine.stop() }, lpw(60, 10))
        return sv
    }

    // ---------- Beranda: dua kotak pilihan (VG Klaim / Kirim BBC) ----------

    private fun modeBar(): View {
        val rowV = LinearLayout(this)
        rowV.orientation = LinearLayout.HORIZONTAL
        rowV.setPadding(dp(20), dp(10), dp(20), dp(4))
        fun box(label: String, mode: Int, logo: Boolean): View {
            val on = Prefs.homeMode == mode
            val b = LinearLayout(this)
            b.orientation = LinearLayout.HORIZONTAL
            b.gravity = Gravity.CENTER
            b.background = shape(if (on) C_ACC else Color.TRANSPARENT, if (on) C_ACC else C_LINE, 26)
            if (logo) {
                val iv = ImageView(this)
                iv.setImageResource(R.drawable.ic_logo)
                iv.setColorFilter(if (on) C_ONACC else C_ACC)
                val ip = LinearLayout.LayoutParams(dp(26), dp(26))
                ip.rightMargin = dp(8)
                b.addView(iv, ip)
            }
            b.addView(tv(label, 17f, true, if (on) C_ONACC else C_FG))
            b.setOnClickListener {
                Prefs.homeMode = mode
                render()
            }
            return b
        }
        val lp1 = LinearLayout.LayoutParams(0, dp(52), 1f)
        lp1.rightMargin = dp(6)
        val lp2 = LinearLayout.LayoutParams(0, dp(52), 1f)
        lp2.leftMargin = dp(6)
        rowV.addView(box("VG Klaim", 0, true), lp1)
        rowV.addView(box("Kirim BBC", 1, false), lp2)
        return rowV
    }

    private fun textBox(value: String, hint: String, numeric: Boolean, onChange: (String) -> Unit): EditText {
        val e = EditText(this)
        e.setText(value)
        e.hint = hint
        e.setTextColor(C_FG)
        e.setHintTextColor(C_MUT)
        e.gravity = Gravity.CENTER
        e.textSize = 17f
        e.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        e.setSingleLine(true)
        e.background = shape(Color.TRANSPARENT, C_LINE, 22)
        e.setPadding(dp(8), 0, dp(8), 0)
        e.layoutParams = LinearLayout.LayoutParams(dp(180), dp(44))
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return e
    }

    private fun bbcHome(): View {
        val (sv, col) = page()

        val st = tv("", 22f, true, C_ACC)
        tvStatus = st
        val prog = tv("", 17f, false, C_FG)
        tvProg = prog

        val barBox = LinearLayout(this)
        barBox.orientation = LinearLayout.HORIZONTAL
        barBox.weightSum = 1f
        barBox.background = shape(C_DIV, C_DIV, 6, 0f)
        val fill = View(this)
        fill.background = shape(C_ACC, C_ACC, 6, 0f)
        val rest = View(this)
        barFill = fill
        barRest = rest
        barBox.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        barBox.addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val stBox = LinearLayout(this)
        stBox.orientation = LinearLayout.VERTICAL
        stBox.setPadding(0, dp(12), 0, dp(14))
        stBox.addView(tv("Status", 14f, false, C_MUT))
        stBox.addView(st, lpw(0, 2))
        stBox.addView(divider(), lpw(1, 12))
        stBox.addView(tv("Terkirim", 14f, false, C_MUT), lpw(0, 12))
        stBox.addView(prog, lpw(0, 2))
        stBox.addView(barBox, lpw(10, 10))
        col.addView(card(stBox), lpw(0, 4))

        val idBox = textBox(Prefs.bbcId, "ID penerima", true) { t -> Prefs.bbcId = t.trim() }
        val nickBox = textBox(Prefs.bbcNick, "Nick penerima", false) { t -> Prefs.bbcNick = t }
        val amtBox = numBox(if (Prefs.bbcAmount > 0) Prefs.bbcAmount.toString() else "") { t ->
            Prefs.bbcAmount = t.trim().toIntOrNull() ?: 0
        }
        amtBox.hint = "kelipatan 1000"
        val cnt = numBox(if (Prefs.serverCount > 0) Prefs.serverCount.toString() else "") { t ->
            t.trim().toIntOrNull()?.let { Prefs.serverCount = it }
        }
        col.addView(
            card(
                row("ID penerima", idBox), divider(),
                row("Nick", nickBox), divider(),
                switchRow("Cocokkan nick", { Prefs.bbcMatchNick }, { Prefs.bbcMatchNick = it }), divider(),
                row("Jumlah BBC", amtBox), divider(),
                switchRow("Klaim dulu", { Prefs.bbcClaimFirst }, { Prefs.bbcClaimFirst = it }), divider(),
                row("Jumlah server", cnt)
            ),
            lpw(0, 12)
        )

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(btn("MULAI KIRIM", true) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
            }
            val r = Engine.start(applicationContext, 1)
            if (r != null) toast(r) else toast("Kirim BBC dimulai")
        }, lpw(60, 16))
        col.addView(btn("BERHENTI") { Engine.stop() }, lpw(60, 10))
        col.addView(btn("Reset progres pesanan") {
            if (Engine.running) {
                toast("Hentikan dulu proses yang berjalan")
            } else {
                Prefs.bbcClearPending()
                toast("Progres pesanan dihapus")
                tick()
            }
        }, lpw(60, 10))
        return sv
    }

    // ---------- tab Log ----------

    private fun logTab(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(8), dp(16), dp(8))

        val rowB = LinearLayout(this)
        rowB.orientation = LinearLayout.HORIZONTAL
        val b1 = btn("Salin log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text()))
            toast("Log disalin")
        }
        val b2 = btn("Hapus log") {
            AppLog.clear()
            lastLog = ""
            tick()
        }
        val b3 = btn("Diagnosa", true) {
            Engine.diagnose(applicationContext)
            toast("Pindah ke game dalam 15 detik, lalu diam")
        }
        for (b in listOf(b1, b2, b3)) {
            val p = LinearLayout.LayoutParams(0, dp(48), 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            rowB.addView(b, p)
        }
        col.addView(rowB)

        val st = tv("", 13f, false, C_MUT)
        tvStatus = st
        col.addView(st, lpw(0, 10))

        val t = tv("", 12f)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        t.setPadding(dp(14), dp(12), dp(14), dp(12))
        tvLog = t
        val sv = ScrollView(this)
        sv.background = shape(C_SURF, C_LINE, 24)
        sv.addView(t)
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dp(10) })
        return col
    }

    // ---------- tab Pengaturan ----------

    private fun gameShort(): String {
        val p = Prefs.pkg
        if (p.isEmpty()) return "Otomatis"
        for ((pk, lb) in Engine.listApps(this, true)) if (pk == p) return lb
        return p
    }

    private fun settings(): View {
        val (sv, col) = page()

        // Izin
        val svc = tv("", 15f, false, C_MUT)
        tvSvc = svc
        val bat = tv("", 15f, false, C_MUT)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val ok = pm.isIgnoringBatteryOptimizations(packageName)
            bat.text = if (ok) "Tanpa batasan" else "Belum diatur"
            if (ok) bat.setTextColor(C_ACC)
        } catch (e: Exception) {
            bat.text = "Tidak diketahui"
        }
        col.addView(
            section(
                "Izin",
                card(
                    linkRow("Aksesibilitas", svc) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    divider(),
                    linkRow("Baterai", bat) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e: Exception) {
                            toast("Tidak bisa dibuka: " + e.javaClass.simpleName)
                        }
                    }
                )
            ), lpw(0, 4)
        )

        // Game
        val gv = tv(gameShort(), 15f, false, C_MUT)
        col.addView(section("Game", card(linkRow("Aplikasi game", gv) { pickGame(gv) })), lpw(0, 16))

        // Klaim
        val sm = numBox(Prefs.stepMs.toString())
        val lw = numBox(Prefs.lobbyWait.toString())
        val afterLabel = tv("Setelah selesai", 16f)
        afterLabel.setPadding(0, dp(14), 0, dp(8))
        val afterSeg = seg(listOf("VG Klaim", "Home", "Tetap di game"), Prefs.afterDone - 1) { k -> Prefs.afterDone = k + 1 }
        val afterBox = LinearLayout(this)
        afterBox.orientation = LinearLayout.VERTICAL
        afterBox.setPadding(0, 0, 0, dp(12))
        afterBox.addView(afterLabel)
        afterBox.addView(afterSeg)
        val saveBox = LinearLayout(this)
        saveBox.orientation = LinearLayout.VERTICAL
        saveBox.setPadding(0, dp(12), 0, dp(12))
        saveBox.addView(btn("Simpan") {
            Prefs.stepMs = sm.text.toString().trim().toIntOrNull() ?: 400
            Prefs.lobbyWait = lw.text.toString().trim().toIntOrNull() ?: 3000
            toast("Tersimpan")
        }, lpw(48, 0))
        col.addView(
            section(
                "Klaim",
                card(
                    afterBox, divider(),
                    switchRow("Lewati server di atas posisi sekarang", { Prefs.skipAbove }, { Prefs.skipAbove = it }), divider(),
                    switchRow("Titik melayang saat klaim", { Prefs.dotAuto }, { Prefs.dotAuto = it }), divider(),
                    switchRow("Klaim Verifikasi Usia", { Prefs.claimAge }, { Prefs.claimAge = it }), divider(),
                    row("Jeda antar langkah (ms)", sm), divider(),
                    row("Waktu tunggu lobby (ms)", lw), divider(),
                    row("Nomor server pertama", numBox(Prefs.serverMin.toString()) { t -> t.trim().toIntOrNull()?.let { Prefs.serverMin = it } }), divider(),
                    row("Nomor server terakhir", numBox(Prefs.serverMax.toString()) { t -> t.trim().toIntOrNull()?.let { Prefs.serverMax = it } }), divider(),
                    saveBox
                )
            ), lpw(0, 16)
        )

        // Geser daftar server (hanya manual)
        val gviews = ArrayList<View>()
        fun field(label: String, value: Int, set: (Int) -> Unit) {
            if (gviews.isNotEmpty()) gviews.add(divider())
            gviews.add(row(label, numBox(value.toString()) { s ->
                val n = s.trim().toIntOrNull()
                if (n != null) set(n)
            }))
        }
        field("Geser cepat: durasi (ms)", Prefs.swipeHardMs) { Prefs.swipeHardMs = it }
        field("Geser cepat: awal (% tinggi)", Prefs.swipeStart) { Prefs.swipeStart = it }
        field("Geser cepat: akhir (% tinggi)", Prefs.swipeEnd) { Prefs.swipeEnd = it }
        field("Geser pelan: durasi (ms)", Prefs.swipeSlowMs) { Prefs.swipeSlowMs = it }
        field("Geser pelan: awal (% tinggi)", Prefs.swipeSlowStart) { Prefs.swipeSlowStart = it }
        field("Geser pelan: akhir (% tinggi)", Prefs.swipeSlowEnd) { Prefs.swipeSlowEnd = it }
        field("Geser balik: durasi (ms)", Prefs.swipeBackMs) { Prefs.swipeBackMs = it }
        field("Geser balik: awal (% tinggi)", Prefs.swipeBackStart) { Prefs.swipeBackStart = it }
        field("Geser balik: akhir (% tinggi)", Prefs.swipeBackEnd) { Prefs.swipeBackEnd = it }
        field("Posisi X (% lebar)", Prefs.swipeX) { Prefs.swipeX = it }
        field("Jumlah server (uji)", Prefs.testCount) { Prefs.testCount = it }
        val bb = LinearLayout(this)
        bb.orientation = LinearLayout.HORIZONTAL
        bb.setPadding(0, dp(12), 0, dp(12))
        val t1 = btn("Uji sampai bawah", true) {
            Engine.diagnose(applicationContext, true)
            toast("Buka Daftar Karakter, pindah ke game dalam 15 detik, lalu diam")
        }
        val t2 = btn("Bawaan") {
            Prefs.resetSwipe()
            render()
        }
        val p1 = LinearLayout.LayoutParams(0, dp(48), 1f)
        p1.setMargins(0, 0, dp(4), 0)
        val p2 = LinearLayout.LayoutParams(0, dp(48), 1f)
        p2.setMargins(dp(4), 0, 0, 0)
        bb.addView(t1, p1)
        bb.addView(t2, p2)
        gviews.add(divider())
        gviews.add(bb)
        col.addView(section("Geser daftar server", card(*gviews.toTypedArray())), lpw(0, 16))

        col.addView(
            section(
                "Kirim BBC",
                card(row("Level server minimal", numBox(Prefs.bbcMinLevel.toString()) { t -> t.trim().toIntOrNull()?.let { Prefs.bbcMinLevel = it } }))
            ),
            lpw(0, 16)
        )

        // Data
        col.addView(
            section("Data hari ini", btn("Ulangi dari awal") {
                Prefs.resetDone()
                toast("Catatan dihapus")
            }.also { it.layoutParams = lpw(52, 0) }), lpw(0, 16)
        )
        return sv
    }

    private fun pickGame(label: TextView) {
        var apps = Engine.listApps(this, false)
        if (apps.isEmpty()) apps = Engine.listApps(this, true)
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(20), dp(22), dp(12))
        box.background = shape(C_POP, C_ACC, 28)
        box.addView(tv("Pilih game", 20f, true))
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        fun item(title: String, sub: String, pkg: String) {
            if (list.childCount > 0) list.addView(divider())
            val rowV = LinearLayout(this)
            rowV.orientation = LinearLayout.VERTICAL
            rowV.gravity = Gravity.CENTER_VERTICAL
            rowV.minimumHeight = dp(60)
            rowV.setPadding(0, dp(8), 0, dp(8))
            rowV.addView(tv(title, 16f, false, if (Prefs.pkg == pkg) C_ACC else C_FG))
            if (sub.isNotEmpty()) rowV.addView(tv(sub, 13f, false, C_MUT))
            rowV.setOnClickListener {
                Prefs.pkg = pkg
                label.text = gameShort()
                d.dismiss()
            }
            list.addView(rowV)
        }
        item("Otomatis (cari Mobile Legends)", "", "")
        for (a in apps) item(a.second, a.first, a.first)
        val sv = ScrollView(this)
        sv.addView(list)
        val svp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        svp.topMargin = dp(8)
        box.addView(sv, svp)
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    // ---------- menu titik tiga dan layar Fungsi pengaturan ----------

    private fun showMenu() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(4), dp(18), dp(4))
        box.background = shape(C_POP, C_ACC, 24)
        val item = tv("Fungsi pengaturan", 16f)
        item.gravity = Gravity.CENTER_VERTICAL
        item.minimumHeight = dp(56)
        item.setOnClickListener {
            d.dismiss()
            help = true
            render()
        }
        box.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.TOP or Gravity.END)
            val a = w.attributes
            a.x = dp(16)
            a.y = dp(72)
            w.attributes = a
        }
        d.show()
        d.window?.setLayout(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val helpItems = listOf(
        "Aksesibilitas" to "Layanan yang dipakai untuk screenshot dan ketukan. Harus aktif. Nyalakan lagi setelah memasang pembaruan.",
        "Baterai" to "Atur ke tanpa batasan supaya sistem tidak membatasi kecepatan baca layar.",
        "Aplikasi game" to "Pilih aplikasi Mobile Legends yang dibuka bot.",
        "Jumlah server" to "Isi jumlah semua server akun ini, termasuk server yang sedang dipakai (awalnya 20). Wajib diisi. Bot terus mencari sampai semua server terlihat, dan tidak berhenti sebelum itu.",
        "Setelah selesai" to "Yang dilakukan bot setelah semua server selesai. VG Klaim: langsung membuka VG Klaim. Home: langsung ke layar Home. Tetap di game: kembali ke lobby dan tetap di game. Game tidak ditutup dan tidak dibuang dari Recents. Banner hasil klaim selalu tampil.",
        "Lewati server di atas posisi sekarang" to "Server yang urutannya di atas server awal dianggap sudah selesai dan tidak diklaim.",
        "Klaim Verifikasi Usia" to "Kalau aktif, di tiap server bot juga mengklaim Verifikasi Usia (satu kali per server): Klaim Semua, Gunakan sekarang, geser peti ke kartu Vanguard Coin, tombol +, Konfirmasi, lalu OK. Hasilnya Vanguard Coin 300. Kalau sudah diklaim atau menunya tidak ada, bot langsung lanjut.",
        "Titik melayang saat klaim" to "Titik tak terlihat yang membuat baca layar sekitar 10 kali lebih cepat. Gayanya dipilih lewat Diagnosa umum.",
        "Jeda antar langkah" to "Lama bot menunggu setelah tiap ketukan sebelum melihat layar lagi, dalam milidetik. 0 berarti tanpa jeda.",
        "Nomor server pertama dan terakhir" to "Hanya nomor server dalam rentang ini (awalnya 57092 sampai 57115) yang dianggap server. Nama yang kehilangan satu digit karena salah baca (misalnya s5094) diperbaiki otomatis kalau hanya ada satu kemungkinan, dan kalau tidak, barisnya diabaikan. Ubah kalau game menambah server.",
        "Kirim BBC" to "Pilih kotak Kirim BBC di Beranda. Isi ID penerima, nick (nyalakan Cocokkan nick untuk memastikan akun yang dikirim benar, matikan untuk memilih hasil pencarian paling atas), Jumlah BBC (kelipatan 1000), dan Jumlah server akun pengirim. Tiap server mengirim satu Yacht (1000 diamond = 1000 BBC): bot membeli 500 Diamond dengan Vanguard Coin bila diamond di lobby kurang dari 1000, mencari akun penerima lewat ID, lalu mengirim Yacht. Klaim dulu: klaim di tiap server sebelum kirim. Server yang sudah dipakai kirim diingat per akun pengirim. Kalau server di akun habis sebelum target tercapai, ganti akun lalu tekan Mulai kirim lagi: bot melanjutkan sisanya. Level server minimal (awalnya 15): server pertama di akun dicek dari angka di bawah nama pemain di lobby, server berikutnya dicek dari Daftar Karakter saat dipilih. Server yang levelnya kurang dilewati tanpa membelanjakan koin. Screenshot before.png dan after.png disimpan di Pictures/VGKlaim.",
        "Waktu tunggu lobby" to "Dalam milidetik. Dipakai hanya kalau lobby langsung muncul setelah loading tanpa popup apa pun: bot menunggu paling lama selama ini untuk popup. Begitu satu popup muncul dan ditutup, bot langsung lanjut. Kalau popup sudah muncul dan ditutup sebelum lobby, waktu ini dilewati.",
        "Geser daftar server" to "Geser cepat memuat server baru dan HANYA dipakai sekali, saat pertama mencari server baru di daftar yang baru dibuka. Sesudah itu bot hanya geser pelan. Geser pelan menggeser daftar maju. Geser balik menggeser daftar mundur, hanya kalau tidak ada server lama yang terlihat. Angka awal dan akhir adalah persen tinggi layar: 0 di atas, 100 di bawah. Uji sampai bawah: isi Jumlah server (uji), buka Daftar Karakter, tekan tombolnya, lalu pindah ke game. Bot terus menggeser sampai semua server terlihat dan melaporkan hasilnya.",
        "Diagnosa" to "Tombol Diagnosa di tab Log mengukur kecepatan baca layar (OCR) dan memilih gaya titik melayang. Untuk menguji geser daftar, pakai tombol Uji sampai bawah di bagian Geser daftar server.",
        "Ulangi dari awal" to "Menghapus catatan server yang sudah selesai hari ini."
    )

    private fun helpPage(): View {
        val (sv, col) = page()
        val items = ArrayList<View>()
        for (i in helpItems.indices) {
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.setPadding(0, dp(14), 0, dp(14))
            b.addView(tv(helpItems[i].first, 16f, true))
            val d = tv(helpItems[i].second, 14f, false, C_MUT)
            d.setLineSpacing(0f, 1.15f)
            b.addView(d, lpw(0, 4))
            items.add(b)
            if (i < helpItems.size - 1) items.add(divider())
        }
        col.addView(card(*items.toTypedArray()), lpw(0, 4))
        val w = tv(
            "Aplikasi ini tidak memiliki izin internet. Layanan Aksesibilitas hanya dipakai untuk screenshot dan ketukan " +
                "saat klaim berjalan. Matikan layanan ini setelah selesai dan jangan buka aplikasi bank saat aktif. " +
                "Memakai bot melanggar aturan game dan bisa membuat akun dikenai sanksi.",
            14f, false, C_MUT
        )
        w.setPadding(dp(12), 0, dp(12), 0)
        col.addView(w, lpw(0, 16))
        return sv
    }

    // ---------- pembaruan tampilan tiap detik ----------

    private fun tick() {
        val total = Engine.total
        val done = Engine.doneCount
        if (tab == 0) {
            tvStatus?.text = Engine.status
            val f: Float
            if (Prefs.homeMode == 1) {
                val same = Prefs.bbcPendId == Prefs.bbcId.trim() && Prefs.bbcPendTarget > 0
                val tgt = if (same) Prefs.bbcPendTarget else Prefs.bbcAmount
                val sent = if (same) Prefs.bbcPendSent else 0
                tvProg?.text = sent.toString() + " dari " + tgt + " BBC"
                f = if (tgt > 0) (sent.toFloat() / tgt).coerceIn(0f, 1f) else 0f
            } else {
                tvProg?.text = if (total > 0) done.toString() + " dari " + total + " server" else done.toString() + " server"
                f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            }
            if (f != lastFrac) {
                lastFrac = f
                barFill?.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f)
                barRest?.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f)
            }
        } else {
            tvStatus?.text = Engine.status + " | Jumlah: " + total + " server | Selesai: " + done
        }
        val lg = tvLog
        if (lg != null) {
            val t = AppLog.text()
            if (t != lastLog) {
                lastLog = t
                lg.text = t
            }
        }
        val sv = tvSvc
        if (sv != null) {
            if (ClaimService.inst != null) {
                sv.text = "Aktif"
                sv.setTextColor(C_ACC)
            } else {
                sv.text = "Belum aktif"
                sv.setTextColor(C_MUT)
            }
        }
    }
}
