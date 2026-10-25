package id.pribadi.vgklaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.view.Display
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

    private fun gesture(p: Path, ms: Long): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
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
