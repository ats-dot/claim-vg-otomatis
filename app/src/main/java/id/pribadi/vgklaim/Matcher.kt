package id.pribadi.vgklaim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File

object Matcher {
    class Tpl(val name: String, val w: Int, val h: Int, val px: IntArray)
    class Hit(val x: Float, val y: Float, val score: Double)

    private val cache = HashMap<String, Tpl>()
    var threshold = 0.88

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "tpl")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun names(ctx: Context): List<String> {
        val fs = dir(ctx).listFiles() ?: return emptyList()
        return fs.filter { it.extension == "png" }.map { it.nameWithoutExtension }.sorted()
    }

    @Synchronized
    fun reload(ctx: Context) {
        cache.clear()
        val fs = dir(ctx).listFiles() ?: return
        for (f in fs) {
            if (f.extension != "png") continue
            val b = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            cache[f.nameWithoutExtension] = Tpl(f.nameWithoutExtension, b.width, b.height, gray(b))
        }
    }

    fun gray(b: Bitmap): IntArray {
        val w = b.width
        val h = b.height
        val p = IntArray(w * h)
        b.getPixels(p, 0, w, 0, 0, w, h)
        for (i in p.indices) {
            val c = p[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val bl = c and 255
            p[i] = (r * 299 + g * 587 + bl * 114) / 1000
        }
        return p
    }

    @Synchronized
    fun has(name: String): Boolean = cache.containsKey(name)

    @Synchronized
    fun find(s: Scr, name: String): Hit? {
        val t = cache[name] ?: return null
        return search(s, t)
    }

    @Synchronized
    fun findPrefix(s: Scr, prefix: String, region: Rect? = null): Hit? {
        var best: Hit? = null
        for ((k, t) in cache) {
            if (!k.startsWith(prefix)) continue
            val h = search(s, t, region) ?: continue
            if (best == null || h.score > best.score) best = h
        }
        return best
    }

    private fun down(src: IntArray, w: Int, h: Int, k: Int): IntArray {
        val nw = w / k
        val nh = h / k
        val out = IntArray(nw * nh)
        val area = k * k
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                var sum = 0
                for (j in 0 until k) {
                    val o = (y * k + j) * w + x * k
                    for (i in 0 until k) sum += src[o + i]
                }
                out[y * nw + x] = sum / area
            }
        }
        return out
    }

    private fun sad(sc: IntArray, cw: Int, x: Int, y: Int, tc: IntArray, tw: Int, th: Int, limit: Int): Int {
        var sum = 0
        for (j in 0 until th) {
            val so = (y + j) * cw + x
            val to = j * tw
            for (i in 0 until tw) {
                val d = sc[so + i] - tc[to + i]
                sum += if (d < 0) -d else d
            }
            if (sum >= limit) return sum
        }
        return sum
    }

    private fun search(s: Scr, t: Tpl, region: Rect? = null): Hit? {
        val k = 4
        val sw = s.w
        val sh = s.h
        if (t.w < 16 || t.h < 16 || t.w > sw || t.h > sh) return null
        val cw = sw / k
        val ch = sh / k
        val tw = t.w / k
        val th = t.h / k
        if (tw < 3 || th < 3 || tw > cw || th > ch) return null
        val sc = down(s.gray, sw, sh, k)
        val tc = down(t.px, t.w, t.h, k)
        var best = Int.MAX_VALUE
        var bx = 0
        var by = 0
        val gx0 = if (region != null) (region.left / k).coerceIn(0, cw - tw) else 0
        val gx1 = if (region != null) ((region.right / k) - tw).coerceIn(gx0, cw - tw) else cw - tw
        val gy0 = if (region != null) (region.top / k).coerceIn(0, ch - th) else 0
        val gy1 = if (region != null) ((region.bottom / k) - th).coerceIn(gy0, ch - th) else ch - th
        for (y in gy0..gy1) {
            for (x in gx0..gx1) {
                val v = sad(sc, cw, x, y, tc, tw, th, best)
                if (v < best) {
                    best = v
                    bx = x
                    by = y
                }
            }
        }
        val sg = s.gray
        var fb = Int.MAX_VALUE
        var fx = bx * k
        var fy = by * k
        val x0 = maxOf(0, bx * k - k - 2)
        val x1 = minOf(sw - t.w, bx * k + k + 2)
        val y0 = maxOf(0, by * k - k - 2)
        val y1 = minOf(sh - t.h, by * k + k + 2)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val v = sad(sg, sw, x, y, t.px, t.w, t.h, fb)
                if (v < fb) {
                    fb = v
                    fx = x
                    fy = y
                }
            }
        }
        if (fb == Int.MAX_VALUE) return null
        val score = 1.0 - fb.toDouble() / (t.w.toDouble() * t.h.toDouble() * 255.0)
        if (score < threshold) return null
        return Hit(fx + t.w / 2f, fy + t.h / 2f, score)
    }
}
