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

    // Rentang nomor server yang dianggap server. Nomor di luar rentang diabaikan.
    var serverMin: Int
        get() = s().getInt("server_min", 57092)
        set(v) { s().edit().putInt("server_min", v.coerceIn(1, 999999)).apply() }

    var serverMax: Int
        get() = s().getInt("server_max", 57115)
        set(v) { s().edit().putInt("server_max", v.coerceIn(1, 999999)).apply() }

    var serverCount: Int
        get() = s().getInt("count", 20)
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

    // Jeda sesudah tiap ketukan (ms). Bawaan 0: ketukan berikutnya menunggu pengecekan layar (OCR), bukan waktu tetap
    var stepMs: Int
        get() = s().getInt("step_ms0", 0)
        set(v) { s().edit().putInt("step_ms0", v.coerceIn(0, 3000)).apply() }

    var swipeHardMs: Int
        get() = s().getInt("swipe_hard_ms", 150)
        set(v) { s().edit().putInt("swipe_hard_ms", v.coerceIn(40, 400)).apply() }

    var swipeStart: Int
        get() = s().getInt("swipe_start", 55)
        set(v) { s().edit().putInt("swipe_start", v.coerceIn(20, 95)).apply() }

    var swipeEnd: Int
        get() = s().getInt("swipe_end", 10)
        set(v) { s().edit().putInt("swipe_end", v.coerceIn(0, 60)).apply() }

    var swipeX: Int
        get() = s().getInt("swipe_x", 77)
        set(v) { s().edit().putInt("swipe_x", v.coerceIn(10, 95)).apply() }

    var swipeSlowMs: Int
        get() = s().getInt("swipe_slow_ms", 200)
        set(v) { s().edit().putInt("swipe_slow_ms", v.coerceIn(100, 1000)).apply() }

    // Jumlah server untuk Uji sampai bawah
    var testCount: Int
        get() = s().getInt("test_count", 20)
        set(v) { s().edit().putInt("test_count", v.coerceIn(2, 300)).apply() }

    // Geser pelan (menggeser daftar maju) dan geser balik (menggeser daftar mundur), semua dalam persen tinggi layar
    var swipeSlowStart: Int
        get() = s().getInt("swipe_slow_start", 60)
        set(v) { s().edit().putInt("swipe_slow_start", v.coerceIn(0, 100)).apply() }

    var swipeSlowEnd: Int
        get() = s().getInt("swipe_slow_end", 47)
        set(v) { s().edit().putInt("swipe_slow_end", v.coerceIn(0, 100)).apply() }

    var swipeBackStart: Int
        get() = s().getInt("swipe_back_start", 45)
        set(v) { s().edit().putInt("swipe_back_start", v.coerceIn(0, 100)).apply() }

    var swipeBackEnd: Int
        get() = s().getInt("swipe_back_end", 58)
        set(v) { s().edit().putInt("swipe_back_end", v.coerceIn(0, 100)).apply() }

    var swipeBackMs: Int
        get() = s().getInt("swipe_back_ms", 200)
        set(v) { s().edit().putInt("swipe_back_ms", v.coerceIn(100, 1000)).apply() }

    fun resetSwipe() {
        swipeHardMs = 150
        swipeStart = 55
        swipeEnd = 10
        swipeX = 77
        swipeSlowMs = 200
        swipeSlowStart = 60
        swipeSlowEnd = 47
        swipeBackStart = 45
        swipeBackEnd = 58
        swipeBackMs = 200
    }

    // Klaim Verifikasi Usia (peti skin -> Vanguard Coin 300) di tiap server. Bisa dimatikan.
    var claimAge: Boolean
        get() = s().getBoolean("claim_age", true)
        set(v) { s().edit().putBoolean("claim_age", v).apply() }

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

    // ---------- Kirim BBC ----------
    var homeMode: Int
        get() = s().getInt("home_mode", 0)
        set(v) { s().edit().putInt("home_mode", v).apply() }

    var bbcId: String
        get() = s().getString("bbc_id", "") ?: ""
        set(v) { s().edit().putString("bbc_id", v).apply() }

    var bbcNick: String
        get() = s().getString("bbc_nick", "") ?: ""
        set(v) { s().edit().putString("bbc_nick", v).apply() }

    // Jumlah BBC yang ingin dikirim (kelipatan 1000)
    var bbcAmount: Int
        get() = s().getInt("bbc_amount", 0)
        set(v) { s().edit().putInt("bbc_amount", v).apply() }

    var bbcMatchNick: Boolean
        get() = s().getBoolean("bbc_match_nick", true)
        set(v) { s().edit().putBoolean("bbc_match_nick", v).apply() }

    var bbcClaimFirst: Boolean
        get() = s().getBoolean("bbc_claim_first", true)
        set(v) { s().edit().putBoolean("bbc_claim_first", v).apply() }

    // Server bernomor lebih kecil dari ini dianggap sudah terpakai dan tidak dikunjungi (0 = mati)
    var bbcStartFrom: Int
        get() = s().getInt("bbc_start_from", 0)
        set(v) { s().edit().putInt("bbc_start_from", v.coerceIn(0, 999999)).apply() }

    var bbcMinLevel: Int
        get() = s().getInt("bbc_min_level", 15)
        set(v) { s().edit().putInt("bbc_min_level", v.coerceIn(1, 100)).apply() }

    // Pesanan yang belum selesai: ID penerima, target BBC, dan BBC yang sudah terkirim
    var bbcPendId: String
        get() = s().getString("bbc_pend_id", "") ?: ""
        set(v) { s().edit().putString("bbc_pend_id", v).apply() }

    var bbcPendTarget: Int
        get() = s().getInt("bbc_pend_target", 0)
        set(v) { s().edit().putInt("bbc_pend_target", v).apply() }

    var bbcPendSent: Int
        get() = s().getInt("bbc_pend_sent", 0)
        set(v) { s().edit().putInt("bbc_pend_sent", v).apply() }

    fun bbcClearPending() {
        s().edit().putString("bbc_pend_id", "").putInt("bbc_pend_target", 0).putInt("bbc_pend_sent", 0).apply()
    }

    // Server milik akun pengirim yang sudah dipakai kirim BBC (diingat sampai semua server terpakai)
    fun bbcUsed(acct: String): MutableSet<String> {
        val st = s().getStringSet("bbc_used_" + acct, null)
        return if (st == null) HashSet() else HashSet(st)
    }

    fun bbcAddUsed(acct: String, name: String) {
        val cur = bbcUsed(acct)
        cur.add(name)
        s().edit().putStringSet("bbc_used_" + acct, cur).apply()
    }

    fun bbcClearUsed(acct: String) {
        s().edit().remove("bbc_used_" + acct).remove("bbc_last_" + acct).remove("bbc_try_" + acct).apply()
    }

    // Percobaan kirim yang belum selesai di akun ini: nama server, "?" (nama belum diketahui), atau "!" (sudah terkirim, nama belum tercatat)
    fun bbcTry(acct: String): String = s().getString("bbc_try_" + acct, "") ?: ""

    fun bbcSetTry(acct: String, v: String) {
        s().edit().putString("bbc_try_" + acct, v).apply()
    }

    fun bbcClearTry(acct: String) {
        s().edit().remove("bbc_try_" + acct).apply()
    }

    // Server terakhir yang dipakai kirim BBC: game membuka server ini lagi saat dijalankan berikutnya
    fun bbcLast(acct: String): String = s().getString("bbc_last_" + acct, "") ?: ""

    fun bbcSetLast(acct: String, name: String) {
        s().edit().putString("bbc_last_" + acct, name).apply()
    }
}
