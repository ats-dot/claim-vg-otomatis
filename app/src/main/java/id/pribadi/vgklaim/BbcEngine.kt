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
    private var startLogged = false

    // Dibaca saat lobby baru terdeteksi (selagi menunggu popup): bar atas masih menampilkan diamond
    @Volatile private var seenLevel: Int? = null
    @Volatile private var seenDiamonds = -1

    // ---------- jalan pintas ke Engine ----------

    private fun log(m: String) = Engine.log(m)
    private fun now(): Long = Engine.now()
    private fun sleepMs(ms: Long) = Engine.sleepMs(ms)
    private fun cap(r: Engine.Reg? = null): Scr = Engine.cap(r)
    private fun tapXY(x: Float, y: Float, why: String) = quickTap(x, y, why)
    private fun tapLine(l: Line, why: String) = quickTap(l.cx, l.cy, why)

    // Ketukan satu kali, langsung, tanpa jeda antar langkah dan tanpa diulang
    private fun quickTap(x: Float, y: Float, why: String) {
        Engine.alive()
        log("Ketuk: " + why)
        val svc = ClaimService.inst ?: throw Engine.Stop("Layanan Aksesibilitas tidak aktif")
        if (!svc.tap(x, y)) log("KETUKAN GAGAL (" + why + "): " + svc.lastGestureFail)
    }

    // Tekan Back lalu tunggu sampai isi layar berubah (maksimal 2 detik)
    private fun backAndWait(prevAll: String) {
        Engine.back()
        val t0 = now()
        while (now() - t0 < 2000) {
            Engine.alive()
            if (cap().all != prevAll) return
            sleepMs(40)
        }
    }

    private fun waitFor(maxMs: Long, pred: (Scr) -> Boolean): Scr? {
        val t0 = now()
        while (now() - t0 < maxMs) {
            Engine.alive()
            val s = cap()
            if (pred(s)) return s
            sleepMs(50)
        }
        return null
    }

    // ---------- data pesanan ----------

    fun validate(): String? {
        val id = Prefs.bbcId.trim()
        if (id.isEmpty() || !id.all { it.isDigit() }) return "Isi ID penerima dulu (angka)"
        val a = Prefs.bbcAmount
        if (a <= 0 || a % UNIT != 0) return "Isi Jumlah BBC dalam ribuan (mis. 20 untuk 20.000 BBC)"
        if (Prefs.bbcMatchNick && Prefs.bbcNick.trim().isEmpty()) return "Isi nick dulu, atau matikan Cocokkan nick"
        return null
    }

    fun bannerText(stopped: Boolean): String {
        if (finished) return "Selesai, " + Prefs.fmt(lastTarget) + " BBC terkirim"
        val s = Prefs.bbcPendSent
        val t = Prefs.bbcPendTarget
        if (exhausted) return "Server akun ini habis: terkirim " + Prefs.fmt(s) + " dari " + Prefs.fmt(t) + " BBC. Ganti akun lalu lanjutkan"
        return "Berhenti: terkirim " + Prefs.fmt(s) + " dari " + Prefs.fmt(t) + " BBC"
    }

    private fun setupOrder(): Boolean {
        val id = Prefs.bbcId.trim()
        val amt = Prefs.bbcAmount
        if (Prefs.bbcPendId == id && Prefs.bbcPendTarget > 0) {
            if (Prefs.bbcPendSent >= amt) {
                log("Pesanan ke ID " + id + " sudah terkirim " + Prefs.fmt(Prefs.bbcPendSent) + " BBC. Tidak ada yang dikirim")
                Prefs.bbcClearPending()
                return false
            }
            Prefs.bbcPendTarget = amt
            log("Melanjutkan pesanan ke ID " + id + ": terkirim " + Prefs.fmt(Prefs.bbcPendSent) + " dari " + Prefs.fmt(amt) + " BBC")
        } else {
            Prefs.bbcPendId = id
            Prefs.bbcPendTarget = amt
            Prefs.bbcPendSent = 0
            log("Pesanan baru ke ID " + id + ": 0 dari " + Prefs.fmt(amt) + " BBC")
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

    // Server bernomor di bawah "Mulai dari server" dianggap sudah terpakai dan tidak dikunjungi
    fun belowStart(name: String): Boolean {
        val st = Prefs.bbcStartFrom
        if (st <= 0) return false
        val v = name.filter { it.isDigit() }.toIntOrNull() ?: return false
        if (v >= st) return false
        if (!startLogged) {
            startLogged = true
            log("Server bernomor di bawah " + st + " dilewati (Mulai dari server)")
        }
        return true
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

    // Kalau berhenti karena masalah (bukan karena tombol Berhenti), simpan screenshot layar saat itu
    fun main() {
        try {
            run()
        } catch (e: Engine.Stop) {
            val m = e.message ?: ""
            if (m != "Dihentikan") captureProblem(m)
            throw e
        }
    }

    fun captureProblem(msg: String) {
        try {
            val s = cap()
            if (saveShot(s.bmp, "masalah.png")) log("Screenshot masalah.png disimpan (Pictures/VGKlaim). Masalah: " + msg)
        } catch (_: Throwable) {
        }
    }

    private fun run() {
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
        startLogged = false

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
                exhausted = true
                log("Server di akun ini habis. Terkirim " + Prefs.fmt(Prefs.bbcPendSent) + " dari " + Prefs.fmt(Prefs.bbcPendTarget) + " BBC")
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
        Engine.serverT0 = t0
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
            openFriendDialog()
            typeId(Prefs.bbcId.trim())
            // Tunggu dialog kembali (tombol Cari ID terlihat), catat daftar saran saat itu, lalu langsung ketuk
            val dlg = waitFor(6000) { sc -> sc.lines.any { it.norm.contains("cariid") } } ?: throw Engine.Stop("Dialog Cari ID tidak kembali sesudah mengisi ID")
            val cari = dlg.lines.first { it.norm.contains("cariid") }
            val before = firstRowSig(dlg)
            quickTap(cari.cx, cari.cy, "Cari ID")
            val card = searchAndOpen(before, cari)
            openProfileFull(card)
            val first = Prefs.bbcPendSent == 0
            openLiveStream(if (first) "before.png" else null, true)
            sendYacht()
            val sentNow = Prefs.bbcPendSent + UNIT
            Prefs.bbcPendSent = sentNow
            // Catat segera supaya tidak hilang kalau proses berhenti sesudah ini
            if (name != null) {
                markUsed(name)
                if (Engine.acct != "-") Prefs.bbcClearTry(Engine.acct)
            } else if (Engine.acct != "-") {
                Prefs.bbcSetTry(Engine.acct, "!")
            }
            log("Kiriman berhasil di " + label + ": terkirim " + Prefs.fmt(sentNow) + " dari " + Prefs.fmt(Prefs.bbcPendTarget) + " BBC (" + ((now() - t0) / 1000) + " dtk)")
            // kembali ke profil
            Engine.back()
            waitFor(4000) { it.has("zonapribadi") }
            if (sentNow >= Prefs.bbcPendTarget) {
                openLiveStream("after.png", false)
                lastTarget = Prefs.bbcPendTarget
                Prefs.bbcClearPending()
                finished = true
                log("SELESAI. Pesanan " + Prefs.fmt(lastTarget) + " BBC terkirim ke ID " + Prefs.bbcId.trim())
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

    private var lastTonton: Pair<Float, Float>? = null

    // Ketuk lalu tunggu layar tujuan. Kalau belum muncul dalam 1 detik, baca ulang dan ketuk SATU kali lagi, lalu tunggu lebih lama
    private fun tapRetry(x: Float, y: Float, why: String, ready: (Scr) -> Boolean, waitMs: Long, failMsg: String): Scr {
        quickTap(x, y, why)
        val r = waitFor(1000) { ready(it) }
        if (r != null) return r
        val c = cap()
        if (ready(c)) return c
        log("Belum terbuka setelah 1 detik, mengetuk sekali lagi: " + why)
        quickTap(x, y, why + " (ulang)")
        return waitFor(waitMs) { ready(it) } ?: throw Engine.Stop(failMsg)
    }

    // Tangani popup di lobby sampai layar bersih (popup biasa, popup gelap tanpa tombol, Mastery Code, dialog Keluar, dan lainnya)
    private fun clearLobbyPopups(): Scr {
        for (i in 0 until 10) {
            Engine.alive()
            val s = cap()
            if (Engine.popups(s)) continue
            if (Engine.darkPopupX(s)) continue
            return s
        }
        return cap()
    }

    // Ketuk sebuah titik di lobby setelah layar dipastikan bebas popup. Kalau layar tujuan tidak terbuka, periksa popup:
    // ada popup berarti ketukan tadi terhalang, popup ditangani lalu ketuk lagi; tidak ada popup berarti berhenti dengan sebab jelas
    private fun openFromLobby(fx: Float, fy: Float, why: String, ready: (Scr) -> Boolean, failMsg: String, known: Scr? = null): Scr {
        for (attempt in 0 until 3) {
            val lob = if (known != null) known else clearLobbyPopups()
            val x = lob.w * fx
            val y = lob.h * fy
            quickTap(x, y, why)
            val r1 = waitFor(1000) { ready(it) }
            if (r1 != null) return r1
            val s0 = cap()
            if (ready(s0)) return s0
            // Belum terbuka: kalau ada popup, tangani dulu lalu ketuk lagi
            if (Engine.popups(s0) || Engine.darkPopupX(s0) || s0.has("bintangkeberuntungan", "penawarankhusus")) {
                log("Ketukan terhalang popup, popup ditangani lalu mengetuk lagi")
                clearLobbyPopups()
                continue
            }
            log("Belum terbuka setelah 1 detik, mengetuk sekali lagi: " + why)
            quickTap(x, y, why + " (ulang)")
            val r2 = waitFor(5000) { ready(it) }
            if (r2 != null) return r2
            val s1 = cap()
            if (!(Engine.popups(s1) || Engine.darkPopupX(s1) || s1.has("bintangkeberuntungan", "penawarankhusus"))) throw Engine.Stop(failMsg)
            log("Ketukan terhalang popup, popup ditangani lalu mengetuk lagi")
            clearLobbyPopups()
        }
        throw Engine.Stop(failMsg)
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
    // Dipakai Klaim VG (saklar Topup): satu kali alur Top Up di server ini. Kegagalan tidak menghentikan klaim:
    // koin kurang, bonus sudah terpakai, atau menu tidak terbuka hanya dicatat di log, lalu lanjut pindah server
    internal fun topUpOnly() {
        Engine.status = "Topup di server ini"
        try {
            val r = buyDiamonds()
            if (r != 0) {
                log("Topup dilewati: Vanguard Coin belum cukup")
                Engine.backToLobby()
            }
        } catch (e: Skip) {
            log("Topup dilewati: " + e.why)
            Engine.backToLobby()
        } catch (e: Engine.Stop) {
            val m = e.message ?: ""
            if (m == "Dihentikan" || m.startsWith("20 menit")) throw e
            log("Topup gagal, lanjut ke server berikutnya: " + m)
            Engine.backToLobby()
        }
    }

    private fun buyDiamonds(): Int {
        var t = openFromLobby(0.730f, 0.054f, "tanda + di lobby (Top Up)", { isTopUp(it) }, "Menu Top Up tidak terbuka")
        // Harga harus dalam Vanguard Coin (mode diamond menampilkan harga Rupiah "Rp ..."). Ketuk Ganti, tunggu 1 detik;
        // kalau harganya belum berubah, ketuk SATU kali lagi (ketukan pertama bisa terabaikan saat halaman masih beranimasi)
        if (rpMode(t)) {
            val g = t.lines.firstOrNull { it.norm == "ganti" } ?: t.find("ganti") ?: throw Engine.Stop("Tombol Ganti tidak terbaca di menu Top Up")
            tapLine(g, "Ganti (harga Vanguard Coin)")
            val ok1 = waitFor(1000) { !rpMode(it) && isTopUp(it) }
            if (ok1 != null) {
                t = ok1
            } else {
                val c = cap()
                if (!rpMode(c) && isTopUp(c)) {
                    t = c
                } else {
                    val g2 = c.lines.firstOrNull { it.norm == "ganti" } ?: g
                    log("Harga belum berubah setelah 1 detik, mengetuk Ganti sekali lagi")
                    tapLine(g2, "Ganti (ulang, harga belum berubah)")
                    t = waitFor(4000) { !rpMode(it) && isTopUp(it) } ?: throw Engine.Stop("Harga masih Rupiah setelah mengetuk Ganti dua kali")
                }
            }
        }
        val card = t.lines.firstOrNull { it.norm == "500diamond" }
            ?: t.lines.firstOrNull { it.norm.length == 10 && lev(it.norm, "500diamond") <= 1 }
            ?: throw Engine.Stop("Kartu 500 Diamond tidak ketemu di menu Top Up")
        if (!firstBadge(t, card)) throw Skip("bonus Top Up Pertama 500 Diamond sudah terpakai", true)
        val cx500 = card.cx + t.w * 0.034f
        val cy500 = card.cy + t.h * 0.12f
        quickTap(cx500, cy500, "kartu 500 Diamond")
        var d = waitFor(1000) { it.has("pemberitahuansistem", "lanjutkan", "tidakcukup") }
        if (d == null) {
            log("Dialog harga belum muncul setelah 1 detik, mengetuk kartu sekali lagi")
            quickTap(cx500, cy500, "kartu 500 Diamond (ulang)")
            d = waitFor(2500) { it.has("pemberitahuansistem", "lanjutkan", "tidakcukup") }
        }
        if (d == null || (d.has("tidakcukup") && !d.has("lanjutkan"))) {
            log("Dialog harga tidak muncul: Vanguard Coin tidak cukup")
            return 1
        }
        val price = d.lines.firstOrNull { it.norm == "500" && it.cx > d.w * 0.45f && it.cx < d.w * 0.56f && it.cy > d.h * 0.38f && it.cy < d.h * 0.58f }
        if (price == null) throw Engine.Stop("Harga di dialog bukan 500, tidak dikonfirmasi")
        val kb = d.lines.firstOrNull { it.norm == "konfirmasi" } ?: d.find("konfirmasi") ?: throw Engine.Stop("Tombol Konfirmasi tidak terbaca")
        tapLine(kb, "Konfirmasi (500 Diamond)")
        val res = waitFor(6500) { it.has("tidakcukup") || it.has("memperolehhadiah") } ?: throw Engine.Stop("Hasil pembelian tidak dikenali")
        if (res.has("tidakcukup")) return 1
        // Hasil pembelian: ketuk OK dan X hanya setelah terlihat di layar. Begitu halaman Top Up bersih (tanpa popup),
        // langsung keluar tanpa menunggu popup kedua (kadang memang tidak muncul)
        var okTapped = false
        var closed = 0
        var lastAct = now()
        var first = true
        val tEnd = now() + 10000
        while (now() < tEnd) {
            Engine.alive()
            val s2 = if (first) res else cap()
            first = false
            if (s2.has("memperolehhadiah")) {
                val o2 = Engine.okBtn(s2)
                quickTap(o2?.cx ?: (s2.w * 0.5f), o2?.cy ?: (s2.h * 0.893f), "OK (Memperoleh Hadiah)")
                okTapped = true
                lastAct = now()
            } else if (s2.has("pilihhadiah", "topupsekarang", "skindeluxe")) {
                quickTap(s2.w * 0.858f, s2.h * 0.076f, "tutup X (popup Top Up)")
                closed++
                lastAct = now()
            } else if ((okTapped || closed >= 1) && isTopUp(s2)) {
                break
            } else {
                // Layar belum dikenali (peralihan): tunggu sebentar, jangan lebih dari 2 detik
                if (now() - lastAct > 2000) break
                sleepMs(50)
            }
        }
        log("Top Up 500 Diamond selesai")
        Engine.backToLobby()
        return 0
    }

    // ---------- Squad, cari akun, profil ----------

    private fun closeSquadPopup(first: Scr) {
        var s = first
        if (!s.has("squadtersedia")) return
        log("Popup Sekarang Squad Tersedia ada, dihilangkan")
        tapXY(s.w * 0.933f, s.h * 0.681f, "ikon Squad")
        for (i in 0 until 40) {
            Engine.alive()
            s = cap()
            val lobby = Engine.isLobby(s) && !Engine.eventOpen(s)
            if (lobby && !s.has("squadtersedia")) return
            if (lobby) {
                sleepMs(60)
                continue
            }
            backAndWait(s.all)
        }
    }

    // Satu bacaan layar untuk memastikan lobby (sekaligus mengecek popup Squad), lalu langsung mengetuk ikon orang +.
    // Popup baru diperiksa kalau dialog tidak terbuka; ketukan ulang satu kali setelah 1 detik.
    private fun openFriendDialog() {
        val s = waitFor(8000) { sc ->
            (sc.has("tambahkanteman") && sc.has("cariid")) || (Engine.isLobby(sc) && !Engine.eventOpen(sc) && !sc.has("masterycode"))
        } ?: throw Engine.Stop("Lobby tidak siap untuk membuka dialog teman")
        if (s.has("tambahkanteman") && s.has("cariid")) return
        closeSquadPopup(s)
        openFromLobby(0.893f, 0.679f, "ikon orang + (tambah teman)", { it.has("tambahkanteman") }, "Dialog Tambahkan Teman tidak terbuka", s)
    }

    // Isi ID, tutup papan ketik dengan OK (Back membatalkan isian), lalu pastikan ID benar-benar tampil di kotak
    // sebelum Cari ID diketuk. Kalau tidak tampil, isi ulang sekali; kalau tetap tidak, berhenti (jangan cari kosong)
    private fun typeId(id: String) {
        for (round in 0 until 2) {
            fillId(id)
            if (waitFor(1800) { it.all.contains(id.take(7)) } != null) return
            log("ID belum terlihat di kotak pencarian, mengisi ulang")
        }
        throw Engine.Stop("ID tidak terisi di kotak pencarian. Pencarian dibatalkan")
    }

    private fun fillId(id: String) {
        val s0 = cap()
        val box = s0.lines.firstOrNull { it.norm.contains("ketukuntuk") }
        quickTap(box?.cx ?: (s0.w * 0.298f), box?.cy ?: (s0.h * 0.222f), "kotak ID")
        val svc = ClaimService.inst ?: throw Engine.Stop("Layanan Aksesibilitas tidak aktif")
        // Isi seluruh ID sekaligus begitu papan ketik siap (ulangi tiap 0,2 detik, maksimal 4 detik)
        var r = ""
        var retapped = false
        val t0 = now()
        while (now() - t0 < 4000) {
            Engine.alive()
            r = svc.inputText(id)
            if (r == "OK" || r.contains("jendela=0")) break
            if (!retapped && now() - t0 > 1000) {
                retapped = true
                log("Papan ketik belum siap setelah 1 detik, mengetuk kotak ID sekali lagi")
                quickTap(box?.cx ?: (s0.w * 0.298f), box?.cy ?: (s0.h * 0.222f), "kotak ID (ulang)")
            }
            sleepMs(200)
        }
        log("Isi ID lewat Aksesibilitas: " + r)
        if (r == "OK") {
            commitKeyboard(svc)
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
    // Papan ketik dianggap tampil kalau jendela IME terlihat, atau (cadangan) terbaca dari layar
    private fun keyboardUp(svc: ClaimService?, s: Scr, digits: Boolean = true): Boolean {
        if (svc != null && svc.keyboardVisible()) return true
        return (digits && digitKeys(s) != null) || s.has("bahasaindonesia") || (s.has("indonesia") && (s.has("tambahkanteman") || s.has("cariid")))
    }

    private fun okLine(s: Scr): Line? =
        s.lines.firstOrNull { (it.norm == "oke" || it.norm == "ok" || it.norm == "selesai") && it.cx > s.w * 0.80f && it.cy < s.h * 0.45f }

    private fun waitKeyboardGone(svc: ClaimService, ms: Long): Boolean {
        val t0 = now()
        while (now() - t0 < ms) {
            Engine.alive()
            if (!svc.keyboardVisible()) return true
            sleepMs(50)
        }
        return false
    }

    // Simpan isian dan tutup papan ketik dengan tombol OK di bilah isian. Back TIDAK dipakai di sini karena membatalkan isian
    // (ID terhapus). Urutan: klik OK lewat node Aksesibilitas (cepat), lalu OK terbaca dari layar, dan Back hanya cadangan terakhir
    private fun commitKeyboard(svc: ClaimService) {
        if (!svc.keyboardVisible()) {
            val s0 = cap()
            if (!keyboardUp(null, s0, false)) return
        }
        val t0 = now()
        while (now() - t0 < 1200) {
            Engine.alive()
            if (!svc.keyboardVisible()) return
            if (svc.clickInputOk()) {
                log("Ketuk: OKE (selesai mengisi ID)")
                if (waitKeyboardGone(svc, 1500)) return
                break
            }
            sleepMs(60)
        }
        for (attempt in 0 until 2) {
            Engine.alive()
            val s = waitFor(2500) { sc -> !keyboardUp(svc, sc, false) || okLine(sc) != null } ?: break
            if (!keyboardUp(svc, s, false)) return
            val ok = okLine(s)
            if (ok != null) {
                tapLine(ok, "OKE (selesai mengisi ID)")
                if (waitKeyboardGone(svc, 1500)) return
            }
        }
        log("Tombol OK tidak terbaca, menutup papan ketik dengan Back")
        Engine.back()
        waitKeyboardGone(svc, 1500)
    }

    private fun pressOke() {
        val s = cap()
        val ok = s.lines.firstOrNull { (it.norm == "oke" || it.norm == "ok" || it.norm == "selesai") && it.cx > s.w * 0.80f && it.cy < s.h * 0.45f }
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
            val kb = keyboardUp(ClaimService.inst, s)
            if (!kb) return
            val ok = s.lines.firstOrNull { (it.norm == "oke" || it.norm == "ok" || it.norm == "selesai") && it.cx > s.w * 0.80f && it.cy < s.h * 0.45f }
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

    // Cari ID sudah diketuk. Tunggu daftar berubah; kalau belum berubah setelah 1 detik, ketuk Cari ID SATU kali lagi.
    // Lalu baca hasil teratas, ketuk akun itu (ulang sekali setelah 1 detik), dan kembalikan layar kartu profil
    private fun searchAndOpen(beforeSig: String, cari: Line): Scr {
        var ch = waitFor(1000) { firstRowSig(it) != beforeSig }
        if (ch == null) {
            log("Hasil pencarian belum berubah setelah 1 detik, mengetuk Cari ID sekali lagi")
            quickTap(cari.cx, cari.cy, "Cari ID (ulang)")
            ch = waitFor(9000) { firstRowSig(it) != beforeSig } ?: throw Engine.Stop("Hasil pencarian tidak berubah: ID tidak ditemukan atau Cari ID tidak terketuk")
        }
        val fin = cap()
        val texts = rowCands(fin).map { it.text }
        log("Hasil teratas pencarian: " + texts.joinToString(" | "))
        if (Prefs.bbcMatchNick && !nickMatch(texts, Prefs.bbcNick)) {
            throw Engine.Stop("Nick hasil teratas (" + texts.joinToString(" ") + ") tidak cocok dengan " + Prefs.bbcNick.trim() + ". Pengiriman dihentikan")
        }
        // Kartu profil dianggap terbuka kalau dialog pencarian (tombol Cari ID dan Cari Nama) sudah tidak terlihat
        return tapRetry(fin.w * 0.417f, fin.h * 0.361f, "akun hasil teratas", { sc -> !sc.has("cariid") && !sc.has("carinama") }, 6500,
            "Kartu profil tidak terbuka setelah mengetuk akun (dialog pencarian masih terlihat)")
    }

    private fun openProfileFull(card: Scr) {
        val prof = tapRetry(card.w * 0.717f, card.h * 0.736f, "ikon pencarian (profil lengkap)", { it.has("zonapribadi") }, 6000, "Halaman Zona Pribadi tidak terbuka")
        if (Prefs.bbcMatchNick) {
            // Judul "Zona Pribadi <nick>" dan nama besar di bawahnya; kata "Zona Pribadi" dan "Pengaturan" dibuang
            val head = prof.lines.filter { it.cy < prof.h * 0.22f && it.cx > prof.w * 0.15f }.map { it.text }
            if (!nickInText(head, Prefs.bbcNick)) {
                throw Engine.Stop("Nick di Zona Pribadi (" + head.joinToString(" ") + ") tidak cocok dengan " + Prefs.bbcNick.trim() + ". Pengiriman dihentikan")
            }
        }
    }

    // Buka menu Live Stream, tunggu sampai tombol Tonton terlihat (halaman sudah lengkap). Langsung ketuk Tonton (kalau diminta),
    // lalu simpan screenshot dari layar yang sudah terbaca itu (before.png / after.png)
    private fun openLiveStream(shotName: String?, thenTonton: Boolean) {
        val s = waitFor(6000) { sc -> sc.lines.any { it.norm.contains("livestream") && it.cx < sc.w * 0.20f } } ?: throw Engine.Stop("Menu Live Stream tidak terbaca")
        val l = s.lines.first { it.norm.contains("livestream") && it.cx < s.w * 0.20f }
        quickTap(l.cx, l.cy, "menu Live Stream")
        if (waitFor(1000) { it.has("catatanlivestream") } == null) {
            val c1 = cap()
            if (!c1.has("catatanlivestream")) {
                log("Halaman Live Stream belum terbuka setelah 1 detik, mengetuk menu sekali lagi")
                quickTap(l.cx, l.cy, "menu Live Stream (ulang)")
            }
        }
        var page: Scr? = null
        var tn: Line? = null
        val t0 = now()
        while (now() - t0 < 8000) {
            Engine.alive()
            val c = cap()
            val t = c.lines.filter { it.norm == "tonton" && it.cx > c.w * 0.60f }.maxByOrNull { it.cy }
            if (c.has("catatanlivestream") && t != null) {
                page = c
                tn = t
                break
            }
            sleepMs(60)
        }
        if (page == null || tn == null) throw Engine.Stop("Tombol Tonton tidak terlihat di halaman Live Stream")
        if (thenTonton) {
            lastTonton = Pair(tn.cx, tn.cy)
            quickTap(tn.cx, tn.cy, "Tonton")
        }
        if (shotName != null && saveShot(page.bmp, shotName)) log("Screenshot " + shotName + " disimpan (Pictures/VGKlaim)")
    }

    private fun panelDiamonds(s: Scr): Int? {
        val l = s.lines.firstOrNull {
            it.cy > s.h * 0.88f && it.cx > s.w * 0.40f && it.cx < s.w * 0.65f && it.norm.isNotEmpty() &&
                it.norm.length <= 6 && it.norm.all { c -> c.isDigit() }
        }
        return l?.norm?.toIntOrNull()
    }

    // ---------- kirim Yacht ----------

    // Titik ketuk ikon Yacht di panel hadiah. Ada dua susunan panel: dengan nama item ("Yacht", "Airplane" tampil),
    // atau hanya harga di bawah tiap ikon (5000, 1000, 250). Mengembalikan null kalau panel belum terbuka
    private fun yachtPoint(s: Scr): Pair<Float, Float>? {
        if (s.has("airplane")) {
            val y = s.lines.firstOrNull { it.norm == "yacht" } ?: s.find("yacht")
            if (y != null) return Pair(y.cx, y.cy - s.h * 0.055f)
        }
        val row = s.lines.filter { it.cx > s.w * 0.50f && it.cy > s.h * 0.55f && it.cy < s.h * 0.82f && it.norm.length <= 7 }
        val p1 = row.firstOrNull { it.norm.endsWith("1000") } ?: return null
        val p5 = row.firstOrNull { it.norm.endsWith("5000") } ?: return null
        val p2 = row.firstOrNull { it.norm.endsWith("250") } ?: return null
        if (!(p5.cx < p1.cx && p1.cx < p2.cx)) return null
        return Pair(p1.cx, p1.cy - s.h * 0.086f)
    }

    // Nama item kadang tidak tampil di dialog ("Kirim  ke Streamer!"). Kenali Yacht dari baris Harga 1000
    private fun yachtDialogByPrice(d: Scr): Boolean {
        if (!d.has("harga")) return false
        return d.lines.any {
            it.norm.endsWith("1000") && it.cx > d.w * 0.55f && it.cx < d.w * 0.72f && it.cy > d.h * 0.55f && it.cy < d.h * 0.72f
        }
    }

    private fun sendYacht() {
        var ch = waitFor(1000) { it.has("trendingpoint") }
        if (ch == null) {
            val tp = lastTonton
            if (tp != null && !cap().has("trendingpoint")) {
                log("Halaman Channel belum terbuka setelah 1 detik, mengetuk Tonton sekali lagi")
                quickTap(tp.first, tp.second, "Tonton (ulang)")
            }
            ch = waitFor(6500) { it.has("trendingpoint") } ?: throw Engine.Stop("Halaman Channel tidak terbuka")
        }
        var g = tapRetry(ch.w * 0.866f, ch.h * 0.946f, "ikon hadiah", { yachtPoint(it) != null }, 6000, "Daftar hadiah (Yacht) tidak muncul")
        var yp = yachtPoint(g) ?: throw Engine.Stop("Ikon Yacht tidak terbaca")
        // Panel hadiah naik dengan animasi: ketuk Yacht hanya kalau posisinya sama di dua bacaan berturut-turut (panel sudah diam)
        val tStable = now()
        while (now() - tStable < 4000L) {
            Engine.alive()
            val c = cap()
            val p2 = yachtPoint(c) ?: continue
            val stable = Math.abs(p2.first - yp.first) < c.w * 0.004f && Math.abs(p2.second - yp.second) < c.h * 0.006f
            g = c
            yp = p2
            if (stable) break
        }
        val before = panelDiamonds(g)
        // Dua kemungkinan yang tidak pernah muncul bersamaan: dialog Beri Hadiah (level cukup) atau tulisan
        // "Mencapai Lv ... untuk mengirim!" tanpa dialog (level kurang, server dilewati)
        val d = tapRetry(yp.first, yp.second, "Yacht (1000 diamond)", { it.has("berihadiah") || it.has("mencapailv") }, 4000, "Dialog Beri Hadiah tidak muncul")
        if (d.has("mencapailv") && !d.has("berihadiah")) throw Skip("level server belum cukup (Mencapai Lv ... untuk mengirim)")
        if (!d.has("kirimyacht") && !yachtDialogByPrice(d)) throw Engine.Stop("Dialog Beri Hadiah bukan Yacht, tidak dikonfirmasi")
        val kb = d.lines.filter { it.norm == "konfirmasi" && it.cy > d.h * 0.60f }.firstOrNull() ?: d.find("konfirmasi") ?: throw Engine.Stop("Tombol Konfirmasi hadiah tidak terbaca")
        quickTap(kb.cx, kb.cy, "Konfirmasi (kirim Yacht)")
        val after = waitFor(5000) { !it.has("berihadiah") } ?: throw Engine.Stop("Dialog Beri Hadiah belum tertutup setelah Konfirmasi")
        log("Diamond di panel hadiah: sebelum " + (before ?: -1) + ", sesudah " + (panelDiamonds(after) ?: -1))
    }

    // ---------- kembali ke lobby ----------

    private fun toLobby() {
        for (i in 0 until 10) {
            Engine.alive()
            val s = cap()
            if (s.has("masterycode")) {
                Engine.waitMasteryCode()
                continue
            }
            val lobby = Engine.isLobby(s) && !Engine.eventOpen(s)
            if (lobby && !s.has("seasonsaatini", "telahmengirimkanlike", "tambahkanteman", "hadiahgratis", "belumdiklaimhariini")) return
            backAndWait(s.all)
        }
        Engine.backToLobby()
    }

    // ---------- pencocokan nick ----------

    private fun normNick(t: String): String {
        val d = Normalizer.normalize(t, Normalizer.Form.NFD)
        // Huruf yang mirip rupa dianggap sama (1 I l, 0 O, 5 S, 8 B) supaya salah baca OCR tidak menggagalkan pencocokan
        return d.replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }.map { ch ->
            when (ch) {
                '1', 'l' -> 'i'
                '0' -> 'o'
                '5' -> 's'
                '8' -> 'b'
                else -> ch
            }
        }.joinToString("")
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

    // Cari nick di dalam teks yang bercampur kata lain: kata judul dibuang, lalu cocokkan sebagian tulisan (kemiripan minimal 80%)
    private fun nickInText(texts: List<String>, want: String): Boolean {
        val b = normNick(want)
        if (b.length < 2) return true
        val hay = normNick(texts.joinToString(" ")).replace("zonapribadi", "").replace("pengaturan", "")
        if (hay.contains(b)) return true
        for (len in maxOf(2, b.length - 1)..(b.length + 1)) {
            var i = 0
            while (i + len <= hay.length) {
                val sub = hay.substring(i, i + len)
                val sim = 1.0 - lev(sub, b).toDouble() / maxOf(sub.length, b.length)
                if (sim >= 0.8) return true
                i++
            }
        }
        return false
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
                // Hapus nama polos dan juga salinan bernomor milik sendiri (mis. "before (3).png") supaya tidak menumpuk
                val dot = name.lastIndexOf('.')
                val stem = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                res.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "(" + MediaStore.Images.Media.DISPLAY_NAME + "=? OR " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?) AND " +
                        MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?",
                    arrayOf(name, stem + " (%)" + ext, "Pictures/VGKlaim%")
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
