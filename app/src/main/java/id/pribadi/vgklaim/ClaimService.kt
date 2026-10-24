package id.pribadi.vgklaim

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ClaimService : AccessibilityService() {

    companion object {
        @Volatile
        var inst: ClaimService? = null
    }

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

    fun shot(): Bitmap? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>(null)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            if (hw != null) {
                                out.set(hw.copy(Bitmap.Config.ARGB_8888, false))
                                hw.recycle()
                            }
                            hb.close()
                        } catch (_: Throwable) {
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
        } catch (_: Throwable) {
            return null
        }
        latch.await(5, TimeUnit.SECONDS)
        return out.get()
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
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun home() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun recents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private fun gesture(p: Path, ms: Long): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        val sent = dispatchGesture(
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
        if (!sent) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok.get()
    }
}
