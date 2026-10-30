package id.pribadi.vgklaim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// Layanan latar (dengan notifikasi) supaya sistem menganggap Klaim & Kirim sedang bekerja
// dan tidak membatasi CPU-nya saat game terbuka di depan.
class KeepAliveService : Service() {

    companion object {
        private const val CH = "vgklaim_jalan"
        private const val ACT_STOP = "id.pribadi.vgklaim.BERHENTI"

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
        // Tombol Berhenti di notifikasi: hentikan proses yang berjalan
        if (intent?.action == ACT_STOP) {
            AppLog.add("Berhenti dari notifikasi")
            Engine.stop()
            return START_NOT_STICKY
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH, "Klaim & Kirim berjalan", NotificationManager.IMPORTANCE_LOW)
            )
            val stopIntent = Intent(this, KeepAliveService::class.java).setAction(ACT_STOP)
            val stopPi = PendingIntent.getService(this, 7, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stopAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_vg), "Berhenti", stopPi
            ).build()
            val n = Notification.Builder(this, CH)
                .setContentTitle(if (Engine.mode == 1) "Kirim BBC berjalan" else "Klaim VG berjalan")
                .setContentText(if (Engine.mode == 1) "Kirim BBC sedang berjalan" else "Klaim otomatis sedang berjalan")
                .addAction(stopAction)
                .setSmallIcon(R.drawable.ic_stat_vg)
                .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher))
                .setColor(android.graphics.Color.parseColor("#C9CEDA"))
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
