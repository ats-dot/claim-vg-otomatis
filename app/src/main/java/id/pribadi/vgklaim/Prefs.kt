package id.pribadi.vgklaim

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("vg", Context.MODE_PRIVATE)
    }

    private fun s(): SharedPreferences = sp ?: throw IllegalStateException("Prefs belum init")

    var serverCount: Int
        get() = s().getInt("count", 0)
        set(v) { s().edit().putInt("count", v).apply() }

    var skipAbove: Boolean
        get() = s().getBoolean("skip_above", true)
        set(v) { s().edit().putBoolean("skip_above", v).apply() }

    var fast: Boolean
        get() = s().getBoolean("fast", true)
        set(v) { s().edit().putBoolean("fast", v).apply() }

    var closeGame: Boolean
        get() = s().getBoolean("close_game", true)
        set(v) { s().edit().putBoolean("close_game", v).apply() }

    var showAll: Boolean
        get() = s().getBoolean("show_all", false)
        set(v) { s().edit().putBoolean("show_all", v).apply() }

    var speed: Float
        get() = s().getFloat("speed", 1f)
        set(v) { s().edit().putFloat("speed", v).apply() }

    var lobbyWait: Int
        get() = s().getInt("lobby_wait", 3)
        set(v) { s().edit().putInt("lobby_wait", v).apply() }

    var pkg: String
        get() = s().getString("pkg", "") ?: ""
        set(v) { s().edit().putString("pkg", v).apply() }

    // Nama kunci diganti (lobby_keys2) supaya nilai awal yang baru langsung dipakai
    var lobbyKeys: String
        get() = s().getString("lobby_keys2", "persiapan,inventori,toko,event,classic,hero") ?: ""
        set(v) { s().edit().putString("lobby_keys2", v).apply() }

    // Posisi ketuk ikon profil di lobby, pecahan 0..1 dari lebar/tinggi layar
    var avatarX: Float
        get() = s().getFloat("avatar_x", 0.06f)
        set(v) { s().edit().putFloat("avatar_x", v).apply() }

    var avatarY: Float
        get() = s().getFloat("avatar_y", 0.056f)
        set(v) { s().edit().putFloat("avatar_y", v).apply() }

    var lobbyMin: Int
        get() = s().getInt("lobby_min", 3)
        set(v) { s().edit().putInt("lobby_min", v).apply() }

    private fun dayKey(): String =
        "done_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun doneToday(): MutableSet<String> {
        val st = s().getStringSet(dayKey(), null) ?: return HashSet()
        return HashSet(st)
    }

    fun addDone(name: String) {
        val cur = doneToday()
        cur.add(name)
        s().edit().putStringSet(dayKey(), cur).apply()
    }

    fun resetDone() {
        s().edit().remove(dayKey()).apply()
    }
}
