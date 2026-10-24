package id.pribadi.vgklaim

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class TemplateActivity : Activity() {

    private lateinit var sel: SelView
    private lateinit var name: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(dp(12), dp(36), dp(12), dp(12))

        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 13f
        info.text = "1) Pilih gambar: screenshot game, atau gambar yang sudah kamu potong sendiri di galeri.\n" +
            "2) Kalau screenshot, geser jari di gambar untuk mengotaki ikon. Kalau gambar sudah dipotong, tidak perlu dikotaki: seluruh gambar disimpan.\n" +
            "3) Isi nama lalu Simpan. Nama: x1, x2, ... (berawalan x = tombol tutup, diketuk otomatis). Boleh juga event (jangan diawali x). Huruf kecil, angka, garis bawah."
        root.addView(info)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val pick = bw("Pilih screenshot")
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            startActivityForResult(i, 11)
        }
        row.addView(pick, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        name = EditText(this)
        name.setTextColor(Color.WHITE)
        name.setHintTextColor(Color.GRAY)
        name.hint = "nama template (mis. x1)"
        name.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(name)

        val save = bw("Simpan potongan")
        save.setOnClickListener { doSave() }
        root.addView(save)

        sel = SelView(this)
        root.addView(sel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun bw(t: String): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        val g = GradientDrawable()
        g.setColor(Color.BLACK)
        g.setStroke(dp(1), Color.WHITE)
        g.cornerRadius = dp(12).toFloat()
        b.background = g
        return b
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 11 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri).use { ins ->
                val b = BitmapFactory.decodeStream(ins)
                if (b == null) toast("Gambar tidak bisa dibuka") else sel.setBitmap(b)
            }
        } catch (e: Throwable) {
            toast("Gagal membuka gambar")
        }
    }

    private fun doSave() {
        val n = name.text.toString().trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        if (n.isEmpty()) {
            toast("Isi nama dulu")
            return
        }
        val c = sel.crop()
        if (c == null) {
            toast("Gambar terlalu kecil (minimal 16x16 piksel)")
            return
        }
        val f = File(Matcher.dir(this), n + ".png")
        FileOutputStream(f).use { c.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Matcher.reload(this)
        toast("Tersimpan: " + n + " (" + c.width + "x" + c.height + ")")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    class SelView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private var scale = 1f
        private var ox = 0f
        private var oy = 0f
        private var sx = 0f
        private var sy = 0f
        private var ex = 0f
        private var ey = 0f
        private var has = false
        private val pRect = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        fun setBitmap(b: Bitmap) {
            bmp = b
            has = false
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val b = bmp ?: return
            scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            ox = (width - b.width * scale) / 2f
            oy = (height - b.height * scale) / 2f
            c.save()
            c.translate(ox, oy)
            c.scale(scale, scale)
            c.drawBitmap(b, 0f, 0f, null)
            c.restore()
            if (has) c.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), pRect)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.x
                    sy = e.y
                    ex = sx
                    ey = sy
                    has = true
                }
                MotionEvent.ACTION_MOVE -> {
                    ex = e.x
                    ey = e.y
                }
                MotionEvent.ACTION_UP -> {
                    ex = e.x
                    ey = e.y
                }
            }
            invalidate()
            return true
        }

        fun crop(): Bitmap? {
            val b = bmp ?: return null
            if (!has) return if (b.width >= 16 && b.height >= 16) b else null
            val x1 = ((minOf(sx, ex) - ox) / scale).toInt().coerceIn(0, b.width - 1)
            val y1 = ((minOf(sy, ey) - oy) / scale).toInt().coerceIn(0, b.height - 1)
            val x2 = ((maxOf(sx, ex) - ox) / scale).toInt().coerceIn(1, b.width)
            val y2 = ((maxOf(sy, ey) - oy) / scale).toInt().coerceIn(1, b.height)
            val w = x2 - x1
            val h = y2 - y1
            if (w < 16 || h < 16) return null
            return Bitmap.createBitmap(b, x1, y1, w, h)
        }
    }
}
