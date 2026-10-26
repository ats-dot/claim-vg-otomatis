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

    // Gaya titik melayang hasil Diagnosa: 0 tanpa, 1 putih 14dp, 2 putih 2dp, 3 putih transparan 5%, 4 1 piksel tanpa warna, 5 14dp tanpa warna
    var dotStyle: Int
        get() = s().getInt("dot_style", 5)
        set(v) { s().edit().putInt("dot_style", v).apply() }

    var stepMs: Int
        get() = s().getInt("step_ms", 400)
        set(v) { s().edit().putInt("step_ms", v.coerceIn(0, 3000)).apply() }

    // Jenis Diagnosa: 0 umum/OCR, 1 geser daftar
    var diagMode: Int
        get() = s().getInt("diag_mode", 0)
        set(v) { s().edit().putInt("diag_mode", v).apply() }

    // Tingkat gesekan keras yang terakhir berhasil memuat baris server baru (0-4), diingat antar sesi
    var hardLevel: Int
        get() = s().getInt("hard_level", 0)
        set(v) { s().edit().putInt("hard_level", v).apply() }

    // Geser daftar server: true = otomatis (bot mencoba tingkat gesekan), false = pakai angka manual di bawah
    var swipeAuto: Boolean
        get() = s().getBoolean("swipe_auto", true)
        set(v) { s().edit().putBoolean("swipe_auto", v).apply() }

    var swipeHardMs: Int
        get() = s().getInt("swipe_hard_ms", 120)
        set(v) { s().edit().putInt("swipe_hard_ms", v.coerceIn(40, 400)).apply() }

    var swipeStart: Int
        get() = s().getInt("swipe_start", 66)
        set(v) { s().edit().putInt("swipe_start", v.coerceIn(20, 95)).apply() }

    var swipeEnd: Int
        get() = s().getInt("swipe_end", 8)
        set(v) { s().edit().putInt("swipe_end", v.coerceIn(0, 60)).apply() }

    var swipeX: Int
        get() = s().getInt("swipe_x", 77)
        set(v) { s().edit().putInt("swipe_x", v.coerceIn(10, 95)).apply() }

    var swipeSlowMs: Int
        get() = s().getInt("swipe_slow_ms", 300)
        set(v) { s().edit().putInt("swipe_slow_ms", v.coerceIn(100, 1000)).apply() }

    fun resetSwipe() {
        swipeHardMs = 120
        swipeStart = 66
        swipeEnd = 8
        swipeX = 77
        swipeSlowMs = 300
    }

    var dotAuto: Boolean
        get() = s().getBoolean("dot_auto", true)
        set(v) { s().edit().putBoolean("dot_auto", v).apply() }

    // Aksi setelah selesai: 1 = kembali ke VG Klaim, 2 = ke Home, 3 = tetap di game
    var afterDone: Int
        get() = s().getInt("after_done", 1)
        set(v) { s().edit().putInt("after_done", v).apply() }

    var showAll: Boolean
        get() = s().getBoolean("show_all", false)
        set(v) { s().edit().putBoolean("show_all", v).apply() }

    var speed: Float
        get() = s().getFloat("speed", 1f)
        set(v) { s().edit().putFloat("speed", v).apply() }

    // Waktu tunggu lobby dalam MILIDETIK. Nilai lama (detik, kunci "lobby_wait") otomatis dikonversi.
    var lobbyWait: Int
        get() = s().getInt("lobby_wait_ms", s().getInt("lobby_wait", 3) * 1000)
        set(v) { s().edit().putInt("lobby_wait_ms", v.coerceIn(0, 30000)).apply() }

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

    // Skala gambar saat OCR (persen). Makin kecil makin cepat, tapi tulisan kecil bisa tidak terbaca.
    var ocrScale: Int
        get() = s().getInt("ocr_scale", 60)
        set(v) { s().edit().putInt("ocr_scale", v).apply() }

    var lobbyMin: Int
        get() = s().getInt("lobby_min", 3)
        set(v) { s().edit().putInt("lobby_min", v).apply() }

    // Progres disimpan per HARI dan per ID AKUN. Catatan hari-hari lama dibuang otomatis.
    private fun dayStamp(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun dayKey(acct: String): String = "done_" + dayStamp() + "_" + acct

    fun doneToday(acct: String = "-"): MutableSet<String> {
        val st = s().getStringSet(dayKey(acct), null) ?: return HashSet()
        return HashSet(st)
    }

    fun addDone(name: String, acct: String = "-") {
        val cur = doneToday(acct)
        cur.add(name)
        s().edit().putStringSet(dayKey(acct), cur).apply()
    }

    fun removeDone(name: String, acct: String = "-") {
        val cur = doneToday(acct)
        cur.remove(name)
        s().edit().putStringSet(dayKey(acct), cur).apply()
    }

    // Daftar ID akun yang sudah selesai semua servernya hari ini
    fun accountsDoneToday(): MutableSet<String> {
        val st = s().getStringSet("accts_" + dayStamp(), null) ?: return HashSet()
        return HashSet(st)
    }

    fun markAccountDone(acct: String) {
        val cur = accountsDoneToday()
        cur.add(acct)
        s().edit().putStringSet("accts_" + dayStamp(), cur).apply()
    }

    // Hapus catatan hari-hari sebelumnya (hanya hari ini yang disimpan)
    fun purgeOld() {
        val today = dayStamp()
        val ed = s().edit()
        for (k in s().all.keys) {
            if (k.startsWith("done_") && !k.startsWith("done_" + today)) ed.remove(k)
            if (k.startsWith("accts_") && k != "accts_" + today) ed.remove(k)
        }
        ed.apply()
    }

    // Hapus catatan hari ini untuk SEMUA akun
    fun resetDone() {
        val today = dayStamp()
        val ed = s().edit()
        for (k in s().all.keys) {
            if (k.startsWith("done_" + today) || k == "accts_" + today) ed.remove(k)
        }
        ed.apply()
    }
}
