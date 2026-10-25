package id.pribadi.vgklaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ClaimService : AccessibilityService() {

    companion object {
        @Volatile
        var inst: ClaimService? = null
    }

    // Alasan kegagalan terakhir (kosong = berhasil). Dibaca Engine untuk pesan log.
    @Volatile
    var lastShotError: String = ""

    @Volatile
    var lastGestureFail: String = ""

    // Thread sendiri untuk callback screenshot, supaya tidak bergantung pada thread utama aplikasi
    private val shotExec = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (inst === this) inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    private fun errName(code: Int): String = when (code) {
        1 -> "kesalahan internal sistem"
        2 -> "tidak ada akses aksesibilitas"
        3 -> "terlalu cepat setelah screenshot sebelumnya"
        4 -> "layar tidak valid"
        5 -> "jendela tidak valid"
        6 -> "jendela dilindungi (FLAG_SECURE)"
        else -> "kode tidak dikenal"
    }

    fun shot(): Bitmap? {
        val t0 = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>(null)
        val err = AtomicReference<String?>(null)
        val abandoned = AtomicBoolean(false)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                shotExec,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        try {
                            if (abandoned.get()) {
                                AppLog.add("Screenshot datang TERLAMBAT (" + (System.currentTimeMillis() - t0) + " ms), dibuang")
                            } else {
                                val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                if (hw == null) {
                                    err.set("hasil screenshot tidak bisa dibaca (wrapHardwareBuffer null)")
                                } else {
                                    out.set(hw.copy(Bitmap.Config.ARGB_8888, false))
                                    hw.recycle()
                                }
                            }
                        } catch (e: Throwable) {
                            err.set("gagal mengolah hasil: " + e.javaClass.simpleName + " " + e.message)
                        } finally {
                            try {
                                hb.close()
                            } catch (_: Throwable) {
                            }
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        err.set("kode " + errorCode + " (" + errName(errorCode) + ")")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Throwable) {
            lastShotError = "takeScreenshot melempar " + e.javaClass.simpleName + ": " + e.message
            AppLog.add("Screenshot GAGAL: " + lastShotError)
            return null
        }
        val got = latch.await(3, TimeUnit.SECONDS)
        val ms = System.currentTimeMillis() - t0
        if (!got) {
            abandoned.set(true)
            lastShotError = "sistem tidak menjawab dalam 3000 ms"
            AppLog.add("Screenshot GAGAL: " + lastShotError)
            return null
        }
        val b = out.get()
        if (b == null) {
            lastShotError = (err.get() ?: "hasil kosong") + " (" + ms + " ms)"
            AppLog.add("Screenshot GAGAL: " + lastShotError)
            return null
        }
        lastShotError = ""
        return b
    }

    private val ui = Handler(Looper.getMainLooper())

    // Banner melayang di atas layar apa pun (hitam putih), hilang sendiri. Tanpa izin tambahan.
    fun banner(text: String, ms: Long = 10000L, notify: Boolean = true) {
        if (notify) notifyDone(text)
        ui.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val d = resources.displayMetrics.density
                val tv = TextView(this)
                tv.text = text
                tv.setTextColor(Color.WHITE)
                tv.textSize = 15f
                tv.gravity = Gravity.CENTER
                tv.setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
                val bg = GradientDrawable()
                bg.setColor(Color.BLACK)
                bg.setStroke(maxOf(1, d.toInt()), Color.WHITE)
                bg.cornerRadius = 16 * d
                tv.background = bg
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = (40 * d).toInt()
                wm.addView(tv, lp)
                ui.postDelayed({
                    try {
                        wm.removeView(tv)
                    } catch (_: Throwable) {
                    }
                }, ms)
            } catch (e: Throwable) {
                AppLog.add("Banner gagal: " + e.javaClass.simpleName + " " + e.message)
            }
        }
    }

    private var dotView: View? = null

    // Titik kecil di pojok kiri bawah (tanpa tulisan, tidak bisa disentuh) supaya sistem menganggap
    // aplikasi ini punya jendela yang terlihat. style: 0 hapus, 1 putih 14dp, 2 putih kecil 2dp,
    // 3 hampir transparan (5%), 4 transparan penuh.
    fun dot(style: Int) {
        ui.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val old = dotView
                if (old != null) {
                    try {
                        wm.removeView(old)
                    } catch (_: Throwable) {
                    }
                    dotView = null
                }
                if (style >= 1) {
                    val d = resources.displayMetrics.density
                    val v = View(this)
                    // gaya 1-3: titik putih (terlihat/hampir transparan); gaya 4-5: tanpa warna sama sekali
                    if (style <= 3) {
                        val bg = GradientDrawable()
                        bg.shape = GradientDrawable.OVAL
                        bg.setColor(Color.WHITE)
                        bg.setStroke(maxOf(1, d.toInt()), Color.BLACK)
                        v.background = bg
                    }
                    val sz = when (style) {
                        2 -> maxOf(2, (2 * d).toInt())
                        4 -> 1
                        else -> (14 * d).toInt()
                    }
                    val lp = WindowManager.LayoutParams(
                        sz, sz,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = Gravity.BOTTOM or Gravity.START
                    if (style == 3) lp.alpha = 0.05f
                    if (style >= 4) lp.alpha = 0f
                    wm.addView(v, lp)
                    dotView = v
                }
            } catch (e: Throwable) {
                AppLog.add("Titik melayang gagal: " + e.javaClass.simpleName + " " + e.message)
            }
        }
    }

    // Cadangan kalau banner tidak tampil: notifikasi biasa
    private fun notifyDone(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("vgklaim_selesai", "Hasil klaim", NotificationManager.IMPORTANCE_HIGH)
            )
            val n = Notification.Builder(this, "vgklaim_selesai")
                .setContentTitle("VG Klaim")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (_: Throwable) {
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path()
        p.moveTo(x, y)
        return gesture(p, 60)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        return gesture(p, ms)
    }

    fun back() {
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) AppLog.add("Tombol Kembali (Back) gagal dikirim ke sistem")
    }

    fun home() {
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) AppLog.add("Tombol Home gagal dikirim ke sistem")
    }

    fun recents() {
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) AppLog.add("Tombol Recents gagal dikirim ke sistem")
    }

    // Tarik cepat lalu tahan jari di ujung (meniru gerakan jari pada rekaman)
    fun swipeHold(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long, holdMs: Long): Boolean {
        val p1 = Path()
        p1.moveTo(x1, y1)
        p1.lineTo(x2, y2)
        val s1 = GestureDescription.StrokeDescription(p1, 0, moveMs, true)
        if (!gestureStroke(s1)) return false
        val p2 = Path()
        p2.moveTo(x2, y2)
        val s2 = s1.continueStroke(p2, 0, holdMs, false)
        return gestureStroke(s2)
    }

    private fun gesture(p: Path, ms: Long): Boolean =
        gestureStroke(GestureDescription.StrokeDescription(p, 0, ms))

    private fun gestureStroke(st: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val g = GestureDescription.Builder()
            .addStroke(st)
            .build()
        val sent = try {
            dispatchGesture(
                g,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        ok.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                },
                null
            )
        } catch (e: Throwable) {
            lastGestureFail = "dispatchGesture melempar " + e.javaClass.simpleName
            return false
        }
        if (!sent) {
            lastGestureFail = "ditolak sistem"
            return false
        }
        val got = latch.await(4, TimeUnit.SECONDS)
        if (!got) {
            lastGestureFail = "timeout"
            return false
        }
        if (!ok.get()) {
            lastGestureFail = "dibatalkan sistem"
            return false
        }
        lastGestureFail = ""
        return true
    }
}
