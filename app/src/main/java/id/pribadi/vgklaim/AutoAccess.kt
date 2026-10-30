package id.pribadi.vgklaim

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

// Menyalakan layanan Aksesibilitas sendiri. Hanya jalan kalau izin WRITE_SECURE_SETTINGS sudah
// diberikan satu kali lewat Shizuku atau ADB. Tanpa izin itu, tidak melakukan apa pun.
object AutoAccess {
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    fun granted(ctx: Context): Boolean = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun command(ctx: Context): String = "pm grant " + ctx.packageName + " " + PERM

    // Mengembalikan true kalau pengaturan baru saja diubah (layanan perlu sekitar 1 detik untuk tersambung)
    fun ensure(ctx: Context): Boolean {
        if (!granted(ctx)) return false
        try {
            val cn = ComponentName(ctx, ClaimService::class.java)
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val parts = cur.split(':').filter { it.isNotBlank() }
            val has = parts.any { it.equals(full, true) || it.equals(short, true) }
            val on = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (has && on) return false
            val nv = if (has) cur else (parts + full).joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, nv)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            AppLog.add("Aksesibilitas dinyalakan otomatis")
            return true
        } catch (e: Throwable) {
            return false
        }
    }
}
