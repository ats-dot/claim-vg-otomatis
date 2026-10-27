package id.pribadi.vgklaim

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import java.text.Normalizer

// Proses Kirim BBC. Memakai mesin layar milik Engine (baca layar, ketuk, pindah server).
// Per server pengirim: (klaim dulu) -> cek diamond di lobby -> Top Up 500 Diamond -> cari akun penerima lewat ID
// -> Live Stream -> Tonton -> hadiah -> Yacht 1000 diamond -> kembali ke lobby -> server berikutnya.
object BbcEngine {

    // Server dilewati (bukan kesalahan): koin kurang atau level server belum cukup
    private class Skip(val why: String, val used: Boolean = false) : RuntimeException(why)

    @Volatile private var lastSkipUsed = false

    private const val UNIT = 1000

    @Volatile private var finished = false
    @Volatile private var exhausted = false
    private var lastTarget = 0
    private val levelWarned = HashSet<String>()

    // Dibaca saat lobby baru terdeteksi (selagi menunggu popup): bar atas masih menampilkan diamond
    @Volatile private var seenLevel: Int? = null
    @Volatile private var seenDiamonds = -1

    // ---------- jalan pintas ke Engine ----------

    private fun log(m: String) = Engine.log(m)
    private fun now(): Long = Engine.now()
    private fun sleepMs(ms: Long) = Engine.sleepMs(ms)
    private fun cap(r: Engine.Reg? = null): Scr = Engine.cap(r)
    private fun tapXY(x: Float, y: Float, why: String) = Engine.tapXY(x, y, why, true)
    private fun tapLine(l: Line, why: String) = Engine.tapXY(l.cx, l.cy, why, true)

    // Ketukan langsung tanpa jeda antar langkah
    private fun quickTap(x: Float, y: Float, why: String) {
        Engine.alive()
        log("Ketuk: " + why)
        ClaimService.inst?.tap(x, y)
    }

    private fun waitFor(maxMs: Long, pred: (Scr) -> Boolean): Scr? {
        val t0 = now()
        while (now() - t0 < maxMs) {
            Engine.alive()
            val s = cap()
            if (pred(s)) return s
            sleepMs(200)
        }
        return null
    }

    // ---------- data pesanan ----------

    fun validate(): String? {
        val id = Prefs.bbcId.trim()
        if (id.isEmpty() || !id.all { it.isDigit() }) return "Isi ID penerima dulu (angka)"
        val a = Prefs.bbcAmount
        if (a <= 0 || a % UNIT != 0) return "Jumlah BBC harus kelipatan 1000"
        if (Prefs.bbcMatchNick && Prefs.bbcNick.trim().isEmpty()) return "Isi nick dulu, atau matikan Cocokkan nick"
        return null
    }

    fun bannerText(stopped: Boolean): String {
        if (finished) return "Selesai, " + lastTarget + " BBC terkirim"
        val s = Prefs.bbcPendSent
        val t = Prefs.bbcPendTarget
        if (exhausted) return "Server akun ini habis: terkirim " + s + " dari " + t + " BBC. Ganti akun lalu lanjutkan"
        return "Berhenti: terkirim " + s + " dari " + t + " BBC"
    }

    private fun setupOrder(): Boolean {
        val id = Prefs.bbcId.trim()
        val amt = Prefs.bbcAmount
        if (Prefs.bbcPendId == id && Prefs.bbcPendTarget > 0) {
            if (Prefs.bbcPendSent >= amt) {
                log("Pesanan ke ID " + id + " sudah terkirim " + Prefs.bbcPendSent + " BBC. Tidak ada yang dikirim")
                Prefs.bbcClearPending()
                return false
            }
            Prefs.bbcPendTarget = amt
            log("Melanjutkan pesanan ke ID " + id + ": terkirim " + Prefs.bbcPendSent + " dari " + amt + " BBC")
        } else {
            Prefs.bbcPendId = id
            Prefs.bbcPendTarget = amt
            Prefs.bbcPendSent = 0
            log("Pesanan baru ke ID " + id + ": 0 dari " + amt + " BBC")
        }
        lastTarget = amt
        return true
    }

    // ---------- level server (hanya dibaca saat memilih server berikutnya) ----------

