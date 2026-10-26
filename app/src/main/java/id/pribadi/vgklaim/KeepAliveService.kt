package id.pribadi.vgklaim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// Layanan latar (dengan notifikasi) supaya sistem menganggap VG Klaim sedang bekerja
// dan tidak membatasi CPU-nya saat game terbuka di depan.
class KeepAliveService : Service() {

    companion object {
        private const val CH = "vgklaim_jalan"

        fun start(ctx: Context): String? {
            return try {
                ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
                null
            } catch (e: Throwable) {
                e.javaClass.simpleName + ": " + e.message
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH, "VG Klaim berjalan", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, CH)
                .setContentTitle("VG Klaim berjalan")
                .setContentText("Klaim otomatis sedang berjalan")
                .setSmallIcon(R.drawable.ic_stat_vg)
                .setColor(android.graphics.Color.parseColor("#E2C275"))
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Throwable) {
            AppLog.add("Layanan latar gagal: " + e.javaClass.simpleName + " " + e.message)
        }
        return START_NOT_STICKY
    }
}
