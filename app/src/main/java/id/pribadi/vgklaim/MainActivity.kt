package id.pribadi.vgklaim

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Tema tunggal: Obsidian Glass (hitam, kaca, perak)
private val C_BG = Color.parseColor("#050608")
private val C_FG = Color.parseColor("#F2F4F8")
private val C_MUT = Color.parseColor("#8B91A0")
private val C_DIV = Color.argb(22, 255, 255, 255)
private val C_POP = Color.argb(250, 20, 22, 28)
private val C_LINE = Color.argb(46, 255, 255, 255)
private val C_ACC = Color.parseColor("#EEF0F5")
private val C_ACC2 = Color.parseColor("#A3A9B6")
private val C_ONACC = Color.parseColor("#0A0B0E")

private fun easeOut() = PathInterpolator(0.32f, 0.72f, 0f, 1f)

// Kaca: isi transparan miring, kilau diagonal, dan garis tepi yang terang di sudut kiri-atas
private class GlassDrawable(private val r: Float, private val hair: Float, private val base: Int = 0) : Drawable() {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        basePaint.color = base
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = hair
    }

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat()
        val ht = b.height().toFloat()
        rect.set(hair / 2f, hair / 2f, w - hair / 2f, ht - hair / 2f)
        fill.shader = LinearGradient(
            0f, 0f, w, ht,
            intArrayOf(Color.argb(24, 255, 255, 255), Color.argb(6, 255, 255, 255), Color.argb(10, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        gloss.shader = LinearGradient(
            0f, 0f, w * 0.75f, ht,
            intArrayOf(Color.argb(24, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP
        )
        edge.shader = LinearGradient(
            0f, 0f, w, ht,
            intArrayOf(Color.argb(128, 255, 255, 255), Color.argb(18, 255, 255, 255), Color.argb(8, 255, 255, 255), Color.argb(66, 255, 255, 255)),
            floatArrayOf(0f, 0.36f, 0.64f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val sv = c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        if (Color.alpha(base) > 0) c.drawRoundRect(rect, r, r, basePaint)
        c.drawRoundRect(rect, r, r, fill)
        c.drawRoundRect(rect, r, r, gloss)
        c.drawRoundRect(rect, r, r, edge)
        c.restoreToCount(sv)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// Latar: hitam dengan cahaya lembut dari bawah dan sedikit kebiruan di kiri-atas
private class BackdropView(ctx: Context) : View(ctx) {
    private val base = Paint()
    private val g1 = Paint()
    private val g2 = Paint()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        base.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#07080B"), Color.parseColor("#030304"), Shader.TileMode.CLAMP)
        g1.shader = RadialGradient(w / 2f, h * 1.06f, w * 1.0f, Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        g2.shader = RadialGradient(w * 0.1f, -h * 0.02f, w * 0.9f, Color.argb(26, 190, 205, 255), Color.argb(0, 190, 205, 255), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, base)
        c.drawRect(0f, 0f, w, h, g1)
        c.drawRect(0f, 0f, w, h, g2)
    }
}

// Bilah progres perak yang bergerak halus
private class GlowBar(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frac = 0f
    private var anim: ValueAnimator? = null

    init {
        track.color = Color.argb(22, 255, 255, 255)
    }

    fun setFraction(f: Float, animate: Boolean) {
        val t = f.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate || width == 0) {
            frac = t
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(frac, t)
        a.duration = 650
        a.interpolator = easeOut()
        a.addUpdateListener {
            frac = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        fillP.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, C_ACC2, C_ACC, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        c.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (frac > 0f) {
            val fw = maxOf(h, w * frac)
            c.drawRoundRect(0f, 0f, fw, h, r, r, fillP)
        }
    }
}

// Saklar kaca: nyala = jalur perak dengan bulatan gelap, bergeser dengan pantulan
private class GlassSwitch(ctx: Context) : View(ctx) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var t = 0f
    private var anim: ValueAnimator? = null
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ev = ArgbEvaluator()
    private val d = ctx.resources.displayMetrics.density

    init {
        strokeP.style = Paint.Style.STROKE
        strokeP.strokeWidth = 1.2f * d
        isClickable = true
        setOnClickListener { toggle() }
    }

    fun set(v: Boolean) {
        checked = v
        t = if (v) 1f else 0f
        invalidate()
    }

    private fun toggle() {
        checked = !checked
        anim?.cancel()
        val a = ValueAnimator.ofFloat(t, if (checked) 1f else 0f)
        a.duration = 380
        a.interpolator = OvershootInterpolator(1.4f)
        a.addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
        onChange?.invoke(checked)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension((54 * d).toInt(), (32 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val ht = height.toFloat()
        val r = ht / 2f
        val tc = t.coerceIn(0f, 1f)
        trackP.color = ev.evaluate(tc, Color.argb(13, 255, 255, 255), C_ACC) as Int
        c.drawRoundRect(0f, 0f, w, ht, r, r, trackP)
        strokeP.color = ev.evaluate(tc, Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255)) as Int
        c.drawRoundRect(0.6f * d, 0.6f * d, w - 0.6f * d, ht - 0.6f * d, r, r, strokeP)
        knobP.color = ev.evaluate(tc, Color.parseColor("#C8CCD6"), Color.parseColor("#0B0C10")) as Int
        val kr = 12f * d
        val cx = 3f * d + kr + t * (w - 6f * d - 2f * kr)
        c.drawCircle(cx, ht / 2f, kr, knobP)
    }
}

// Ikon tab bergaris tipis, digambar lewat kode: 0 = rumah, 1 = log, 2 = roda gigi
private class TabIcon(ctx: Context, private val kind: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = C_MUT
    }

    fun tint(c: Int) {
        p.color = c
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save()
        c.scale(s, s)
        p.strokeWidth = 1.7f
        when (kind) {
            0 -> {
                val path = Path()
                path.moveTo(4f, 11f)
                path.lineTo(12f, 4f)
                path.lineTo(20f, 11f)
                path.lineTo(20f, 19.5f)
                path.lineTo(15f, 19.5f)
                path.lineTo(15f, 14f)
                path.lineTo(9f, 14f)
                path.lineTo(9f, 19.5f)
                path.lineTo(4f, 19.5f)
                path.close()
                c.drawPath(path, p)
            }
            1 -> {
                c.drawRoundRect(RectF(4f, 3.5f, 20f, 20.5f), 3f, 3f, p)
                c.drawLine(8f, 8.5f, 16f, 8.5f, p)
                c.drawLine(8f, 12f, 16f, 12f, p)
                c.drawLine(8f, 15.5f, 13f, 15.5f, p)
            }
            else -> {
                c.drawCircle(12f, 12f, 3.2f, p)
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4.0
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    c.drawLine(12f + 6.6f * ca, 12f + 6.6f * sa, 12f + 9f * ca, 12f + 9f * sa, p)
                }
            }
        }
        c.restore()
    }
}

// Ikon kembali (chevron)
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
        val ht = height.toFloat()
        val path = Path()
        path.moveTo(w * 0.60f, ht * 0.30f)
        path.lineTo(w * 0.40f, ht * 0.50f)
        path.lineTo(w * 0.60f, ht * 0.70f)
        c.drawPath(path, p)
    }
}

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private var tab = 0
    private var help = false

    // Beranda: Klaim VG
    private var cStatus: TextView? = null
    private var cProg: TextView? = null
    private var cBar: GlowBar? = null
    private var lastFracC = -1f

    // Beranda: Kirim BBC
    private var bStatus: TextView? = null
    private var bProg: TextView? = null
    private var bBar: GlowBar? = null
    private var lastFracB = -1f

    private var tvLog: TextView? = null
    private var tvLogStatus: TextView? = null
    private var tvSvc: TextView? = null
    private var tvBat: TextView? = null
    private var tvAuto: TextView? = null
    private var tvNotif: TextView? = null
    private var focusSink: View? = null
    private var lastLog = ""

    // Pager antar tab
    private var pager: Pager? = null
    private var pages = ArrayList<View>()
    private var pos = 0f
    private var posAnim: ValueAnimator? = null
    private var thumbAnim: ValueAnimator? = null
    private var thumbPos = 0f
    private var dockThumb: View? = null
    private var dockBtnW = 0
    private var dockIcons = ArrayList<TabIcon>()
    private var dockLabels = ArrayList<TextView>()
    private var modeViews = ArrayList<View>()
    private val argb = ArgbEvaluator()

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
        AutoAccess.ensure(applicationContext)
        // Papan ketik tidak muncul sendiri saat aplikasi dibuka atau dibuka lagi (hanya saat kotak diketuk)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        askNotif()
        render()
    }

    // Izin notifikasi (Android 13 ke atas) diminta begitu aplikasi dibuka. Sistem hanya menampilkan dialognya kalau izin belum
    // diberikan dan belum ditolak permanen; kalau sudah ditolak permanen, ketuk baris Notifikasi di Pengaturan
    private fun askNotif() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }

    // Tutup papan ketik dan lepaskan fokus dari kotak isian, supaya papan ketik tidak muncul lagi sendiri
    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            currentFocus?.clearFocus()
            focusSink?.requestFocus()
        } catch (e: Throwable) {
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val f = currentFocus
            if (f is EditText) {
                val r = Rect()
                f.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) hideKeyboard()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        AutoAccess.ensure(applicationContext)
        h.post(ticker)
    }

    override fun onPause() {
        hideKeyboard()
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

    private fun popBg(radiusDp: Int): Drawable = GlassDrawable(dp(radiusDp).toFloat(), dp(1).toFloat(), C_POP)

    private fun silverPill(radiusDp: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C_ACC, C_ACC2))
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun styleBtn(b: Button, primary: Boolean) {
        if (primary) {
            b.background = silverPill(30)
            b.setTextColor(C_ONACC)
        } else {
            b.background = GlassDrawable(dp(30).toFloat(), dp(1).toFloat())
            b.setTextColor(C_FG)
        }
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
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(110).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
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
        c.setPadding(dp(20), dp(6), dp(20), dp(6))
        c.background = GlassDrawable(dp(26).toFloat(), dp(1).toFloat())
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
        r.minimumHeight = dp(56)
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
        val sw = GlassSwitch(this)
        sw.set(get())
        sw.onChange = { v -> set(v) }
        return row(label, sw)
    }

    private fun inputBg(): StateListDrawable {
        val f = GradientDrawable()
        f.setColor(Color.argb(40, 255, 255, 255))
        f.setStroke(maxOf(1, dp(1)), Color.argb(150, 255, 255, 255))
        f.cornerRadius = dp(22).toFloat()
        val n = GradientDrawable()
        n.setColor(Color.argb(10, 255, 255, 255))
        n.setStroke(maxOf(1, dp(1)), Color.argb(41, 255, 255, 255))
        n.cornerRadius = dp(22).toFloat()
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_focused), f)
        s.addState(intArrayOf(), n)
        return s
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
        e.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        e.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        e.background = inputBg()
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

    // Pilihan geser: penanda perak meluncur dengan sedikit pantulan
    private fun seg(options: List<String>, selected: Int, heightDp: Int, textSp: Float, onPick: (Int) -> Unit): FrameLayout {
        val f = FrameLayout(this)
        val pad = dp(4)
        f.background = GlassDrawable(dp(heightDp / 2).toFloat(), dp(1).toFloat())
        f.setPadding(pad, pad, pad, pad)
        val n = options.size
        val th = View(this)
        th.background = silverPill(heightDp / 2)
        f.addView(th, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val rowV = LinearLayout(this)
        rowV.orientation = LinearLayout.HORIZONTAL
        val labels = ArrayList<TextView>()
        var cur = selected
        var segW = 0
        for (k in 0 until n) {
            val t = tv(options[k], textSp, true, if (k == selected) C_ONACC else C_FG)
            t.gravity = Gravity.CENTER
            t.setOnClickListener {
                if (cur != k) {
                    cur = k
                    for (j in labels.indices) labels[j].setTextColor(if (j == k) C_ONACC else C_FG)
                    th.animate().translationX((k * segW).toFloat()).setDuration(520).setInterpolator(OvershootInterpolator(0.9f)).start()
                    onPick(k)
                }
            }
            labels.add(t)
            rowV.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        f.addView(rowV, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        f.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = (r - l - 2 * pad) / n
            if (w > 0 && w != segW) {
                segW = w
                th.layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                th.translationX = (cur * w).toFloat()
            }
        }
        return f
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
        val t = tv(title, 13f, true, C_MUT)
        t.isAllCaps = true
        t.letterSpacing = 0.06f
        t.setPadding(dp(8), 0, 0, dp(10))
        c.addView(t)
        c.addView(body)
        return c
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(8), dp(18), dp(124))
        sv.addView(col)
        return Pair(sv, col)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- kerangka: header, pager, dock ----------

    private fun header(title: String, menu: Boolean, back: Boolean): View {
        val hd = LinearLayout(this)
        hd.orientation = LinearLayout.HORIZONTAL
        hd.gravity = Gravity.CENTER_VERTICAL
        hd.setPadding(dp(26), dp(10), dp(14), 0)
        hd.minimumHeight = dp(70)
        if (back) {
            val b = BackIcon(this)
            b.contentDescription = "Kembali"
            b.setOnClickListener {
                help = false
                render()
            }
            val bp = LinearLayout.LayoutParams(dp(48), dp(48))
            bp.leftMargin = -dp(14)
            hd.addView(b, bp)
        }
        hd.addView(tv(title, 30f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (menu) {
            val m = TextView(this)
            m.text = "\u22EE"
            m.textSize = 28f
            m.setTextColor(C_MUT)
            m.gravity = Gravity.CENTER
            m.contentDescription = "Menu"
            m.setOnClickListener { showMenu() }
            hd.addView(m, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        return hd
    }

    // Posisi halaman (0 sampai 2, boleh pecahan saat digeser). Semua halaman, dock, dan ikon mengikuti angka ini.
    private fun applyPos(p: Float, drag: Boolean) {
        pos = p
        val w = (pager?.width ?: 0).toFloat()
        for (i in pages.indices) {
            val d = i - p
            val a = Math.abs(d)
            val v = pages[i]
            if (a >= 1f) {
                v.visibility = View.INVISIBLE
            } else {
                v.visibility = View.VISIBLE
                v.translationX = d * w
                v.alpha = 1f - 0.65f * a
                val s = 1f - 0.05f * a
                v.scaleX = s
                v.scaleY = s
            }
        }
        if (drag) setThumb(p)
        for (i in dockIcons.indices) {
            val k = (1f - Math.abs(i - p)).coerceIn(0f, 1f)
            val col = argb.evaluate(k, C_MUT, C_FG) as Int
            dockIcons[i].tint(col)
            dockLabels[i].setTextColor(col)
        }
        tab = Math.round(p).coerceIn(0, 2)
    }

    private fun setThumb(p: Float) {
        thumbPos = p
        dockThumb?.translationX = p * dockBtnW
    }

    private fun goTo(i: Int) {
        posAnim?.cancel()
        thumbAnim?.cancel()
        val a = ValueAnimator.ofFloat(pos, i.toFloat())
        a.duration = (360 + 160 * Math.abs(i - pos)).toLong()
        a.interpolator = easeOut()
        a.addUpdateListener { applyPos(it.animatedValue as Float, false) }
        a.start()
        posAnim = a
        val b = ValueAnimator.ofFloat(thumbPos, i.toFloat())
        b.duration = 560
        b.interpolator = OvershootInterpolator(1.0f)
        b.addUpdateListener { setThumb(it.animatedValue as Float) }
        b.start()
        thumbAnim = b
    }

    private inner class Pager(ctx: Context) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var x0 = 0f
        private var y0 = 0f
        private var drag = false
        private var startPos = 0f
        private var vt: VelocityTracker? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x
                    y0 = e.y
                    drag = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    vt?.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.x - x0
                    val dy = e.y - y0
                    if (!drag && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                        drag = true
                        startPos = pos
                        posAnim?.cancel()
                        thumbAnim?.cancel()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        x0 = e.x
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!drag) {
                        vt?.recycle()
                        vt = null
                    }
                }
            }
            return drag
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (drag && width > 0) {
                        var p = startPos - (e.x - x0) / width
                        if (p < 0f) p *= 0.35f
                        if (p > 2f) p = 2f + (p - 2f) * 0.35f
                        applyPos(p, true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drag) {
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.xVelocity ?: 0f
                        val start = Math.round(startPos)
                        val moved = pos - start
                        var target = start
                        if (v < -700f || moved > 0.22f) target = start + 1
                        else if (v > 700f || moved < -0.22f) target = start - 1
                        goTo(target.coerceIn(0, 2))
                    }
                    drag = false
                    vt?.recycle()
                    vt = null
                }
            }
            return true
        }
    }

    private fun buildDock(): FrameLayout {
        val dockW = Math.min(resources.displayMetrics.widthPixels - dp(36), dp(400))
        dockBtnW = (dockW - dp(12)) / 3
        val dock = FrameLayout(this)
        dock.background = GlassDrawable(dp(35).toFloat(), dp(1).toFloat(), Color.argb(250, 12, 13, 17))
        val th = View(this)
        val tg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(15, 255, 255, 255)))
        tg.cornerRadius = dp(29).toFloat()
        tg.setStroke(maxOf(1, dp(1)), Color.argb(34, 255, 255, 255))
        th.background = tg
        val tp = FrameLayout.LayoutParams(dockBtnW, dp(58))
        tp.leftMargin = dp(6)
        tp.topMargin = dp(6)
        dock.addView(th, tp)
        dockThumb = th

        val rowV = LinearLayout(this)
        rowV.orientation = LinearLayout.HORIZONTAL
        rowV.setPadding(dp(6), dp(6), dp(6), dp(6))
        dockIcons = ArrayList()
        dockLabels = ArrayList()
        val names = listOf("Beranda", "Log", "Pengaturan")
        for (i in names.indices) {
            val b = LinearLayout(this)
            b.orientation = LinearLayout.VERTICAL
            b.gravity = Gravity.CENTER
            val ic = TabIcon(this, i)
            val lb = tv(names[i], 11.5f, true, C_MUT)
            lb.gravity = Gravity.CENTER
            b.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
            b.addView(lb, lpw(0, 3))
            dockIcons.add(ic)
            dockLabels.add(lb)
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
            b.setOnClickListener { goTo(i) }
            rowV.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        dock.addView(rowV, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(dockW, dp(70))
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = dp(16)
        dock.layoutParams = lp
        return dock
    }

    private fun render() {
        window.statusBarColor = Color.parseColor("#07080B")
        window.navigationBarColor = Color.parseColor("#030304")
        cStatus = null
        cProg = null
        cBar = null
        bStatus = null
        bProg = null
        bBar = null
        tvLog = null
        tvLogStatus = null
        tvSvc = null
        tvBat = null
        tvAuto = null
        tvNotif = null
        lastFracC = -1f
        lastFracB = -1f
        posAnim?.cancel()
        thumbAnim?.cancel()

        val root = FrameLayout(this)
        root.setBackgroundColor(C_BG)
        // Root memegang fokus supaya tidak ada kotak isian yang otomatis terfokus (papan ketik muncul sendiri)
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        focusSink = root
        root.addView(BackdropView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (help) {
            val colH = LinearLayout(this)
            colH.orientation = LinearLayout.VERTICAL
            colH.setPadding(0, dp(28), 0, 0)
            colH.addView(header("Fungsi pengaturan", false, true))
            colH.addView(helpPage(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(colH, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(root)
            return
        }

        val pg = Pager(this)
        pager = pg
        pages = ArrayList()
        pages.add(pageHome())
        pages.add(pageLog())
        pages.add(pageSettings())
        for (p in pages) pg.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val pgp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        pgp.topMargin = dp(28)
        root.addView(pg, pgp)

        val dock = buildDock()
        root.addView(dock)

        var lastW = -1
        pg.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            if (r - l != lastW) {
                lastW = r - l
                applyPos(tab.toFloat(), true)
            }
        }
        applyPos(tab.toFloat(), true)

        // Saat keyboard tampil, dock disembunyikan (tidak ikut naik di atas keyboard)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val sh = root.rootView.height
            dock.visibility = if (sh - r.bottom > sh * 0.15f) View.GONE else View.VISIBLE
        }
        setContentView(root)
        root.requestFocus()
        tick()
    }

    // ---------- Beranda: pilihan Klaim VG / Kirim BBC ----------

    private fun pageHome(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val ms = seg(listOf("Klaim VG", "Kirim BBC"), Prefs.homeMode, 60, 17f) { m -> selectMode(m, true) }
        val mp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60))
        mp.setMargins(dp(18), dp(14), dp(18), dp(4))
        col.addView(ms, mp)
        val frame = FrameLayout(this)
        modeViews = arrayListOf<View>(home(), bbcHome())
        frame.addView(modeViews[0], FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(modeViews[1], FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectMode(Prefs.homeMode, false)
        return col
    }

    private fun selectMode(m: Int, animate: Boolean) {
        Prefs.homeMode = m
        for (i in modeViews.indices) {
            val v = modeViews[i]
            val on = i == m
            val tx = if (on) 0f else if (i < m) -dp(28).toFloat() else dp(28).toFloat()
            if (!animate) {
                v.alpha = if (on) 1f else 0f
                v.translationX = tx
                v.visibility = if (on) View.VISIBLE else View.INVISIBLE
            } else {
                if (on) v.visibility = View.VISIBLE
                v.animate().alpha(if (on) 1f else 0f).translationX(tx).setDuration(420).setInterpolator(easeOut())
                    .withEndAction { if (!on) v.visibility = View.INVISIBLE }.start()
            }
        }
        tick()
    }

    private fun statusCard(label: String, status: TextView, prog: TextView, bar: GlowBar): View {
        val stBox = LinearLayout(this)
        stBox.orientation = LinearLayout.VERTICAL
        stBox.setPadding(0, dp(14), 0, dp(18))
        stBox.addView(tv("Status", 14f, false, C_MUT))
        stBox.addView(status, lpw(0, 2))
        stBox.addView(divider(), lpw(1, 14))
        stBox.addView(tv(label, 14f, false, C_MUT), lpw(0, 14))
        stBox.addView(prog, lpw(0, 2))
        stBox.addView(bar, lpw(9, 12))
        return card(stBox)
    }

    private fun home(): View {
        val (sv, col) = page()

        val st = tv("", 30f, true, C_FG)
        cStatus = st
        val prog = tv("", 18f, false, C_FG)
        cProg = prog
        val bar = GlowBar(this)
        cBar = bar
        col.addView(statusCard("Selesai", st, prog, bar), lpw(0, 4))

        val cnt = numBox(if (Prefs.serverCount > 0) Prefs.serverCount.toString() else "") { t ->
            t.trim().toIntOrNull()?.let { Prefs.serverCount = it }
        }
        cnt.hint = "Wajib diisi"
        col.addView(
            card(
                row("Jumlah server", cnt), divider(),
                switchRow("Topup", { Prefs.topUp }, { Prefs.topUp = it })
            ), lpw(0, 14)
        )

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(btn("MULAI KLAIM", true) {
            val n = cnt.text.toString().trim().toIntOrNull() ?: 0
            if (n < 2) {
                toast("Isi Jumlah server dulu (angka 2 atau lebih)")
                return@btn
            }
            Prefs.serverCount = n
            begin(0, "Klaim dimulai")
        }, lpw(60, 16))
        col.addView(btn("BERHENTI") { Engine.stop() }, lpw(60, 10))
        return sv
    }

    // Mulai proses: nyalakan Aksesibilitas otomatis bila izin sudah diberikan, lalu jalankan
    private fun begin(mode: Int, okMsg: String) {
        hideKeyboard()
        askNotif()
        val changed = AutoAccess.ensure(applicationContext)
        val go = {
            val r = if (mode == 1) Engine.start(applicationContext, 1) else Engine.start(applicationContext)
            toast(r ?: okMsg)
        }
        // Kalau Aksesibilitas baru dinyalakan (atau sedang tersambung), tunggu sampai layanan benar-benar tersambung, maksimal 10 detik.
        // Begitu tersambung, langsung jalan
        if (changed || (ClaimService.inst == null && AutoAccess.granted(applicationContext))) {
            toast("Menyalakan Aksesibilitas...")
            var waited = 0
            val poll = object : Runnable {
                override fun run() {
                    if (ClaimService.inst != null) {
                        go()
                        return
                    }
                    waited += 100
                    if (waited >= 10000) {
                        toast("Aksesibilitas belum tersambung, coba lagi")
                        return
                    }
                    h.postDelayed(this, 100)
                }
            }
            h.postDelayed(poll, 100)
        } else {
            go()
        }
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
        e.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        e.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        e.background = inputBg()
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

        val st = tv("", 30f, true, C_FG)
        bStatus = st
        val prog = tv("", 18f, false, C_FG)
        bProg = prog
        val bar = GlowBar(this)
        bBar = bar
        col.addView(statusCard("Terkirim", st, prog, bar), lpw(0, 4))

        val idBox = textBox(Prefs.bbcId, "ID penerima", true) { t -> Prefs.bbcId = t.trim() }
        val nickBox = textBox(Prefs.bbcNick, "Nick penerima", false) { t -> Prefs.bbcNick = t }
        // Diisi dalam ribuan (k): 20 berarti 20k = 20.000 BBC. Yang tersimpan tetap BBC penuh
        val amtBox = numBox(if (Prefs.bbcAmount >= 1000) (Prefs.bbcAmount / 1000).toString() else "") { t ->
            Prefs.bbcAmount = (t.trim().toIntOrNull() ?: 0) * 1000
            tick()
        }
        amtBox.hint = "mis. 20"
        val amtWrap = LinearLayout(this)
        amtWrap.orientation = LinearLayout.HORIZONTAL
        amtWrap.gravity = Gravity.CENTER_VERTICAL
        amtWrap.addView(amtBox)
        val kTv = tv("k", 17f, true, C_MUT)
        kTv.setPadding(dp(8), 0, 0, 0)
        amtWrap.addView(kTv)
        val cnt = numBox(if (Prefs.serverCount > 0) Prefs.serverCount.toString() else "") { t ->
            t.trim().toIntOrNull()?.let { Prefs.serverCount = it }
        }
        val startBox = numBox(if (Prefs.bbcStartFrom > 0) Prefs.bbcStartFrom.toString() else "") { t ->
            Prefs.bbcStartFrom = t.trim().toIntOrNull() ?: 0
        }
        startBox.hint = "mis. 57102"
        col.addView(
            card(
                row("ID penerima", idBox), divider(),
                row("Nick", nickBox), divider(),
                switchRow("Cocokkan nick", { Prefs.bbcMatchNick }, { Prefs.bbcMatchNick = it }), divider(),
                row("Jumlah BBC", amtWrap), divider(),
                switchRow("Klaim dulu", { Prefs.bbcClaimFirst }, { Prefs.bbcClaimFirst = it }), divider(),
                row("Jumlah server", cnt), divider(),
                row("Mulai dari server", startBox)
            ),
            lpw(0, 14)
        )

        val spacer = View(this)
        col.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(btn("MULAI KIRIM", true) { begin(1, "Kirim BBC dimulai") }, lpw(60, 16))
        col.addView(btn("BERHENTI") { Engine.stop() }, lpw(60, 10))
        return sv
    }

    // ---------- tab Log ----------

    private fun pageLog(): View {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.addView(header("Log", false, false))
        outer.addView(logTab(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return outer
    }

    private fun logTab(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(4), dp(18), dp(112))

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
        tvLogStatus = st
        col.addView(st, lpw(0, 10))

        val t = tv("", 12f)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        t.setPadding(dp(16), dp(14), dp(16), dp(14))
        tvLog = t
        val sv = ScrollView(this)
        sv.background = GlassDrawable(dp(26).toFloat(), dp(1).toFloat())
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

    private fun pageSettings(): View {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.addView(header("Pengaturan", true, false))
        outer.addView(settings(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return outer
    }

    private fun settings(): View {
        val (sv, col) = page()

        // Izin
        val svc = tv("", 15f, false, C_MUT)
        tvSvc = svc
        val bat = tv("", 15f, false, C_MUT)
        tvBat = bat
        val auto = tv("", 15f, false, C_MUT)
        tvAuto = auto
        val notif = tv("", 15f, false, C_MUT)
        tvNotif = notif
        col.addView(
            section(
                "Izin",
                card(
                    linkRow("Aksesibilitas", svc) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    divider(),
                    linkRow("Nyala otomatis", auto) { showAutoInfo() },
                    divider(),
                    linkRow("Notifikasi", notif) {
                        try {
                            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            startActivity(i)
                        } catch (e: Exception) {
                            toast("Tidak bisa dibuka: " + e.javaClass.simpleName)
                        }
                    },
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
        col.addView(section("Game", card(linkRow("Aplikasi game", gv) { pickGame(gv) })), lpw(0, 20))

        // Klaim
        val sm = numBox(Prefs.stepMs.toString())
        val lw = numBox(Prefs.lobbyWait.toString())
        val afterLabel = tv("Setelah selesai", 16f)
        afterLabel.setPadding(0, dp(14), 0, dp(10))
        val afterSeg = seg(listOf("Klaim VG", "Home", "Tetap di game"), Prefs.afterDone - 1, 46, 14f) { k -> Prefs.afterDone = k + 1 }
        val afterBox = LinearLayout(this)
        afterBox.orientation = LinearLayout.VERTICAL
        afterBox.setPadding(0, 0, 0, dp(14))
        afterBox.addView(afterLabel)
        afterBox.addView(afterSeg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        val saveBox = LinearLayout(this)
        saveBox.orientation = LinearLayout.VERTICAL
        saveBox.setPadding(0, dp(12), 0, dp(12))
        saveBox.addView(btn("Simpan") {
            Prefs.stepMs = sm.text.toString().trim().toIntOrNull() ?: 0
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
            ), lpw(0, 20)
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
        col.addView(section("Geser daftar server", card(*gviews.toTypedArray())), lpw(0, 20))

        col.addView(
            section(
                "Kirim BBC",
                card(
                    row("Level server minimal", numBox(Prefs.bbcMinLevel.toString()) { t -> t.trim().toIntOrNull()?.let { Prefs.bbcMinLevel = it } }),
                    divider(),
                    linkRow("Data server akun", tv("Buka", 15f, true, C_ACC)) { showBbcData() }
                )
            ),
            lpw(0, 20)
        )

        // Data
        col.addView(
            section("Data hari ini", btn("Ulangi dari awal") {
                Prefs.resetDone()
                toast("Catatan dihapus")
            }.also { it.layoutParams = lpw(52, 0) }), lpw(0, 20)
        )
        return sv
    }

    // Cara memberi izin sekali supaya Aksesibilitas menyala sendiri
    private fun showAutoInfo() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(20), dp(22), dp(16))
        box.background = popBg(28)
        box.addView(tv("Nyala otomatis", 20f, true))
        val ok = AutoAccess.granted(applicationContext)
        box.addView(
            tv(
                if (ok) "Izin sudah diberikan. Aksesibilitas akan dinyalakan sendiri tiap aplikasi dibuka atau saat Mulai ditekan."
                else "Berikan izin ini satu kali lewat Shizuku atau ADB, supaya Aksesibilitas tidak perlu dinyalakan manual lagi. Izin bertahan sampai aplikasi di-uninstall.",
                14f, false, C_MUT
            ), lpw(0, 8)
        )
        val cmd = tv(AutoAccess.command(applicationContext), 13f)
        cmd.typeface = Typeface.MONOSPACE
        cmd.setTextIsSelectable(true)
        cmd.setPadding(dp(14), dp(12), dp(14), dp(12))
        cmd.background = shape(Color.argb(14, 255, 255, 255), C_LINE, 16, 1f)
        box.addView(cmd, lpw(0, 14))
        val bb = LinearLayout(this)
        bb.orientation = LinearLayout.HORIZONTAL
        val b1 = btn("Salin perintah") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("perintah", AutoAccess.command(applicationContext)))
            toast("Perintah disalin")
        }
        val b2 = btn("Tutup", true) { d.dismiss() }
        val q1 = LinearLayout.LayoutParams(0, dp(48), 1f)
        q1.setMargins(0, 0, dp(4), 0)
        val q2 = LinearLayout.LayoutParams(0, dp(48), 1f)
        q2.setMargins(dp(4), 0, 0, 0)
        bb.addView(b1, q1)
        bb.addView(b2, q2)
        box.addView(bb, lpw(0, 16))
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    // Daftar akun pengirim yang punya catatan server terpakai; hapus per akun atau semuanya
    private fun showBbcData() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(20), dp(22), dp(14))
        box.background = popBg(28)
        box.addView(tv("Data server Kirim BBC", 20f, true))
        box.addView(tv("Akun yang pernah dipakai kirim dan server yang tercatat. Hapus kalau akun itu bisa dipakai kirim lagi.", 13f, false, C_MUT), lpw(0, 4))
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        fun confirm(msg: String, act: () -> Unit) {
            if (Engine.running) {
                toast("Hentikan dulu proses yang berjalan")
                return
            }
            AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Hapus") { _, _ -> act() }
                .setNegativeButton("Batal", null)
                .show()
        }
        lateinit var fill: () -> Unit
        fill = {
            list.removeAllViews()
            // Pesanan yang sedang berjalan (belum selesai)
            val pid = Prefs.bbcPendId
            val pend = LinearLayout(this)
            pend.orientation = LinearLayout.VERTICAL
            pend.setPadding(0, dp(10), 0, dp(10))
            pend.addView(tv("Pesanan berjalan", 16f, true))
            if (pid.isEmpty()) {
                pend.addView(tv("Tidak ada pesanan yang belum selesai", 13f, false, C_MUT), lpw(0, 2))
            } else {
                pend.addView(
                    tv("ID " + pid + ": terkirim " + Prefs.fmt(Prefs.bbcPendSent) + " dari " + Prefs.fmt(Prefs.bbcPendTarget) + " BBC", 13f, false, C_MUT),
                    lpw(0, 2)
                )
                val delP = tv("Hapus pesanan", 15f, true, C_ACC)
                delP.setPadding(0, dp(10), 0, dp(4))
                delP.setOnClickListener {
                    confirm("Hapus catatan pesanan ke ID " + pid + "? Hitungan terkirim kembali ke nol.") {
                        Prefs.bbcClearPending()
                        toast("Pesanan dihapus")
                        tick()
                        fill()
                    }
                }
                pend.addView(delP)
            }
            list.addView(pend)
            list.addView(divider())
            val accts = Prefs.bbcAccounts()
            if (accts.isEmpty()) {
                list.addView(tv("Belum ada catatan.", 15f, false, C_MUT), lpw(0, 12))
            }
            for (i in accts.indices) {
                val (id, names) = accts[i]
                if (i > 0) list.addView(divider())
                val rowV = LinearLayout(this)
                rowV.orientation = LinearLayout.VERTICAL
                rowV.setPadding(0, dp(10), 0, dp(10))
                rowV.addView(tv("ID " + id + ": " + names.size + " server", 16f, true))
                val last = Prefs.bbcLast(id)
                val sub = (if (names.isEmpty()) "Belum ada server tercatat" else names.joinToString(", ")) +
                    (if (last.isNotEmpty()) "\nTerakhir dipakai: " + last else "")
                rowV.addView(tv(sub, 13f, false, C_MUT), lpw(0, 2))
                val del = tv("Hapus data akun ini", 15f, true, C_ACC)
                del.setPadding(0, dp(10), 0, dp(4))
                del.setOnClickListener {
                    confirm("Hapus catatan server akun " + id + "?") {
                        Prefs.bbcClearUsed(id)
                        toast("Data akun dihapus")
                        fill()
                    }
                }
                rowV.addView(del)
                list.addView(rowV)
            }
        }
        fill()
        val sv = ScrollView(this)
        sv.addView(list)
        val svp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        svp.topMargin = dp(8)
        box.addView(sv, svp)
        val bb = LinearLayout(this)
        bb.orientation = LinearLayout.HORIZONTAL
        val b1 = btn("Hapus semua") {
            confirm("Hapus catatan server SEMUA akun?") {
                Prefs.bbcClearAllUsed()
                toast("Semua data dihapus")
                fill()
            }
        }
        val b2 = btn("Tutup", true) { d.dismiss() }
        val q1 = LinearLayout.LayoutParams(0, dp(48), 1f)
        q1.setMargins(0, 0, dp(4), 0)
        val q2 = LinearLayout.LayoutParams(0, dp(48), 1f)
        q2.setMargins(dp(4), 0, 0, 0)
        bb.addView(b1, q1)
        bb.addView(b2, q2)
        val bp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bp.topMargin = dp(12)
        box.addView(bb, bp)
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        }
        d.show()
    }

    private fun pickGame(label: TextView) {
        var apps = Engine.listApps(this, false)
        if (apps.isEmpty()) apps = Engine.listApps(this, true)
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(20), dp(22), dp(12))
        box.background = popBg(28)
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
        box.background = popBg(24)
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
        "Aksesibilitas" to "Layanan yang dipakai untuk screenshot dan ketukan. Harus aktif. Nyalakan lagi setelah memasang pembaruan, atau pakai Nyala otomatis.",
        "Nyala otomatis" to "Kalau izin WRITE_SECURE_SETTINGS sudah diberikan satu kali lewat Shizuku atau ADB, aplikasi menyalakan Aksesibilitasnya sendiri tiap dibuka dan saat Mulai ditekan. Ketuk barisnya untuk melihat dan menyalin perintahnya. Izin bertahan sampai aplikasi di-uninstall.",
        "Notifikasi" to "Izin notifikasi diminta saat aplikasi pertama dibuka. Kalau pernah ditolak, ketuk barisnya untuk membuka pengaturan notifikasi aplikasi dan mengizinkannya. Tanpa izin ini, notifikasi berjalan dan tombol Berhenti di notifikasi tidak tampil.",
        "Papan ketik" to "Papan ketik ditutup dan fokus dilepas dari kotak isian saat tombol Selesai di papan ketik ditekan, saat layar di luar kotak diketuk, saat Mulai ditekan, dan saat aplikasi ditinggalkan. Papan ketik tidak muncul sendiri saat aplikasi dibuka lagi.",
        "Baterai" to "Atur ke tanpa batasan supaya sistem tidak membatasi kecepatan baca layar.",
        "Aplikasi game" to "Pilih aplikasi Mobile Legends yang dibuka bot.",
        "Jumlah server" to "Isi jumlah semua server akun ini, termasuk server yang sedang dipakai (awalnya 20). Wajib diisi. Bot terus mencari sampai semua server terlihat, dan tidak berhenti sebelum itu.",
        "Topup" to "Di Beranda, Klaim VG. Kalau aktif, di tiap server sesudah semua klaim selesai dan sebelum pindah ke server berikutnya, bot membeli 500 Diamond (bonus Top Up Pertama) dengan Vanguard Coin hasil klaim. Kalau Vanguard Coin kurang atau bonus server itu sudah terpakai, bot mencatatnya di log lalu lanjut pindah server. Menambah sekitar 6 sampai 10 detik per server.",
        "Setelah selesai" to "Yang dilakukan bot setelah semua server selesai. Klaim VG: langsung membuka aplikasi ini. Home: langsung ke layar Home. Tetap di game: kembali ke lobby dan tetap di game. Game tidak ditutup dan tidak dibuang dari Recents. Banner hasil klaim selalu tampil.",
        "Lewati server di atas posisi sekarang" to "Server yang urutannya di atas server awal dianggap sudah selesai dan tidak diklaim.",
        "Klaim Verifikasi Usia" to "Kalau aktif, di tiap server bot juga mengklaim Verifikasi Usia (satu kali per server): Klaim Semua, Gunakan sekarang, geser peti ke kartu Vanguard Coin, tombol +, Konfirmasi, lalu OK. Hasilnya Vanguard Coin 300. Kalau sudah diklaim atau menunya tidak ada, bot langsung lanjut.",
        "Titik melayang saat klaim" to "Titik tak terlihat yang membuat baca layar sekitar 10 kali lebih cepat. Gayanya dipilih lewat Diagnosa umum.",
        "Jeda antar langkah" to "Lama bot menunggu setelah tiap ketukan sebelum melihat layar lagi, dalam milidetik. 0 berarti tanpa jeda.",
        "Nomor server pertama dan terakhir" to "Hanya nomor server dalam rentang ini (awalnya 57092 sampai 57115) yang dianggap server. Nama yang kehilangan satu digit karena salah baca (misalnya s5094) diperbaiki otomatis kalau hanya ada satu kemungkinan, dan kalau tidak, barisnya diabaikan. Ubah kalau game menambah server.",
        "Kirim BBC" to "Pilih Kirim BBC di Beranda. Isi ID penerima, nick (nyalakan Cocokkan nick untuk memastikan akun yang dikirim benar, matikan untuk memilih hasil pencarian paling atas), Jumlah BBC (diisi dalam ribuan: 20 berarti 20k = 20.000 BBC), dan Jumlah server akun pengirim. Tiap server mengirim satu Yacht (1000 diamond = 1000 BBC): bot membeli 500 Diamond dengan Vanguard Coin bila diamond di lobby kurang dari 1000, mencari akun penerima lewat ID, lalu mengirim Yacht. Klaim dulu: klaim di tiap server sebelum kirim. Server yang sudah dipakai kirim diingat per akun pengirim. Kalau server di akun habis sebelum target tercapai, ganti akun lalu tekan Mulai kirim lagi: bot melanjutkan sisanya. Mulai dari server (kosong = mati): server bernomor lebih kecil dianggap sudah terpakai dan tidak dikunjungi, berguna untuk akun yang bonus Top Up Pertamanya sudah terpakai sampai nomor tertentu. Level server minimal (awalnya 15): server pertama di akun dicek dari angka di bawah nama pemain di lobby, server berikutnya dicek dari Daftar Karakter saat dipilih. Server yang levelnya kurang dilewati tanpa membelanjakan koin. Screenshot before.png dan after.png disimpan di Pictures/VGKlaim.",
        "Waktu tunggu lobby" to "Dalam milidetik. Dipakai hanya kalau lobby langsung muncul setelah loading tanpa popup apa pun: bot menunggu paling lama selama ini untuk popup. Begitu satu popup muncul dan ditutup, bot langsung lanjut. Kalau popup sudah muncul dan ditutup sebelum lobby, waktu ini dilewati.",
        "Geser daftar server" to "Geser cepat memuat server baru dan HANYA dipakai sekali, saat pertama mencari server baru di daftar yang baru dibuka. Sesudah itu bot hanya geser pelan. Geser pelan menggeser daftar maju. Geser balik menggeser daftar mundur, hanya kalau tidak ada server lama yang terlihat. Angka awal dan akhir adalah persen tinggi layar: 0 di atas, 100 di bawah. Uji sampai bawah: isi Jumlah server (uji), buka Daftar Karakter, tekan tombolnya, lalu pindah ke game. Bot terus menggeser sampai semua server terlihat dan melaporkan hasilnya.",
        "Diagnosa" to "Tombol Diagnosa di tab Log mengukur kecepatan baca layar (OCR) dan memilih gaya titik melayang. Untuk menguji geser daftar, pakai tombol Uji sampai bawah di bagian Geser daftar server.",
        "Data server akun" to "Di Pengaturan, bagian Kirim BBC. Menampilkan akun pengirim yang punya catatan server yang sudah dipakai kirim BBC. Hapus per akun atau semuanya kalau akun itu bisa dipakai kirim lagi. Di bagian atas dialog ada Pesanan berjalan (ID penerima, terkirim dari target) dengan tombol Hapus pesanan untuk memulai pesanan dari nol. Catatan tidak direset otomatis: kalau semua server akun sudah terpakai, bot berhenti dengan pesan Server di akun ini habis.",
        "Ulangi dari awal" to "Menghapus catatan server yang sudah selesai hari ini."
    )

    private fun helpPage(): View {
        val (sv, col) = page()
        col.setPadding(dp(18), dp(8), dp(18), dp(24))
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

    private fun statusFor(m: Int): String {
        if (Engine.running && Engine.mode != m) return "Proses lain sedang berjalan"
        return Engine.status
    }

    private fun tick() {
        val total = Engine.total
        val done = Engine.doneCount

        // Klaim VG
        cStatus?.text = statusFor(0)
        cProg?.text = if (total > 0) done.toString() + " dari " + total + " server" else done.toString() + " server"
        val fc = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        if (fc != lastFracC) {
            cBar?.setFraction(fc, lastFracC >= 0f)
            lastFracC = fc
        }

        // Kirim BBC
        bStatus?.text = statusFor(1)
        val same = Prefs.bbcPendId == Prefs.bbcId.trim() && Prefs.bbcPendTarget > 0
        val tgt = if (same) Prefs.bbcPendTarget else Prefs.bbcAmount
        val sent = if (same) Prefs.bbcPendSent else 0
        bProg?.text = Prefs.fmt(sent) + " dari " + Prefs.fmt(tgt) + " BBC"
        val fb = if (tgt > 0) (sent.toFloat() / tgt).coerceIn(0f, 1f) else 0f
        if (fb != lastFracB) {
            bBar?.setFraction(fb, lastFracB >= 0f)
            lastFracB = fb
        }

        tvLogStatus?.text = Engine.status + " | Jumlah: " + total + " server | Selesai: " + done

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
        val au = tvAuto
        if (au != null) {
            if (AutoAccess.granted(applicationContext)) {
                au.text = "Siap"
                au.setTextColor(C_ACC)
            } else {
                au.text = "Perlu izin"
                au.setTextColor(C_MUT)
            }
        }
        val nn = tvNotif
        if (nn != null) {
            val on = try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
            } catch (e: Exception) {
                true
            }
            nn.text = if (on) "Diizinkan" else "Belum diizinkan"
            nn.setTextColor(if (on) C_ACC else C_MUT)
        }
        val bt = tvBat
        if (bt != null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val ok = pm.isIgnoringBatteryOptimizations(packageName)
                bt.text = if (ok) "Tanpa batasan" else "Belum diatur"
                bt.setTextColor(if (ok) C_ACC else C_MUT)
            } catch (e: Exception) {
                bt.text = "Tidak diketahui"
            }
        }
    }
}