    private fun rowLevel(s: Scr, row: Line): Int? {
        val band = s.lines.filter { Math.abs(it.cy - row.cy) < s.h * 0.035f }
        val right = band.maxByOrNull { it.r.right } ?: return null
        val tok = right.text.trim().split(Regex("\\s+")).lastOrNull() ?: return null
        val digits = tok.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 3) return null
        return digits.toIntOrNull()
    }

    fun levelOk(name: String, s: Scr, row: Line): Boolean {
        val min = Prefs.bbcMinLevel
        val lv = rowLevel(s, row)
        if (lv == null) {
            if (levelWarned.add(name)) log("Level server " + name + " tidak terbaca, dianggap memenuhi")
            return true
        }
        if (lv < min) {
            log("Server " + name + " dilewati: level " + lv + " (minimal " + min + ")")
            return false
        }
        return true
    }

    // Dipanggil Engine begitu lobby terdeteksi, sebelum popup ditutup. Level dan diamond dibaca di waktu tunggu ini
    fun onLobbyDetected() {
        try {
            seenLevel = lobbyLevel()
            seenDiamonds = lobbyDiamonds()
            log("Dibaca saat lobby muncul: level " + (seenLevel ?: "?") + ", diamond " + (if (seenDiamonds < 0) "?" else seenDiamonds.toString()))
        } catch (e: Engine.Stop) {
            throw e
        } catch (_: Throwable) {
            seenLevel = null
            seenDiamonds = -1
        }
    }

    // Level server yang sedang dimainkan: angka kecil di bawah nama pemain di pojok kiri atas lobby
    private fun lobbyLevel(): Int? {
        val s = cap(Engine.Reg(0.0f, 0.0f, 0.30f, 0.14f))
        val l = s.lines.firstOrNull {
            it.norm.isNotEmpty() && it.norm.length <= 3 && it.norm.all { c -> c.isDigit() } &&
                it.cx > s.w * 0.05f && it.cx < s.w * 0.17f && it.cy > s.h * 0.05f && it.cy < s.h * 0.11f
        } ?: return null
        return l.norm.toIntOrNull()
    }

    // ---------- alur utama ----------

    fun main() {
        val ctx = Engine.app ?: throw Engine.Stop("Aplikasi tidak siap")
        Engine.gamePkg = Engine.findGame(ctx) ?: throw Engine.Stop("Game MLBB tidak ditemukan. Pilih game di Pengaturan.")
        Engine.done.clear()
        Engine.skipped.clear()
        Engine.order.clear()
        Engine.cur = null
        Engine.acct = "-"
        Engine.lastClaimed = null
        Engine.firstPending = false
        Engine.total = 0
        Engine.doneCount = 0
        finished = false
        exhausted = false
        levelWarned.clear()

        if (!setupOrder()) {
            finished = true
            return
        }

        Engine.status = "Membuka game"
        log("Mulai kirim BBC. Game: " + Engine.gamePkg)
        Engine.launch()
        Engine.sleepS(6.0)
        Engine.awaitLobby(240)
        Engine.settleLobby()

        // ID akun dibaca dari layar Memuat Game (Role). Daftar Karakter TIDAK dibuka di awal: server awal diperkirakan
        // sama dengan server terakhir yang dipakai kirim BBC, dan nama server awal baru dibaca sesudah kiriman pertama.
        Engine.total = Prefs.serverCount
        var needLobby = false
        if (Engine.acct == "-") {
            Engine.status = "Membaca akun dan daftar server"
            log("ID akun tidak terbaca dari layar loading, membaca lewat Daftar Karakter")
            val ls = Engine.openServerList()
            val rc = Engine.readCurrent(ls) ?: throw Engine.Stop("Server awal tidak terbaca di Daftar Karakter")
            Engine.cur = rc
            needLobby = true
            if (Engine.acct == "-") log("Peringatan: ID akun tidak terbaca, server yang dipakai tidak diingat")
        } else {
            val tr = Prefs.bbcTry(Engine.acct)
            val last = Prefs.bbcLast(Engine.acct)
            if (tr == "!") {
                // Kiriman terakhir berhasil tapi proses berhenti sebelum nama servernya tercatat: server awal sudah terpakai
                Engine.status = "Membaca server awal"
                log("Kiriman terakhir belum tercatat, membaca nama server awal")
                val ls = Engine.openServerList()
                val rc = Engine.readCurrent(ls) ?: throw Engine.Stop("Server awal tidak terbaca di Daftar Karakter")
                Engine.cur = rc
                markUsed(rc)
                Prefs.bbcClearTry(Engine.acct)
                needLobby = true
            } else if (tr.isNotEmpty() && tr != "?") {
                Engine.cur = tr
                log("Melanjutkan di server " + tr + " (percobaan sebelumnya belum selesai)")
            } else if (tr == "?") {
                log("Melanjutkan di server awal (percobaan sebelumnya belum selesai)")
            } else if (last.isNotEmpty() && Engine.done.contains(last)) {
                Engine.cur = last
                log("Server awal diperkirakan " + last + " (server terakhir yang dipakai kirim BBC), dilewati")
            }
        }
        log("Akun " + Engine.acct + ", server yang sudah dipakai kirim BBC: " + Engine.done.size)

        var resetDone = false
        var guard = 0
        while (true) {
            Engine.alive()
            var cur = Engine.cur
            if (cur != null && (Engine.done.contains(cur) || Engine.skipped.contains(cur))) {
                log("Server " + cur + " sudah dipakai, dilewati")
            } else {
                if (needLobby) {
                    Engine.backToLobby()
                    needLobby = false
                }
                val sent = sendOnServer(cur, guard == 0)
                if (cur == null) {
                    // Nama server awal baru diketahui sekarang
                    val ls = Engine.openServerList()
                    val rc = Engine.readCurrent(ls) ?: throw Engine.Stop("Server awal tidak terbaca di Daftar Karakter")
                    Engine.cur = rc
                    cur = rc
                    needLobby = true
                    log("Server awal ternyata " + rc)
                    if (sent && Engine.done.contains(rc)) log("Peringatan: server " + rc + " sudah pernah dipakai kirim BBC sebelumnya")
                    if (Engine.acct != "-") Prefs.bbcClearTry(Engine.acct)
                    if (sent || lastSkipUsed) {
                        markUsed(rc)
                    } else {
                        Engine.skipped.add(rc)
                        Engine.refreshCount()
                    }
                }
                if (finished) break
            }
            guard++
            if (guard > 80) throw Engine.Stop("Terlalu banyak server")
            val next = Engine.pickNext()
            if (next == null) {
                if (!resetDone && Engine.acct != "-" && Prefs.bbcUsed(Engine.acct).size >= Prefs.serverCount) {
                    resetDone = true
                    Prefs.bbcClearUsed(Engine.acct)
                    Engine.done.clear()
                    Engine.skipped.clear()
                    needLobby = true
                    log("Semua server akun ini sudah pernah dipakai kirim BBC, ingatan direset")
                    continue
                }
                exhausted = true
                log("Server di akun ini habis. Terkirim " + Prefs.bbcPendSent + " dari " + Prefs.bbcPendTarget + " BBC")
                break
            }
            Engine.switchTo(next)
            Engine.cur = next
            needLobby = false
        }
        Engine.finishAction()
    }

    private fun markUsed(name: String) {
        Engine.done.add(name)
        if (Engine.acct != "-") {
            Prefs.bbcAddUsed(Engine.acct, name)
            Prefs.bbcSetLast(Engine.acct, name)
        }
        Engine.refreshCount()
    }

    // ---------- satu server pengirim ----------

    private fun sendOnServer(name: String?, firstOfRun: Boolean): Boolean {
        val label = name ?: "(server awal)"
        lastSkipUsed = false
        Engine.status = "Kirim BBC di " + label
        log("===== SERVER " + label + " (kirim BBC) =====")
        val t0 = now()
        if (Engine.acct != "-") Prefs.bbcSetTry(Engine.acct, name ?: "?")
        try {
            // Level dicek di lobby HANYA untuk server pertama di akun ini (server awal). Server berikutnya sudah dicek
            // dari Daftar Karakter saat dipilih, jadi tidak perlu membaca lobby lagi.
            if (firstOfRun) {
                val lv = seenLevel ?: lobbyLevel()
                if (lv == null) {
                    log("Level server awal di lobby tidak terbaca, dilanjutkan")
                } else {
                    log("Level server awal di lobby: " + lv)
                    if (lv < Prefs.bbcMinLevel) throw Skip("level " + lv + " (minimal " + Prefs.bbcMinLevel + ")")
                }
            }
            if (Prefs.bbcClaimFirst) {
                Engine.status = "Klaim di " + label
                Engine.claimEvents()
                Engine.status = "Kirim BBC di " + label
            }
            ensureDiamonds()
            closeSquadPopup()
            openFriendDialog()
            val before = firstRowSig(cap())
            typeId(Prefs.bbcId.trim())
            searchAndOpen(before)
            openProfileFull()
            val first = Prefs.bbcPendSent == 0
            val ls = openLiveStream()
            if (first) {
                if (saveShot(ls.bmp, "before.png")) log("Screenshot before.png disimpan (Pictures/VGKlaim)")
            }
            sendYacht(ls)
            val sentNow = Prefs.bbcPendSent + UNIT
            Prefs.bbcPendSent = sentNow
            // Catat segera supaya tidak hilang kalau proses berhenti sesudah ini
            if (name != null) {
                markUsed(name)
                if (Engine.acct != "-") Prefs.bbcClearTry(Engine.acct)
            } else if (Engine.acct != "-") {
                Prefs.bbcSetTry(Engine.acct, "!")
            }
            log("Kiriman berhasil di " + label + ": terkirim " + sentNow + " dari " + Prefs.bbcPendTarget + " BBC (" + ((now() - t0) / 1000) + " dtk)")
            // kembali ke profil
            Engine.back()
            waitFor(4000) { it.has("zonapribadi") }
            if (sentNow >= Prefs.bbcPendTarget) {
                val la = openLiveStream()
                if (saveShot(la.bmp, "after.png")) log("Screenshot after.png disimpan (Pictures/VGKlaim)")
                lastTarget = Prefs.bbcPendTarget
                Prefs.bbcClearPending()
                finished = true
                log("SELESAI. Pesanan " + lastTarget + " BBC terkirim ke ID " + Prefs.bbcId.trim())
            }
            toLobby()
            seenLevel = null
            seenDiamonds = -1
            return true
        } catch (e: Skip) {
            log("Server " + label + " dilewati: " + e.why)
            lastSkipUsed = e.used
            if (Engine.acct != "-" && name != null) Prefs.bbcClearTry(Engine.acct)
            if (name != null) {
                if (e.used) {
                    markUsed(name)
                } else {
                    Engine.skipped.add(name)
                    Engine.refreshCount()
                }
            }
            toLobby()
            seenLevel = null
            seenDiamonds = -1
            return false
        }
    }

    // ---------- diamond ----------

    // Ikon di kiri sebuah angka: biru = diamond, emas = Vanguard Coin
    private fun blueIconLeftOf(s: Scr, l: Line): Boolean {
        val ix = (l.r.left - s.w * 0.014f).toInt()
        var rr = 0
        var bb = 0
        var n = 0
        for (dy in intArrayOf(-6, 0, 6)) {
            for (dx in intArrayOf(-6, 0, 6)) {
                val x = ix + dx
                val y = l.cy.toInt() + dy
                if (x < 0 || y < 0 || x >= s.bmp.width || y >= s.bmp.height) continue
                val c = s.bmp.getPixel(x, y)
                rr += (c shr 16) and 255
                bb += c and 255
                n++
            }
        }
        if (n == 0) return false
        return (bb - rr) / n > 20
    }

    private fun lobbyDiamonds(): Int {
        val s = cap(Engine.R_TOP)
        val nums = s.lines.filter {
            it.norm.isNotEmpty() && it.norm.length <= 6 && it.norm.all { c -> c.isDigit() } &&
                it.cx > s.w * 0.50f && it.cx < s.w * 0.74f
        }
        val l = nums.maxByOrNull { it.cx } ?: return -1
        if (!blueIconLeftOf(s, l)) {
            log("Bar atas lobby sedang menampilkan koin lain (bukan diamond)")
            return -1
        }
        return l.norm.toIntOrNull() ?: -1
    }

    // Pita merah "Top Up Pertama" di pojok kanan atas kartu: ada = bonus pembelian pertama kartu itu belum terpakai
    private fun firstBadge(t: Scr, card: Line): Boolean {
        val x0 = (card.cx + t.w * 0.055f).toInt()
        val x1 = (card.cx + t.w * 0.120f).toInt()
        val y0 = (card.cy - t.h * 0.045f).toInt()
        val y1 = (card.cy + t.h * 0.030f).toInt()
        var red = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                if (x >= 0 && y >= 0 && x < t.bmp.width && y < t.bmp.height) {
                    val c = t.bmp.getPixel(x, y)
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val bl = c and 255
                    if (r > 170 && g < 90 && bl < 90) red++
                }
                x += 3
            }
            y += 3
        }
        return red >= 40
    }

    private fun ensureDiamonds() {
        if (Prefs.bbcClaimFirst) {
            // Klaim dulu aktif: akun ini belum diklaim, jadi belum punya diamond. Bar atas lobby juga sudah berganti ke bintang
            log("Klaim dulu aktif, langsung ke menu Top Up")
        } else {
            val d = if (seenDiamonds >= 0) seenDiamonds else lobbyDiamonds()
            if (d >= UNIT) {
                log("Diamond di lobby: " + d + ", tidak perlu Top Up")
                return
            }
            log("Diamond di lobby: " + (if (d < 0) "tidak terbaca" else d.toString()) + ", ke menu Top Up")
        }
        for (round in 0 until 2) {
            if (buyDiamonds() == 0) return
            if (round == 1) throw Skip("Vanguard Coin belum cukup untuk kirim BBC")
            log("Vanguard Coin tidak cukup, kembali ke lobby lalu klaim dulu")
            Engine.backToLobby()
            Engine.claimEvents()
        }
        throw Skip("Vanguard Coin belum cukup untuk kirim BBC")
    }

    private fun isTopUp(s: Scr): Boolean = s.has("keuntungantopup") && s.has("ganti")

    private fun rpMode(s: Scr): Boolean = Regex("rp[0-9]{4,}").containsMatchIn(s.all)

    // 0 = berhasil (diamond 1000 sudah masuk dan kembali di lobby), 1 = Vanguard Coin tidak cukup (masih di menu Top Up)
    private fun buyDiamonds(): Int {
        val l0 = cap()
        tapXY(l0.w * 0.730f, l0.h * 0.054f, "tanda + di lobby (Top Up)")
        var t = waitFor(7000) { isTopUp(it) } ?: throw Engine.Stop("Menu Top Up tidak terbuka")
        // Harga harus dalam Vanguard Coin. Mode diamond menampilkan harga Rupiah ("Rp ..."). Tunggu halaman tenang,
        // ketuk Ganti SEKALI, lalu tunggu harganya berubah (jangan mengetuk berulang karena bisa membalikkan lagi)
        sleepMs(600)
        t = cap()
        if (rpMode(t)) {
            val g = t.lines.firstOrNull { it.norm == "ganti" } ?: t.find("ganti") ?: throw Engine.Stop("Tombol Ganti tidak terbaca di menu Top Up")
            tapLine(g, "Ganti (harga Vanguard Coin)")
            val tg = now()
            while (now() - tg < 3000 && rpMode(t)) {
                Engine.alive()
                sleepMs(300)
                t = cap()
            }
            if (rpMode(t)) {
                val g2 = t.lines.firstOrNull { it.norm == "ganti" } ?: t.find("ganti") ?: throw Engine.Stop("Tombol Ganti tidak terbaca di menu Top Up")
                tapLine(g2, "Ganti (ulang)")
                sleepMs(1200)
                t = cap()
            }
        }
        if (rpMode(t)) throw Engine.Stop("Harga masih Rupiah setelah mengetuk Ganti")
        val card = t.lines.firstOrNull { it.norm == "500diamond" }
            ?: t.lines.firstOrNull { it.norm.length == 10 && lev(it.norm, "500diamond") <= 1 }
            ?: throw Engine.Stop("Kartu 500 Diamond tidak ketemu di menu Top Up")
        if (!firstBadge(t, card)) throw Skip("bonus Top Up Pertama 500 Diamond sudah terpakai", true)
        tapXY(card.cx + t.w * 0.034f, card.cy + t.h * 0.12f, "kartu 500 Diamond")
        val d = waitFor(4500) { it.has("pemberitahuansistem", "lanjutkan") } ?: throw Engine.Stop("Dialog harga tidak muncul setelah memilih 500 Diamond")
        val price = d.lines.firstOrNull { it.norm == "500" && it.cx > d.w * 0.45f && it.cx < d.w * 0.56f && it.cy > d.h * 0.38f && it.cy < d.h * 0.58f }
        if (price == null) throw Engine.Stop("Harga di dialog bukan 500, tidak dikonfirmasi")
        val kb = d.lines.firstOrNull { it.norm == "konfirmasi" } ?: d.find("konfirmasi") ?: throw Engine.Stop("Tombol Konfirmasi tidak terbaca")
        tapLine(kb, "Konfirmasi (500 Diamond)")
        val res = waitFor(6500) { it.has("tidakcukup") || it.has("memperolehhadiah") } ?: throw Engine.Stop("Hasil pembelian tidak dikenali")
        if (res.has("tidakcukup")) return 1
        // Hasil pembelian: OK lalu X popup pertama dan kedua, beruntun tanpa jeda (posisi tombol tidak berpindah)
        val okb = Engine.okBtn(res)
        quickTap(okb?.cx ?: (res.w * 0.5f), okb?.cy ?: (res.h * 0.893f), "OK (Memperoleh Hadiah)")
        sleepMs(150)
        quickTap(res.w * 0.858f, res.h * 0.076f, "tutup X (popup pertama)")
        sleepMs(150)
        quickTap(res.w * 0.858f, res.h * 0.076f, "tutup X (popup kedua)")
        // Sisa popup yang muncul terlambat: ketuk X begitu terlihat
        var quietFrom = now()
        val tEnd = now() + 9000
        while (now() < tEnd) {
            Engine.alive()
            val s2 = cap()
            if (s2.has("pilihhadiah", "topupsekarang", "skindeluxe", "memperolehhadiah")) {
                if (s2.has("memperolehhadiah")) {
                    val o2 = Engine.okBtn(s2)
                    quickTap(o2?.cx ?: (s2.w * 0.5f), o2?.cy ?: (s2.h * 0.893f), "OK (Memperoleh Hadiah)")
                } else {
                    quickTap(s2.w * 0.858f, s2.h * 0.076f, "tutup X (popup Top Up)")
                }
                quietFrom = now()
            } else {
                if (now() - quietFrom > 900) break
                sleepMs(100)
            }
        }
        log("Top Up 500 Diamond selesai")
        Engine.backToLobby()
        return 0
    }

    // ---------- Squad, cari akun, profil ----------

    private fun closeSquadPopup() {
        var s = cap()
        if (!s.has("squadtersedia")) return
        log("Popup Sekarang Squad Tersedia ada, dihilangkan")
        tapXY(s.w * 0.933f, s.h * 0.681f, "ikon Squad")
        var lobbyWaits = 0
        for (i in 0 until 14) {
            sleepMs(700)
            s = cap()
            val lobby = Engine.isLobby(s) && !Engine.eventOpen(s)
            if (lobby && !s.has("squadtersedia")) return
            if (lobby) {
                lobbyWaits++
                if (lobbyWaits == 4) tapXY(s.w * 0.933f, s.h * 0.681f, "ikon Squad (ulang)")
                continue
            }
            Engine.back()
        }
    }

    private fun openFriendDialog() {
        for (att in 0 until 3) {
            val s = cap()
            if (s.has("tambahkanteman") && s.has("cariid")) return
            tapXY(s.w * 0.893f, s.h * 0.679f, "ikon orang + (tambah teman)")
            if (waitFor(3500) { it.has("tambahkanteman") } != null) return
        }
        throw Engine.Stop("Dialog Tambahkan Teman tidak terbuka")
    }

    private fun typeId(id: String) {
        val s0 = cap()
        val box = s0.lines.firstOrNull { it.norm.contains("ketukuntuk") }
        quickTap(box?.cx ?: (s0.w * 0.298f), box?.cy ?: (s0.h * 0.222f), "kotak ID")
        val svc = ClaimService.inst ?: throw Engine.Stop("Layanan Aksesibilitas tidak aktif")
        // Isi seluruh ID sekaligus begitu papan ketik siap (ulangi tiap 0,2 detik, maksimal 4 detik)
        var r = ""
        val t0 = now()
        while (now() - t0 < 4000) {
            Engine.alive()
            r = svc.inputText(id)
            if (r == "OK" || r.contains("jendela=0")) break
            sleepMs(200)
        }
        log("Isi ID lewat Aksesibilitas: " + r)
        if (r == "OK") {
            pressOke()
            if (cap().all.contains(id)) log("ID terisi (terbaca di dialog)") else log("ID belum terbaca di dialog, dilanjutkan")
            return
        }
        log("Pengisian otomatis gagal, mengetik lewat tombol angka papan ketik")
        if (typeByKeyboard(id)) {
            sleepMs(300)
            if (cap().all.contains(id)) {
                log("ID terisi lewat papan ketik")
                closeKeyboard()
                return
            }
            log("ID yang diketik belum terbaca di layar")
        }
        throw Engine.Stop("Tidak bisa mengisi kotak ID. Kirim log ke Claude")
    }

    // Langsung ketuk OKE di kanan atas papan ketik (tanpa jeda), cadangan: tutup papan ketik
    private fun pressOke() {
        val s = cap()
        val ok = s.lines.firstOrNull { (it.norm == "oke" || it.norm == "ok" || it.norm == "selesai") && it.cx > s.w * 0.80f && it.cy < s.h * 0.25f }
        if (ok != null) quickTap(ok.cx, ok.cy, "OKE (selesai mengisi ID)") else closeKeyboard()
    }

    private fun isLightKey(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        return mx > 120 && mx - mn < 45
    }

    // Letak tombol angka 1..0 di papan ketik, dicari dari bentuk tombol (baris tombol terang pertama di bawah bilah atas).
    // Mengembalikan 10 titik pusat (urutan 1,2,...,9,0) atau null kalau papan ketik tidak terlihat.
    private fun digitKeys(s: Scr): List<Pair<Float, Float>>? {
        val bmp = s.bmp
        val w = bmp.width
        val h = bmp.height
        var y = (h * 0.30f).toInt()
        var bandStart = -1
        var bandEnd = -1
        while (y < (h * 0.95f).toInt()) {
            var cnt = 0
            var tot = 0
            var x = 0
            while (x < w) {
                if (isLightKey(bmp.getPixel(x, y))) cnt++
                tot++
                x += 6
            }
            val frac = cnt.toFloat() / tot
            if (frac > 0.35f) {
                if (bandStart < 0) bandStart = y
                bandEnd = y
            } else if (bandStart >= 0) {
                break
            }
            y += 3
        }
        if (bandStart < 0 || bandEnd - bandStart < h * 0.03f) return null
        // Garis di bagian atas tombol (di atas angka) supaya angka tidak memotong tombol
        val yl = bandStart + ((bandEnd - bandStart) * 0.18f).toInt()
        val runs = ArrayList<IntArray>()
        var start = -1
        var x = 0
        while (x < w) {
            if (isLightKey(bmp.getPixel(x, yl))) {
                if (start < 0) start = x
            } else if (start >= 0) {
                if (x - start > w * 0.03f) runs.add(intArrayOf(start, x))
                start = -1
            }
            x += 2
        }
        if (start >= 0 && w - start > w * 0.03f) runs.add(intArrayOf(start, w))
        if (runs.size != 10) {
            log("Baris tombol angka tidak terdeteksi (terbaca " + runs.size + " tombol)")
            return null
        }
        val yc = (bandStart + bandEnd) / 2f
        return runs.map { Pair((it[0] + it[1]) / 2f, yc) }
    }

    private fun typeByKeyboard(id: String): Boolean {
        val keys = digitKeys(cap())
        if (keys == null) {
            log("Papan ketik tidak terlihat")
            return false
        }
        for (ch in id) {
            if (!ch.isDigit()) {
                log("Karakter " + ch + " bukan angka, tidak bisa diketik")
                return false
            }
            val idx = if (ch == '0') 9 else ch - '1'
            val p = keys[idx]
            tapXY(p.first, p.second, "ketik " + ch)
        }
        return true
    }

    // Papan ketik layar penuh (lanskap) menutupi dialog: tutup dengan tombol OKE di kanan atas, atau Back
    private fun closeKeyboard() {
        for (i in 0 until 4) {
            val s = cap()
            val kb = digitKeys(s) != null || s.has("bahasaindonesia")
            if (!kb || s.has("cariid")) return
            val ok = s.lines.firstOrNull { (it.norm == "oke" || it.norm == "ok" || it.norm == "selesai") && it.cx > s.w * 0.80f && it.cy < s.h * 0.25f }
            if (ok != null) tapLine(ok, "OKE (tutup papan ketik)") else Engine.back()
            sleepMs(600)
        }
    }

    // Baris teratas hasil pencarian (nama, tanpa level dan status online)
    private fun rowCands(s: Scr): List<Line> = s.lines.filter {
        it.cy > s.h * 0.27f && it.cy < s.h * 0.46f && it.cx > s.w * 0.20f && it.cx < s.w * 0.72f &&
            !it.norm.contains("terakhir") && !it.norm.contains("online") && it.norm.length >= 2 &&
            !it.norm.all { c -> c.isDigit() }
    }.sortedBy { it.r.left }

    private fun firstRowSig(s: Scr): String = rowCands(s).joinToString("|") { it.norm }

    private fun searchAndOpen(beforeSig: String) {
        val s = cap()
        val cb = s.lines.firstOrNull { it.norm == "cariid" } ?: s.find("cariid") ?: throw Engine.Stop("Tombol Cari ID tidak terbaca")
        tapLine(cb, "Cari ID")
        var changed = false
        val t0 = now()
        while (now() - t0 < 8000) {
            Engine.alive()
            sleepMs(500)
            if (firstRowSig(cap()) != beforeSig) {
                changed = true
                break
            }
        }
        if (!changed) throw Engine.Stop("Hasil pencarian tidak berubah: ID tidak ditemukan atau Cari ID tidak terketuk")
        sleepMs(700)
        val fin = cap()
        val texts = rowCands(fin).map { it.text }
        log("Hasil teratas pencarian: " + texts.joinToString(" | "))
        if (Prefs.bbcMatchNick && !nickMatch(texts, Prefs.bbcNick)) {
            throw Engine.Stop("Nick hasil teratas (" + texts.joinToString(" ") + ") tidak cocok dengan " + Prefs.bbcNick.trim() + ". Pengiriman dihentikan")
        }
        tapXY(fin.w * 0.417f, fin.h * 0.361f, "akun hasil teratas")
    }

    private fun openProfileFull() {
        val card = waitFor(6500) { it.has("seasonsaatini") } ?: throw Engine.Stop("Kartu profil tidak terbuka setelah mengetuk akun")
        sleepMs(500)
        tapXY(card.w * 0.717f, card.h * 0.736f, "ikon pencarian (profil lengkap)")
        val prof = waitFor(7000) { it.has("zonapribadi") } ?: throw Engine.Stop("Halaman Zona Pribadi tidak terbuka")
        if (Prefs.bbcMatchNick) {
            val head = prof.lines.filter { it.cy < prof.h * 0.10f && it.cx > prof.w * 0.15f }.map { it.text }
            if (!nickMatch(head, Prefs.bbcNick)) {
                throw Engine.Stop("Nick di Zona Pribadi (" + head.joinToString(" ") + ") tidak cocok dengan " + Prefs.bbcNick.trim() + ". Pengiriman dihentikan")
            }
        }
    }

    private fun openLiveStream(): Scr {
        val s = cap()
        val l = s.lines.firstOrNull { it.norm.contains("livestream") && it.cx < s.w * 0.20f } ?: throw Engine.Stop("Menu Live Stream tidak terbaca")
        tapLine(l, "menu Live Stream")
        waitFor(6500) { it.has("catatanlivestream") } ?: throw Engine.Stop("Halaman Live Stream tidak terbuka")
        // Beri waktu angka dan tombol Tonton selesai dimuat, lalu baca layar yang baru (dipakai juga untuk before/after.png)
        sleepMs(800)
        return cap()
    }

    private fun panelDiamonds(s: Scr): Int? {
        val l = s.lines.firstOrNull {
            it.cy > s.h * 0.88f && it.cx > s.w * 0.40f && it.cx < s.w * 0.65f && it.norm.isNotEmpty() &&
                it.norm.length <= 6 && it.norm.all { c -> c.isDigit() }
        }
        return l?.norm?.toIntOrNull()
    }

    // ---------- kirim Yacht ----------

    private fun sendYacht(ls: Scr) {
        val tn = ls.lines.filter { it.norm == "tonton" && it.cx > ls.w * 0.60f }.maxByOrNull { it.cy }
            ?: throw Engine.Stop("Tombol Tonton tidak terbaca")
        tapLine(tn, "Tonton")
        waitFor(7000) { it.has("trendingpoint") } ?: throw Engine.Stop("Halaman Channel tidak terbuka")
        var gp: Scr? = null
        for (att in 0 until 2) {
            val s = cap()
            tapXY(s.w * 0.866f, s.h * 0.946f, "ikon hadiah")
            gp = waitFor(4500) { it.has("yacht") && it.has("airplane") }
            if (gp != null) break
        }
        val g = gp ?: throw Engine.Stop("Daftar hadiah (Yacht) tidak muncul")
        val y = g.lines.firstOrNull { it.norm == "yacht" } ?: g.find("yacht") ?: throw Engine.Stop("Tulisan Yacht tidak terbaca")
        val before = panelDiamonds(g)
        // Dua kemungkinan yang tidak pernah muncul bersamaan: dialog Beri Hadiah (level cukup) atau tulisan
        // "Mencapai Lv ... untuk mengirim!" tanpa dialog (level kurang, server dilewati)
        var d: Scr? = null
        for (att in 0 until 2) {
            tapXY(y.cx, y.cy - g.h * 0.055f, "Yacht (1000 diamond)")
            d = waitFor(3500) { it.has("berihadiah") || it.has("mencapailv") }
            if (d != null) break
        }
        if (d == null) throw Engine.Stop("Dialog Beri Hadiah tidak muncul")
        if (d.has("mencapailv") && !d.has("berihadiah")) throw Skip("level server belum cukup (Mencapai Lv ... untuk mengirim)")
        if (!d.has("kirimyacht")) throw Engine.Stop("Dialog Beri Hadiah bukan Yacht, tidak dikonfirmasi")
        val kb = d.lines.filter { it.norm == "konfirmasi" && it.cy > d.h * 0.60f }.firstOrNull() ?: d.find("konfirmasi") ?: throw Engine.Stop("Tombol Konfirmasi hadiah tidak terbaca")
        tapLine(kb, "Konfirmasi (kirim Yacht)")
        sleepMs(1500)
        var after = cap()
        if (after.has("berihadiah")) {
            sleepMs(1500)
            after = cap()
        }
        if (after.has("berihadiah")) throw Engine.Stop("Dialog Beri Hadiah belum tertutup setelah Konfirmasi")
        log("Diamond di panel hadiah: sebelum " + (before ?: -1) + ", sesudah " + (panelDiamonds(after) ?: -1))
    }

    // ---------- kembali ke lobby ----------

    private fun toLobby() {
        for (i in 0 until 10) {
            Engine.alive()
            val s = cap()
            val lobby = Engine.isLobby(s) && !Engine.eventOpen(s)
            if (lobby && !s.has("seasonsaatini", "telahmengirimkanlike", "tambahkanteman")) return
            Engine.back()
            sleepMs(450)
        }
        Engine.backToLobby()
    }

    // ---------- pencocokan nick ----------

    private fun normNick(t: String): String {
        val d = Normalizer.normalize(t, Normalizer.Form.NFD)
        return d.replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // Tanpa memperhatikan aksen dan huruf besar-kecil; kemiripan minimal 80%
    private fun nickMatch(texts: List<String>, want: String): Boolean {
        val b = normNick(want)
        if (b.isEmpty()) return true
        val joined = normNick(texts.joinToString(""))
        if (b.length >= 3 && joined.contains(b)) return true
        for (t in texts) {
            val a = normNick(t)
            if (a.length < 2) continue
            val sim = 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
            if (sim >= 0.8) return true
        }
        return false
    }

    // ---------- screenshot before / after ----------

    private fun saveShot(bmp: Bitmap, name: String): Boolean {
        val ctx = Engine.app ?: return false
        try {
            val res = ctx.contentResolver
            try {
                res.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                    arrayOf(name, "Pictures/VGKlaim%")
                )
            } catch (_: Throwable) {
            }
            val cv = ContentValues()
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VGKlaim")
            val uri = res.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            res.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        } catch (e: Throwable) {
            log("Gagal menyimpan " + name + ": " + e.javaClass.simpleName)
            return false
        }
    }
}
