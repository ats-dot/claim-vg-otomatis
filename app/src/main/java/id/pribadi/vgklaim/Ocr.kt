package id.pribadi.vgklaim

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

class Line(val text: String, val norm: String, val r: Rect) {
    val cx: Float get() = r.exactCenterX()
    val cy: Float get() = r.exactCenterY()
}

class Scr(val bmp: Bitmap, val lines: List<Line>) {
    val w: Int = bmp.width
    val h: Int = bmp.height
    val all: String by lazy { lines.joinToString("") { it.norm } }
    val gray: IntArray by lazy { Matcher.gray(bmp) }

    fun has(vararg keys: String): Boolean = keys.any { all.contains(it) }

    fun find(key: String): Line? =
        lines.firstOrNull { it.norm == key } ?: lines.firstOrNull { it.norm.contains(key) }

    private fun same(n: String, key: String): Boolean {
        if (n == key) return true
        if (key == "ok" && n == "0k") return true
        if (key.length >= 4 && Ocr.lev(n, key) <= 1) return true
        return false
    }

    fun exact(key: String): Line? = lines.firstOrNull { same(it.norm, key) }

    fun exactAll(key: String): List<Line> = lines.filter { same(it.norm, key) }
}

object Ocr {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            if ((ch in 'a'..'z') || (ch in '0'..'9')) sb.append(ch)
        }
        return sb.toString()
    }

    fun read(bmp: Bitmap): List<Line> {
        return try {
            val t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS)
            val out = ArrayList<Line>()
            for (b in t.textBlocks) {
                for (l in b.lines) {
                    val r = l.boundingBox ?: continue
                    val n = norm(l.text)
                    if (n.isNotEmpty()) out.add(Line(l.text, n, r))
                }
            }
            out
        } catch (e: Throwable) {
            AppLog.add("OCR gagal: " + e.javaClass.simpleName)
            emptyList()
        }
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(minOf(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
