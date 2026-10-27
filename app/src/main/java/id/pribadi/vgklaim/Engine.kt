package id.pribadi.vgklaim

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.PowerManager

object Engine {
    @Volatile var running = false
    @Volatile var status = "Berhenti"
    @Volatile var total = 0
    @Volatile var doneCount = 0

    private class Stop(msg: String) : RuntimeException(msg)

    private var app: Context? = null
    private var thread: Thread? = null
    private var wl: PowerManager.WakeLock? = null
    private var lastShot = 0L
    private var lastActive = 0L
    private var serverT0 = 0L
    private var gamePkg = ""

    private val order = ArrayList<String>()
    private val done = HashSet<String>()
    private val skipped = HashSet<String>()
    private var cur: String? = null

    // Kode server di daftar game: huruf s + angka (contoh s57094).
    // Nomor "Katelvira N" sengaja tidak dipakai (bisa salah ketik).
    private val SRV = Regex("s[0-9]{4,6}")
    private var flip = 0
    private var capN = 0
    private var capShotMs = 0L
    private var capOcrMs = 0L
    private var areaN = 0
    private var areaShotMs = 0L
    private var areaOcrMs = 0L
    private var lastW = 0
    private var lastH = 0
    private var blindX = -1f
    private var blindY = -1f
    private var blindWhy = ""
    private var topupTaps = 0
    private var topupBlockUntil = 0L
    private var lastTapX = -1000f
    private var lastTapY = -1000f
    private var lastTapT = 0L
    private var lastTapWhy = ""
    private var minGap = 345L
    private var darkTaps = 0

    // TAHAP 1: area baca (pecahan layar: kiri, atas, kanan, bawah). Langkah yang sudah dikenal
    // hanya membaca area kecil, bukan seluruh layar. Angka dari screenshot layar 2400x1080.
    private class Reg(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private val R_MENU = Reg(0.04f, 0.08f, 0.27f, 0.99f)   // daftar menu kiri halaman Hadiah
    private val R_EVT = Reg(0.04f, 0.0f, 0.62f, 1.0f)      // judul halaman + menu kiri + judul besar (cek halaman Hadiah terbuka)
    private val R_PANEL = Reg(0.38f, 0.05f, 1.0f, 1.0f)    // panel kanan Hadiah (Klaim, Pergi, koin)
    private val R_OK = Reg(0.20f, 0.60f, 0.80f, 1.0f)      // tombol OK / ketuk untuk lanjut
    private val R_PROFIL = Reg(0.0f, 0.10f, 0.46f, 1.0f)   // ID akun + tab kiri layar Profil
    private val R_AKUN = Reg(0.62f, 0.10f, 1.0f, 0.95f)    // tombol kanan Pengaturan Akun
    private val R_SERVER = Reg(0.12f, 0.12f, 0.88f, 0.88f) // daftar karakter, tombol emas, popup sistem
    private val R_BOTTOM = Reg(0.0f, 0.80f, 1.0f, 1.0f)    // strip bawah: Memuat Game + menu lobby
    private val R_TOP = Reg(0.0f, 0.0f, 1.0f, 0.14f)
    private val R_ROLE = Reg(0.55f, 0.0f, 1.0f, 0.09f)     // "Role: ID akun" di kanan atas layar Memuat Game
    private val R_ACCID = Reg(0.70f, 0.12f, 1.0f, 0.20f)  // "Account ID" di Pengaturan Akun
    private val R_ID = Reg(0.20f, 0.14f, 0.50f, 0.26f)     // baris "ID: ... (kode server)" di Profil, dibaca resolusi penuh       // strip atas: judul halaman (Pusat Bantuan)
    private var acct: String = "-"
    private var firstPending = false
    private var lastClaimed: String? = null

    // Ambang pembacaan warna koin x20 (jumlah piksel); lihat log "Koin x20: emas=..., titik merah=..."
    // Diuji pada screenshot: belum/siap = 15.000-18.000 piksel emas, sudah diklaim = ~2.500 (hanya lencana centang);
    // titik merah koin siap = ~69 piksel, selain itu 0.
    private val GOLD_MIN = 7000
    private val RED_MIN = 25
    private val GRAY_MIN = 10000
    private var lastXx = -999f
    private var lastXy = -999f
    private var xRepeat = 0
    private var xBlockUntil = 0L

    // Posisi koin x20 di halaman Masukan Advance Server (persen layar). Perkiraan dari screenshot.
    private val COIN_X = 0.773f
    private val COIN_Y = 0.537f

    // Ikon petir (tab "Hadiah") di sisi kiri layar Event, persen layar dari screenshot.
    // Posisinya hampir sama dengan ikon Event di lobby.
    private val PETIR_X = 0.063f
    private val PETIR_Y = 0.51f

    fun start(ctx: Context): String? {
        Prefs.init(ctx)
        if (running) return "Sudah berjalan"
        if (Prefs.serverCount < 2) return "Isi Jumlah server dulu (angka 2 atau lebih)"
        if (ClaimService.inst == null) return "Aktifkan dulu Aksesibilitas VG Klaim (tab Pengaturan)"
        app = ctx.applicationContext
        Matcher.reload(ctx.applicationContext)
        running = true
        status = "Berjalan"
        val fe = KeepAliveService.start(ctx.applicationContext)
        if (fe != null) log("Layanan latar gagal dimulai (" + fe + "). Klaim tetap jalan tanpa layanan latar.")
        else log("Layanan latar dimulai (notifikasi VG Klaim berjalan)")
        total = 0
        doneCount = 0
        lastActive = now()
        thread = Thread { runMain() }.also {
            it.isDaemon = true
            it.start()
        }
        return null
    }

    fun stop() {
        running = false
        status = "Berhenti"
        thread?.interrupt()
        app?.let { KeepAliveService.stop(it) }
    }

    // Gaya titik melayang hasil Diagnosa: 0 tanpa titik, 1 putih 14dp, 2 putih kecil 2dp,
    // 3 hampir transparan (5%), 4 transparan penuh
    private fun applyPerf() {
        val m = if (Prefs.dotAuto) Prefs.dotStyle else 0
        if (m >= 1) {
            ClaimService.inst?.dot(m)
            log("Titik melayang gaya " + m + " aktif (" + dotName(m) + ")")
        }
    }

    private fun releasePerf() {
        ClaimService.inst?.dot(0)
    }

    private fun dotName(m: Int): String = when (m) {
        1 -> "putih 14 dp"
        2 -> "putih kecil 2 dp"
        3 -> "putih hampir transparan 5%"
        4 -> "1 piksel tanpa warna"
        5 -> "14 dp tanpa warna"
        else -> "tanpa titik"
    }

    private fun runMain() {
        val ctx = app ?: return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VGKlaim:klaim")
            wl?.acquire(3L * 60 * 60 * 1000)
        } catch (_: Throwable) {
        }
        try {
            applyPerf()
            main()
            status = "Selesai"
            log("SELESAI. Server terklaim hari ini: " + doneCount + " dari " + total)
            showBanner(false)
        } catch (e: Stop) {
            status = "Berhenti"
            log("Berhenti: " + e.message)
            showBanner(true)
        } catch (e: InterruptedException) {
            status = "Berhenti"
            log("Dihentikan")
            showBanner(true)
        } catch (e: Throwable) {
            status = "Error"
            log("Error: " + e.toString())
            showBanner(true)
        } finally {
            running = false
            releasePerf()
            app?.let { KeepAliveService.stop(it) }
            try {
                wl?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun log(m: String) = AppLog.add(m)

    private fun alive() {
        if (!running) throw Stop("Dihentikan")
        if (now() - lastActive > 20 * 60_000L) throw Stop("20 menit tanpa aktivitas")
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0) {
            if (!running) throw Stop("Dihentikan")
            val s = if (left > 200) 200L else left
            val t0 = now()
            Thread.sleep(s)
            val dt = now() - t0
            if (dt > s + 2500L) {
                log("Peringatan: proses tertahan " + dt + " ms saat menunggu " + s + " ms (mungkin dibekukan/dibatasi sistem, cek pengaturan baterai)")
            }
            left -= s
        }
    }

    private fun sleepS(sec: Double) = sleepMs((sec * 1000).toLong())

    private fun speed(): Double = Prefs.speed.toDouble().coerceIn(1.0, 3.0)

    private fun rect(b: Bitmap, r: Reg): Rect {
        val x0 = (b.width * r.x0).toInt().coerceIn(0, b.width - 40)
        val y0 = (b.height * r.y0).toInt().coerceIn(0, b.height - 40)
        val x1 = (b.width * r.x1).toInt().coerceIn(x0 + 32, b.width)
        val y1 = (b.height * r.y1).toInt().coerceIn(y0 + 32, b.height)
        return Rect(x0, y0, x1, y1)
    }

    private fun setBlind(x: Float, y: Float, why: String) {
        blindX = x
        blindY = y
        blindWhy = why
    }

    private fun clearBlind() {
        blindX = -1f
    }

    // Screenshot + OCR. reg = null: baca layar penuh. reg diisi: baca hanya area itu (lebih cepat).
    private fun cap(reg: Reg? = null): Scr {
        var tries = 0
        var recovered = false
        var blindDone = false
        var lastLoop = now()
        var rateHits = 0
        while (true) {
            alive()
            val svc = ClaimService.inst ?: throw Stop("Aksesibilitas mati")
            val gap = minGap - (now() - lastShot)
            if (gap > 0) sleepMs(gap)
            lastShot = now()
            val t1 = now()
            if (tries > 0 && t1 - lastLoop > 8000L) {
                log("Peringatan: jeda antar percobaan screenshot " + (t1 - lastLoop) + " ms (normalnya sekitar 4000). Proses mungkin ditahan sistem")
            }
            lastLoop = t1
            val bmp = svc.shot()
            val t2 = now()
            if (bmp != null) {
                lastW = bmp.width
                lastH = bmp.height
                val rc = if (reg != null) rect(bmp, reg) else null
                var lines = Ocr.read(bmp, rc)
                if (lines == null) {
                    log("OCR gagal, mencoba lagi")
                    lines = Ocr.read(bmp, rc)
                    if (lines == null) log("OCR gagal dua kali, layar dianggap kosong")
                }
                val t3 = now()
                if (reg == null) {
                    capN++
                    capShotMs += t2 - t1
                    capOcrMs += t3 - t2
                } else {
                    areaN++
                    areaShotMs += t2 - t1
                    areaOcrMs += t3 - t2
                }
                if (tries > 0) log("Screenshot pulih setelah " + tries + " kegagalan berturut-turut")
                if (t3 - t1 > 3000L) {
                    log("Baca layar lambat: " + (t3 - t1) + " ms (screenshot " + (t2 - t1) + ", OCR " + (t3 - t2) + ")")
                }
                return Scr(bmp, lines ?: emptyList())
            }
            if (svc.lastShotError.startsWith("kode 3") && rateHits < 5) {
                rateHits++
                if (minGap < 420L) {
                    minGap = 420L
                    log("Jeda screenshot dinaikkan ke 420 ms (sistem menolak interval pendek)")
                }
                sleepMs(120)
                continue
            }
            tries++
            if (tries == 3) {
                log("Screenshot gagal 3 kali berturut-turut (alasan terakhir: " + svc.lastShotError + ")")
                if (blindX >= 0f && !blindDone && lastW > 0) {
                    blindDone = true
                    tapXY(lastW * blindX, lastH * blindY, blindWhy)
                }
            }
            if (tries == 6 && !recovered) {
                recovered = true
                log("Percobaan pemulihan: membawa VG Klaim ke depan sebentar, lalu membuka game lagi")
                try {
                    val c = app
                    if (c != null) {
                        val i = Intent(c, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        svc.startActivity(i)
                    }
                    sleepS(2.0)
                    launch()
                    sleepS(3.0)
                } catch (e: Stop) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    log("Pemulihan gagal: " + e.javaClass.simpleName)
                }
            }
            if (tries >= 12) {
                throw Stop("Screenshot terus gagal 12 kali berturut-turut. Alasan terakhir: " + svc.lastShotError + ". Lihat baris 'Screenshot GAGAL' di atas.")
            }
            sleepS(1.0)
        }
    }

    private fun resetStats() {
        capN = 0
        capShotMs = 0L
        capOcrMs = 0L
        areaN = 0
        areaShotMs = 0L
        areaOcrMs = 0L
    }

    private fun statLine(): String {
        val nf = if (capN == 0) 1 else capN
        val na = if (areaN == 0) 1 else areaN
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
        return "layar penuh rata-rata " + ((capShotMs + capOcrMs) / nf) + " ms (screenshot " + (capShotMs / nf) +
            ", OCR " + (capOcrMs / nf) + ") x " + capN +
            "; area kecil rata-rata " + ((areaShotMs + areaOcrMs) / na) + " ms (screenshot " + (areaShotMs / na) +
            ", OCR " + (areaOcrMs / na) + ") x " + areaN +
            "; memori " + used + "/" + (rt.maxMemory() / 1048576L) + " MB"
    }

    // Jeda setelah ketukan sesuai pengaturan (milidetik). 0 berarti tanpa jeda.
    private fun stepWait() {
        val ms = Prefs.stepMs.toLong().coerceIn(0L, 3000L)
        if (ms > 0L) sleepMs(ms)
    }

    // repeat=true: ketukan berulang di titik sama memang disengaja (tidak dianggap dobel)
    private fun tapXY(x: Float, y: Float, why: String, repeat: Boolean = false) {
        alive()
        val dt = now() - lastTapT
        if (!repeat && dt < 700L && why == lastTapWhy && Math.abs(x - lastTapX) < 60f && Math.abs(y - lastTapY) < 60f) {
            log("Ketukan dobel dibatalkan (" + why + ", " + dt + " ms setelah ketukan di titik sama)")
            sleepMs(150)
            return
        }
        log("Ketuk: " + why)
        val svc = ClaimService.inst
        if (svc == null) {
            log("KETUKAN GAGAL (" + why + "): aksesibilitas mati")
            return
        }
        var ok = svc.tap(x, y)
        if (!ok && svc.lastGestureFail != "timeout") {
            log("Ketukan gagal (" + why + "): " + svc.lastGestureFail + ". Mengulang sekali")
            sleepMs(300)
            ok = svc.tap(x, y)
        }
        if (!ok) log("KETUKAN GAGAL (" + why + "): " + svc.lastGestureFail)
        lastTapX = x
        lastTapY = y
        lastTapT = now()
        lastTapWhy = why
        lastActive = now()
        stepWait()
    }

    private fun tapLine(l: Line, why: String) = tapXY(l.cx, l.cy, why)

    private fun back() {
        ClaimService.inst?.back()
        lastActive = now()
        sleepMs(150)
        stepWait()
    }

    private fun doSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, why: String) {
        val svc = ClaimService.inst
        if (svc == null) {
            log("GESERAN GAGAL (" + why + "): aksesibilitas mati")
            return
        }
        var ok = svc.swipe(x1, y1, x2, y2, ms)
        if (!ok && svc.lastGestureFail != "timeout") {
            log("Geseran gagal (" + why + "): " + svc.lastGestureFail + ". Mengulang sekali")
            sleepS(0.3)
            ok = svc.swipe(x1, y1, x2, y2, ms)
        }
        if (!ok) log("GESERAN GAGAL (" + why + "): " + svc.lastGestureFail)
    }

    private fun doSwipeHold(x1: Float, y1: Float, x2: Float, y2: Float, moveMs: Long, holdMs: Long, why: String) {
        val svc = ClaimService.inst
        if (svc == null) {
            log("GESERAN GAGAL (" + why + "): aksesibilitas mati")
            return
        }
        var ok = svc.swipeHold(x1, y1, x2, y2, moveMs, holdMs)
        if (!ok && svc.lastGestureFail != "timeout") {
            log("Geseran gagal (" + why + "): " + svc.lastGestureFail + ". Mengulang sekali")
            sleepMs(300)
            ok = svc.swipeHold(x1, y1, x2, y2, moveMs, holdMs)
        }
        if (!ok) log("GESERAN GAGAL (" + why + "): " + svc.lastGestureFail)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        doSwipe(x1, y1, x2, y2, 380, "geser menu")
        sleepMs(450)
    }

    private fun isLobby(s: Scr): Boolean {
        val keys = Prefs.lobbyKeys.split(",").map { Ocr.norm(it) }.filter { it.isNotEmpty() }
        val n = keys.count { s.all.contains(it) }
        return n >= Prefs.lobbyMin
    }

    private fun eventOpen(s: Scr): Boolean =
        s.has("vanguardcoin", "coinsupply", "pembaruan", "pembaharuan", "masukanadvance")

    // Nama server yang sah: hanya nomor dalam rentang Pengaturan (awalnya s57092 sampai s57115).
    // OCR yang kehilangan satu digit (misalnya s5094) diperbaiki kalau hanya ada SATU nomor dalam rentang yang cocok.
    private fun fixSrv(raw: String): String? {
        val lo = Prefs.serverMin
        val hi = Prefs.serverMax
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val len = lo.toString().length
        if (digits.length == len) {
            val n = digits.toIntOrNull() ?: return null
            return if (n in lo..hi) "s" + digits else null
        }
        if (digits.length == len - 1) {
            val cands = LinkedHashSet<Int>()
            for (i in 0..digits.length) {
                for (d in 0..9) {
                    val v = (digits.substring(0, i) + d + digits.substring(i)).toIntOrNull() ?: continue
                    if (v in lo..hi) cands.add(v)
                }
            }
            if (cands.size == 1) return "s" + cands.first()
        }
        return null
    }

    private fun serverNums(s: Scr): LinkedHashMap<String, Line> {
        // Baris di atas judul "Semua Server" (bagian "Server Saat Ini") tidak ikut dihitung
        val hdr = s.find("semuaserver")
        val minY: Float = hdr?.cy ?: -1f
        val sorted = s.lines
            .filter { it.cy > minY && it.cy > s.h * 0.17f && it.cy < s.h * 0.70f && SRV.containsMatchIn(it.norm) }
            .sortedWith(compareBy({ (it.cy / 40f).toInt() }, { it.cx }))
        val m = LinkedHashMap<String, Line>()
        for (l in sorted) {
            val raw = SRV.find(l.norm)?.value ?: continue
            val k = fixSrv(raw) ?: continue
            if (!m.containsKey(k)) m[k] = l
        }
        return m
    }

    private fun isServerList(s: Scr): Boolean =
        s.has("serversaatini") || serverNums(s).size >= 3

    private fun readCurrent(s: Scr): String? {
        val h = s.find("serversaatini")
        if (h != null) {
            val h2 = s.find("semuaserver")
            val maxY: Float = h2?.cy ?: (h.cy + s.h * 0.15f)
            val c = s.lines
                .filter { it.cy > h.cy && it.cy < maxY }
                .sortedBy { it.cy }
                .mapNotNull { l -> SRV.find(l.norm)?.value?.let { v -> fixSrv(v) } }
                .firstOrNull()
            if (c != null) return c
        }
        val m = Regex("serversaatini(?:[0-9]{4,6})?(s[0-9]{4,6})").find(s.all)
        return m?.groupValues?.get(1)?.let { v -> fixSrv(v) }
    }

    private fun setAccount(id: String, from: String) {
        if (id == acct) return
        acct = id
        done.clear()
        done.addAll(Prefs.doneToday(acct))
        refreshCount()
        log("Akun terbaca dari " + from + ": ID " + acct + " (server selesai hari ini: " + done.size + ")")
    }

    // Layar Profil menulis "ID: 2281758175 (57102)" -> (ID akun, kode server saat ini)
    private fun parseProfile(s: Scr): Pair<String, String>? = parseProfileLines(s.lines)

    private fun parseProfileLines(lines: List<Line>): Pair<String, String>? {
        val re = Regex("([0-9]{6,12})\\s*\\(\\s*([0-9]{4,6})\\s*\\)")
        for (l in lines) {
            val m = re.find(l.text) ?: continue
            return Pair(m.groupValues[1], "s" + m.groupValues[2])
        }
        return null
    }

    private fun readProfileInfo(s: Scr) {
        val lo = parseProfile(s)
        val hiLines = Ocr.read(s.bmp, rect(s.bmp, R_ID), 100)
        val hi = if (hiLines != null) parseProfileLines(hiLines) else null
        if (hi != null && lo != null && hi != lo) {
            log("Baca Profil: resolusi penuh " + hi.first + " (" + hi.second + ") beda dengan baca cepat " + lo.first + " (" + lo.second + "). Memakai resolusi penuh")
        }
        val p = hi ?: lo ?: return
        setAccount(p.first, "Profil")
        val lc = lastClaimed
        if (lc != null && lc != p.second) {
            log("Peringatan: server di Profil " + p.second + " tidak sama dengan yang baru diklaim (" + lc + "). Pergantian server mungkin gagal, " + lc + " dicatat belum selesai.")
            done.remove(lc)
            Prefs.removeDone(lc, acct)
            refreshCount()
        }
        lastClaimed = null
        cur = p.second
    }

    // Role di layar Memuat Game = ID akun (sama dengan ID di Profil)
    private fun parseRole(lines: List<Line>): String? {
        val re = Regex("(?i)role\\D{0,4}([0-9]{6,12})")
        for (l in lines) {
            val m = re.find(l.text) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    private fun readRole(s: Scr): Boolean {
        val l = Ocr.read(s.bmp, rect(s.bmp, R_ROLE), 100) ?: return false
        val id = parseRole(l) ?: return false
        setAccount(id, "layar Memuat Game (Role)")
        return true
    }

    // "Account ID: ..." di layar Pengaturan Akun, dibaca resolusi penuh di strip tipis.
    // Kalau ID sudah diketahui dari layar loading, hanya dicocokkan (beda = peringatan di log).
    private fun readAccountId(s: Scr) {
        val hi = Ocr.read(s.bmp, rect(s.bmp, R_ACCID), 100)
        val txt = if (hi != null) hi.joinToString("") { it.norm } else ""
        val re = Regex("accountid([0-9]{6,12})")
        val m = re.find(txt) ?: re.find(s.all) ?: return
        val id = m.groupValues[1]
        if (acct == "-") {
            setAccount(id, "Pengaturan Akun")
        } else if (acct != id) {
            log("Peringatan: ID dari layar loading (" + acct + ") beda dengan Account ID di Pengaturan Akun (" + id + ")")
        }
    }

    // Layar Pengaturan Akun: ada tombol "Pusat Akun"/"Ganti Akun", atau judul "PENGATURAN AKUN" di bagian atas.
    // (Di layar Profil, "Pengaturan Akun" hanyalah tab di sisi kiri bawah.)
    private fun isAccount(s: Scr): Boolean {
        if (s.has("pusatakun", "gantiakun")) return true
        return s.lines.any { it.norm.contains("pengaturanakun") && it.cy < s.h * 0.12f }
    }

    // Tab menuju Pengaturan Akun di layar Profil (namanya "Akun" di versi lama, "Pengaturan Akun" di versi baru)
    private fun akunTab(a: Scr): Pair<Float, Float> {
        val l = a.lines.firstOrNull { it.norm.contains("pengaturanakun") && it.cx < a.w * 0.25f }
            ?: a.lines.firstOrNull { it.norm == "pengaturan" && it.cx < a.w * 0.25f && it.cy > a.h * 0.5f }
            ?: a.lines.firstOrNull { it.norm == "akun" && it.cx < a.w * 0.25f && it.cy > a.h * 0.5f }
        return if (l != null) Pair(l.cx, l.cy) else Pair(a.w * 0.105f, a.h * 0.745f)
    }

    // Popup "Fitur ... Telah Hadir!" di tengah layar: tidak punya tombol X, hanya bisa ditutup dengan Back.
    // Tulisan kecil di bagian atas layar ("Fitur Riwayat Like telah hadir.") tidak dihitung.
    private fun featurePopup(s: Scr): Boolean =
        s.lines.any { it.norm.contains("telahhadir") && it.cy > s.h * 0.15f && it.cy < s.h * 0.55f }

    private fun isProfile(s: Scr): Boolean =
        s.has("youthcare", "kartunama") || (s.has("album") && s.has("riwayat"))

    // Panah kembali di pojok kiri atas (persen layar, dari screenshot)
    private fun tapArrow(s: Scr) {
        val x = if (isServerList(s)) 0.16f else 0.097f
        tapXY(s.w * x, s.h * 0.045f, "panah kembali")
    }

    // Ketuk template X, tapi berhenti kalau X yang sama diketuk berulang tanpa hasil
    private fun tapX(s: Scr): Boolean {
        if (now() < xBlockUntil) return false
        val x = Matcher.findPrefix(s, "x") ?: return false
        if (Math.abs(x.x - lastXx) < 40f && Math.abs(x.y - lastXy) < 40f) xRepeat++
        else {
            xRepeat = 0
            lastXx = x.x
            lastXy = x.y
        }
        if (xRepeat >= 2) {
            xBlockUntil = now() + 60000L
            xRepeat = 0
            log("Template X cocok di titik yang sama berulang kali tanpa menutup apa pun. X diabaikan 60 detik (template X mungkin terlalu longgar, potong ulang lebih rapat).")
            return false
        }
        tapXY(x.x, x.y, "tutup X")
        return true
    }

    // true kalau sudah ada popup yang muncul (dan ditangani) sejak mulai menunggu lobby.
    // Kalau sudah ada, waktu tunggu lobby tidak dipakai lagi (popupnya sudah keluar sebelum lobby).
    @Volatile private var popupSeen = false

    private fun popups(s: Scr): Boolean {
        val r = popupsRaw(s)
        if (r) popupSeen = true
        return r
    }

    private fun popupsRaw(s: Scr): Boolean {
        if (s.has("bonuslogin") && !eventOpen(s) && !isLobby(s)) {
            log("Layar Bonus Login 9 Hari muncul, ditutup tanpa klaim")
            if (flip++ % 2 == 0) tapArrow(s) else back()
            return true
        }
        val tu = s.find("hadiahtopup")
        if (tu != null && tu.cy < s.h * 0.18f) {
            if (now() < topupBlockUntil) return false
            if (topupTaps >= 3) {
                topupBlockUntil = now() + 60_000L
                topupTaps = 0
                log("Popup Hadiah Top Up Pertama tidak menutup setelah 3 ketukan X. Diabaikan 60 detik (cek posisi X: 85,6% / 7,4%)")
                return false
            }
            topupTaps++
            tapXY(s.w * 0.856f, s.h * 0.09f, "tutup X (Hadiah Top Up Pertama; tidak mengetuk Top Up Sekarang)")
            return true
        }
        topupTaps = 0
        if (tapX(s)) return true
        for (k in listOf("ketukuntukmelanjutkan", "ketukuntuk")) {
            val l = s.find(k)
            if (l != null) {
                tapLine(l, "lanjut")
                return true
            }
        }
        for (k in listOf("konfirmasi", "ok", "berikutnya", "lewati", "lanjut")) {
            val l = s.exact(k)
            if (l != null) {
                tapLine(l, k)
                return true
            }
        }
        if (s.has("hadiahselamatdatang", "datanguntukmu")) {
            val l = s.exact("klaim")
            if (l != null) {
                tapLine(l, "Klaim hadiah selamat datang")
                return true
            }
        }
        return false
    }

    private fun awaitLobby(maxSec: Int, needLoad: Boolean = false): Scr {
        popupSeen = false
        val t0 = now()
        var okCount = 0
        var relaunched = 0
        var quietLogged = false
        var loadLogged = false
        var loadSeen = false
        var roleTries = 0
        val quietMs = 15_000L
        while (now() - t0 < maxSec * 1000L) {
            // Baca hanya strip bawah layar: cukup untuk mengenali "Memuat Game" dan menu lobby
            var s = cap(R_BOTTOM)
            lastActive = now()
            val elapsed = now() - t0
            if (elapsed > (relaunched + 1) * 100_000L && relaunched < 2) {
                relaunched++
                log("Lobby belum muncul, membuka game lagi (" + relaunched + "/2)")
                launch()
                sleepS(5.0)
                continue
            }
            // Layar loading ("Memuat Game..." + tulisan persyaratan di bawahnya): jangan diketuk
            if (s.has("memuatgame", "sayatelahmembaca")) {
                okCount = 0
                loadSeen = true
                if (acct == "-" && roleTries < 4) {
                    roleTries++
                    readRole(s)
                }
                if (!loadLogged) {
                    log("Game sedang memuat (tidak mengetuk apa pun)")
                    loadLogged = true
                }
                sleepMs(400)
                continue
            }
            // Setelah ganti server: lobby lama bisa terlihat sebentar sebelum game restart
            val tooEarly = needLoad && !loadSeen && elapsed < 25_000L
            if (isLobby(s) && !tooEarly) {
                okCount++
                if (okCount >= 2) {
                    log("Lobby terdeteksi")
                    return s
                }
                sleepMs(250)
                continue
            }
            okCount = 0
            if (elapsed < quietMs || tooEarly) {
                // Game masih memuat: jangan mengetuk apa pun
                if (!quietLogged) {
                    log("Game sedang loading, menunggu (tidak mengetuk apa pun)")
                    quietLogged = true
                }
                sleepMs(400)
                continue
            }
            // Strip bawah tidak cukup mengenali layar: baca layar penuh
            s = cap()
            if (isLobby(s) && !eventOpen(s) && !isServerList(s)) {
                okCount++
                if (okCount >= 2) {
                    log("Lobby terdeteksi")
                    return s
                }
                sleepMs(250)
                continue
            }
            if (!popups(s)) {
                log("Menunggu layar dikenali...")
                sleepMs(500)
            }
        }
        throw Stop("Lobby tidak terdeteksi (jalankan Diagnosa di tab Log dan kirim lognya)")
    }

    private fun lum(c: Int): Int {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        return (r * 30 + g * 59 + b * 11) / 100
    }

    private fun maxLum(bmp: Bitmap, r: Rect): Int {
        var m = 0
        val x0 = r.left.coerceIn(0, bmp.width - 1)
        val x1 = r.right.coerceIn(x0 + 1, bmp.width)
        val y0 = r.top.coerceIn(0, bmp.height - 1)
        val y1 = r.bottom.coerceIn(y0 + 1, bmp.height)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val l = lum(bmp.getPixel(x, y))
                if (l > m) m = l
                x += 2
            }
            y += 2
        }
        return m
    }

    // Popup apa pun menggelapkan lobby di belakangnya. Kalau tulisan "Event" di lobby tampak redup,
    // ketuk X di pojok kanan atas (x 85,6%, y 9%, pas untuk popup Top Up Pertama dan Bintang Keberuntungan).
    private fun darkPopupX(s: Scr): Boolean {
        if (!isLobby(s)) return false
        val ev = s.exact("event") ?: return false
        val m = maxLum(s.bmp, ev.r)
        if (m >= 175) {
            darkTaps = 0
            return false
        }
        if (now() < topupBlockUntil) return false
        // Maksimal 3 ketukan X (tanpa jeda tambahan, tanpa dibatalkan penjaga ketukan dobel). Kalau popup masih ada,
        // bot BERHENTI dengan pesan supaya Anda yang memeriksa layarnya.
        if (darkTaps >= 3) {
            darkTaps = 0
            val msg = "Popup di lobby tidak bisa ditutup setelah 3 ketukan X. Periksa layar, lalu mulai lagi"
            log(msg)
            throw Stop(msg)
        }
        darkTaps++
        val promo = s.has("bintangkeberuntungan") || s.has("penawarankhusus")
        log("Lobby tampak gelap (kecerahan teks Event " + m + ")" + (if (promo) ", popup promo layar penuh" else ", kemungkinan ada popup") + ", ketukan X " + darkTaps + "/3")
        tapXY(s.w * 0.856f, s.h * 0.09f, "tutup X (popup)", true)
        popupSeen = true
        return true
    }

    // Waktu tunggu lobby (milidetik) HANYA dipakai kalau lobby langsung muncul setelah loading tanpa popup apa pun.
    // Kalau popup (misalnya Bonus Login 9 Hari) sudah muncul dan ditutup sebelum lobby, tidak menunggu lagi:
    // cukup tutup popup yang masih terlihat, lalu lanjut.
    private fun settleLobby() {
        if (popupSeen) {
            log("Popup sudah muncul sebelum lobby, waktu tunggu lobby dilewati")
            closeRemainingPopups()
            return
        }
        val calm = Prefs.lobbyWait.toLong().coerceIn(0L, 30_000L)
        log("Lobby langsung muncul tanpa popup, menunggu paling lama " + calm + " ms untuk popup")
        val t0 = now()
        while (now() - t0 < calm) {
            val s = cap()
            if (popups(s) || darkPopupX(s)) {
                // Popup pertama sudah muncul dan ditutup: tidak menunggu lagi, hanya periksa popup susulan
                log("Popup muncul dan ditutup, waktu tunggu lobby selesai")
                closeRemainingPopups()
                return
            }
        }
    }

    // Tutup popup susulan yang langsung muncul setelah popup sebelumnya. Selesai kalau satu pemeriksaan bersih.
    private fun closeRemainingPopups() {
        var guard = 0
        while (guard < 4) {
            guard++
            val s = cap()
            if (!(popups(s) || darkPopupX(s))) return
            sleepMs(300)
        }
    }

    private fun backToLobby() {
        for (i in 0 until 12) {
            val s = cap()
            if (isLobby(s) && !eventOpen(s) && !isServerList(s)) {
                if (darkPopupX(s)) continue
                return
            }
            if (!isServerList(s) && tapX(s)) {
                // X sudah diketuk
            } else if (i % 2 == 0 && (eventOpen(s) || isAccount(s) || isProfile(s) || isServerList(s))) {
                tapArrow(s)
            } else {
                back()
            }
            sleepMs(100)
        }
        throw Stop("Gagal kembali ke lobby")
    }

    private fun findGame(ctx: Context): String? {
        val saved = Prefs.pkg
        if (saved.isNotEmpty()) return saved
        var best: String? = null
        var bestScore = 0
        for ((pkg, label) in listApps(ctx, true)) {
            val l = label.lowercase()
            var sc = 0
            if (pkg == "com.mobile.legends") sc += 100
            if (l.contains("mobile legends")) sc += 50
            if (l.contains("mlbb")) sc += 40
            if (l.contains("advance")) sc += 20
            if (sc > bestScore) {
                bestScore = sc
                best = pkg
            }
        }
        return best
    }

    fun listApps(ctx: Context, all: Boolean): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val res = pm.queryIntentActivities(i, 0)
        val out = ArrayList<Pair<String, String>>()
        for (r in res) {
            val pkg = r.activityInfo.packageName
            val label = r.loadLabel(pm).toString()
            if (pkg == ctx.packageName) continue
            out.add(Pair(pkg, label))
        }
        out.sortBy { it.second.lowercase() }
        if (all) return out
        val f = out.filter {
            val l = it.second.lowercase()
            l.contains("legends") || l.contains("mlbb") || it.first.contains("legends") || it.first.contains("moonton")
        }
        return if (f.isEmpty()) out else f
    }

    private fun launch() {
        val ctx = app ?: return
        val i = ctx.packageManager.getLaunchIntentForPackage(gamePkg)
            ?: throw Stop("Tidak bisa membuka " + gamePkg)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val svc = ClaimService.inst
        if (svc != null) svc.startActivity(i) else ctx.startActivity(i)
        lastActive = now()
    }


    // Geser pelan supaya daftar tidak "meluncur" dan melewatkan baris
    private fun slowSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        doSwipe(x1, y1, x2, y2, 700, "geser daftar server")
        sleepS(0.8)
    }

    private class Step(val scr: Scr, val moved: Boolean, val lost: Boolean)

    private var scrollNo = 0

    // Tinggi satu baris server (piksel), dari jarak antar baris yang terbaca
    private fun rowH(s: Scr): Float {
        val ys = serverNums(s).values.map { it.cy }.sorted()
        if (ys.size < 2) return s.h * 0.08f
        val d = ArrayList<Float>()
        for (i in 1 until ys.size) d.add(ys[i] - ys[i - 1])
        d.sort()
        return d[d.size / 2].coerceIn(s.h * 0.05f, s.h * 0.12f)
    }

    private fun rangeText(m: Map<String, Line>): String {
        val k = m.keys.toList()
        return if (k.isEmpty()) "kosong" else if (k.size == 1) k[0] else k[0] + " sampai " + k[k.size - 1] + " (" + k.size + " baris)"
    }

    private var styleIdx = 0

    // Gaya geser. xf = posisi x (pecahan lebar layar). Gaya pertama meniru gerakan jari pada rekaman:
    // di sisi kanan daftar (x sekitar 78%), cepat (sekitar 0,2 detik), naik sekitar 20% tinggi layar.
    private class Sty(val ms: Long, val rows: Float, val name: String, val xf: Float, val hold: Long = 0L, val hard: Boolean = false, val y0f: Float = -1f, val y1f: Float = -1f)

    // Protokol dari video contoh: (1) geseran pelan menggeser daftar 0,6 baris sampai baris ke-5 penuh;
    // (2) geseran KERAS (sekitar 6 baris dalam 0,18 detik, jari ditahan di ujung) memuat baris ke-6 dst;
    // (3) sesudah itu geseran pelan sudah cukup sampai baris terakhir.
    // Geser cepat meniru auto clicker yang terbukti berhasil memuat baris baru di game ini:
    // satu garis lurus di x 77%, dari y 53% ke y 8% layar, 200 ms, tanpa tahan.
    // true setelah daftar mentok: dipakai oleh alur lama (nilainya tidak lagi menentukan apa pun)
    private var hardNext = false

    // Geser cepat dari Pengaturan (hanya manual)
    private fun fastStyle(): Sty {
        val xf = Prefs.swipeX / 100f
        val y0 = Prefs.swipeStart / 100f
        val y1 = minOf(Prefs.swipeEnd / 100f, y0 - 0.1f)
        return Sty(
            Prefs.swipeHardMs.toLong(), 0f,
            "geser cepat (" + Prefs.swipeStart + "% ke " + Prefs.swipeEnd + "%, " + Prefs.swipeHardMs + "ms)",
            xf, 0L, true, y0, y1
        )
    }

    // Geser daftar ke ATAS (pelan) dan buktikan daftarnya bergerak. moved=false = sudah di paling atas.
    // Geser ke bawah memakai scrollNext.
    private fun scrollStep(s: Scr, forward: Boolean, tag: String, maxTry: Int = 3): Step {
        val before = serverNums(s)
        val rh = rowH(s)
        var cur2 = s
        for (attempt in 0 until maxTry) {
            val y0 = s.h * 0.38f
            val d = minOf(3.0f * rh, s.h * 0.90f - y0)
            doSwipe(s.w * 0.77f, y0, s.w * 0.79f, y0 + d, 300, "geser daftar ke atas")
            sleepMs(450)
            scrollNo++
            val after = serverNums(cap(R_SERVER).also { cur2 = it })
            if (after.isEmpty() || before.isEmpty()) continue
            val c = after.keys.firstOrNull { before.containsKey(it) }
            val grew = after.keys.any { !before.containsKey(it) }
            val shift = if (c != null) Math.abs(before[c]!!.cy - after[c]!!.cy) else 9999f
            if (c == null || grew || shift >= rh * 0.4f) {
                log("Geseran " + scrollNo + " (" + tag + "): ke atas, terlihat " + rangeText(after))
                return Step(cur2, true, false)
            }
        }
        return Step(cur2, false, false)
    }

    // ===== Geser maju untuk mencari server =====
    // Daftar baru hanya berisi 5 baris. Geseran pertama = geser cepat manual (Pengaturan) yang memuat server baru.
    // Sesudahnya HANYA geser pelan. Geser cepat tidak pernah dipakai lagi pada daftar yang sama.
    // Tiap layar: maksimal 2 server baru dan minimal 1 server lama (patokan). Server baru = server yang tidak
    // ada di layar sebelum geseran. Selesai kalau semua server (Jumlah server dikurangi satu) sudah terlihat.
    // tanpa server baru (daftar tidak pernah mentok, hanya bergoyang di ujung).
    private val listSeen = LinkedHashSet<String>()
    private var listPulled = false
    private var listReopened = false
    private var pullFails = 0
    private var slowRows = 3.0f
    private var idleSlow = 0
    private var stalled = 0
    private var idleSince = 0L
    private var lastSig = ""
    private var expectOverride = -1
    private var firstPullSince = 0L
    private var listOpenedAt = 0L
    // Urutan baris yang pernah terlihat di daftar (atas ke bawah). Dipakai untuk tahu apakah layar sudah di baris terdalam.
    private val rowOrder = ArrayList<String>()

    private fun mergeRows(keys: List<String>) {
        for (i in keys.indices) {
            val k = keys[i]
            if (k == cur || rowOrder.contains(k)) continue
            var at = -1
            var j = i - 1
            while (j >= 0 && at < 0) {
                val idx = rowOrder.indexOf(keys[j])
                if (idx >= 0) at = idx + 1
                j--
            }
            j = i + 1
            while (j < keys.size && at < 0) {
                val idx = rowOrder.indexOf(keys[j])
                if (idx >= 0) at = idx
                j++
            }
            if (at < 0) rowOrder.add(k) else rowOrder.add(at, k)
        }
    }

    // Berapa baris daftar bergerak antara dua bacaan (dibandingkan dari satu baris yang sama)
    private fun movedText(before: Map<String, Line>, after: Map<String, Line>, rh: Float): String {
        val k = after.keys.firstOrNull { before.containsKey(it) } ?: return "melompat (tidak ada baris yang sama)"
        val d = (before[k]!!.cy - after[k]!!.cy) / rh
        if (Math.abs(d) < 0.15f) return "tidak bergerak"
        return "bergerak " + String.format("%.1f", Math.abs(d)) + " baris " + (if (d > 0) "ke atas" else "ke bawah")
    }

    // Baris terbawah di layar sama dengan baris terdalam yang pernah terlihat
    private fun atFrontier(rows: Map<String, Line>): Boolean {
        val bottom = rows.keys.lastOrNull() ?: return false
        return rowOrder.isNotEmpty() && rowOrder.last() == bottom
    }

    private fun expectRows(): Int =
        if (expectOverride >= 0) expectOverride else (Prefs.serverCount - 1).coerceAtLeast(0)

    // Panggil tiap kali Daftar Karakter baru dibuka (posisi paling atas)
    private fun beginList(s: Scr, resetReopen: Boolean = true) {
        listSeen.clear()
        listPulled = false
        pullFails = 0
        slowRows = 3.0f
        idleSlow = 0
        stalled = 0
        idleSince = 0L
        lastSig = ""
        rowOrder.clear()
        listOpenedAt = now()
        if (resetReopen) {
            listReopened = false
            firstPullSince = now()
        }
        for (k in serverNums(s).keys) if (k != cur) listSeen.add(k)
        mergeRows(serverNums(s).keys.toList())
    }

    // Satu geser cepat keras. Mengembalikan layar sesudahnya (null kalau daftar tidak terbaca).
    private fun pullOnce(s: Scr, tag: String): Scr? {
        val st = fastStyle()
        doSwipe(s.w * st.xf, s.h * st.y0f, s.w * st.xf, s.h * st.y1f, st.ms, "geser cepat (" + st.name + ")")
        sleepMs(700)
        scrollNo++
        var r: Scr = s
        var after = serverNums(cap(R_SERVER).also { r = it })
        if (after.isEmpty()) after = serverNums(cap(R_SERVER).also { r = it })
        if (after.isEmpty()) {
            log("Geseran " + scrollNo + " (" + tag + "): daftar tidak terbaca setelah geser cepat")
            return null
        }
        return r
    }

    // Pastikan layar berisi minimal 1 server lama (patokan). Kalau belum ada, geser balik sampai 1 server lama terlihat.
    // Jumlah server baru di layar tidak dibatasi.
    private fun settleWindow(start: Scr, prev: Map<String, Line>, tag: String): Step {
        var scr = start
        var rows: Map<String, Line> = serverNums(scr)
        val rh = rowH(scr)
        var steps = 0
        while (steps <= 5) {
            for (k in rows.keys) if (k != cur) listSeen.add(k)
            mergeRows(rows.keys.toList())
            val newCount = rows.keys.count { !prev.containsKey(it) && it != cur }
            val oldCount = rows.keys.count { prev.containsKey(it) }
            if (oldCount >= 1 || prev.isEmpty()) {
                log("Geseran " + scrollNo + " (" + tag + "): layar " + rangeText(rows) + ", " + newCount + " server baru, " + oldCount + " lama" +
                    (if (steps > 0) " (setelah geser balik " + steps + "x)" else ""))
                return Step(scr, true, false)
            }
            if (steps == 5) break
            val bx = scr.w * Prefs.swipeX / 100f
            val ys = scr.h * Prefs.swipeBackStart / 100f
            val ye = scr.h * Prefs.swipeBackEnd / 100f
            doSwipe(bx, ys, bx + scr.w * 0.01f, ye, Prefs.swipeBackMs.toLong(), "geser balik (" + Prefs.swipeBackStart + "% ke " + Prefs.swipeBackEnd + "%)")
            sleepMs(500)
            scrollNo++
            val r2 = serverNums(cap(R_SERVER).also { scr = it })
            if (r2.isNotEmpty()) rows = r2
            steps++
        }
        log("Geseran " + scrollNo + " (" + tag + "): batas geser balik tercapai, layar " + rangeText(rows) + " (mungkin ada baris terlewat)")
        return Step(scr, true, false)
    }

    private fun scrollNext(s: Scr, tag: String): Step {
        val expect = expectRows()
        if (expect > 0 && listSeen.size >= expect) {
            log("Geseran (" + tag + "): semua " + expect + " server di daftar sudah terlihat")
            return Step(s, false, false)
        }
        val rh = rowH(s)
        val prev = serverNums(s)
        for (k in prev.keys) if (k != cur) listSeen.add(k)
        mergeRows(prev.keys.toList())
        var scr = s

        if (!listPulled) {
            // Geser cepat hanya SEKALI per daftar yang dibuka, apa pun hasilnya. Sesudah itu hanya geser pelan.
            val seenBefore = listSeen.size
            val since = now() - listOpenedAt
            val r = pullOnce(s, tag)
            listPulled = true
            pullFails = 0
            if (r == null) {
                log("Geseran " + scrollNo + " (" + tag + "): daftar tidak terbaca setelah geser cepat, lanjut geser pelan")
                return Step(s, true, false)
            }
            scr = r
            val after = serverNums(r)
            val extra = after.keys.count { !listSeen.contains(it) && it != cur }
            for (k in after.keys) if (k != cur) listSeen.add(k)
            mergeRows(after.keys.toList())
            val mv = movedText(prev, after, rh)
            log("Geseran " + scrollNo + " (" + tag + "): geser cepat sekali, dimulai " + since + " ms sejak daftar terbaca, " + mv + ", terlihat " + rangeText(after) +
                (if (seenBefore + extra > 5) " (memuat server baru)" else " (belum ada server baru)"))
            return settleWindow(r, prev, tag)
        }

        // Sesudah geser cepat pertama: geser pelan dulu
        val x0 = s.w * Prefs.swipeX / 100f
        val y0 = s.h * Prefs.swipeSlowStart / 100f
        val y1 = s.h * Prefs.swipeSlowEnd / 100f
        doSwipe(x0, y0, x0 + s.w * 0.02f, y1, Prefs.swipeSlowMs.toLong(), "geser pelan (" + Prefs.swipeSlowStart + "% ke " + Prefs.swipeSlowEnd + "%)")
        sleepMs(450)
        scrollNo++
        var after = serverNums(cap(R_SERVER).also { scr = it })
        if (after.isEmpty()) after = serverNums(cap(R_SERVER).also { scr = it })
        if (after.isEmpty()) {
            log("Geseran " + scrollNo + " (" + tag + "): daftar tidak terbaca setelah geser pelan")
            return Step(scr, false, true)
        }
        val globalNew = after.keys.count { !listSeen.contains(it) && it != cur }
        if (globalNew > 0) {
            idleSlow = 0
            stalled = 0
            idleSince = 0L
            val newVsPrev = after.keys.count { !prev.containsKey(it) && it != cur }
            val res = settleWindow(scr, prev, tag)
            slowRows = if (newVsPrev > 2) maxOf(2f, slowRows * 2f / newVsPrev) else 3.0f
            return res
        }

        // Tidak ada server baru. Geser cepat TIDAK dipakai lagi (hanya sekali di awal daftar).
        // Ujung daftar dikenali dari Jumlah server yang diisi (semua server sudah terlihat), bukan dari layar yang diam.
        // Kalau belum semua terlihat, bot TIDAK berhenti: terus geser pelan (dengan sedikit jeda tambahan supaya daftar sempat memuat).
        // Satu-satunya pengaman: 10 menit tanpa server baru, berhenti dengan pesan supaya tidak menggeser selamanya.
        for (k in after.keys) if (k != cur) listSeen.add(k)
        mergeRows(after.keys.toList())
        idleSlow++
        if (idleSince == 0L) idleSince = now()
        val sig = after.keys.joinToString(",")
        if (sig == lastSig) stalled++ else stalled = 0
        lastSig = sig
        if (stalled >= 4 && listSeen.size <= 5 && now() - firstPullSince < 600_000L) {
            // Geser cepat sekali tidak memuat apa pun dan geser pelan pun diam: buka ulang daftar. Daftar baru = geser cepat sekali lagi.
            log("Geseran " + scrollNo + " (" + tag + "): daftar belum memuat server baru, membuka ulang Daftar Karakter")
            val fl = freshList()
            if (fl != null) {
                beginList(fl, false)
                return Step(fl, true, false)
            }
        }
        if (now() - idleSince > 600_000L) {
            val msg = "Daftar tidak menemukan server baru selama 10 menit, baru " + listSeen.size + " dari " + expect + " server terlihat. Periksa Jumlah server atau setelan geser pelan"
            log("Geseran " + scrollNo + " (" + tag + "): " + msg)
            if (expectOverride >= 0) return Step(scr, false, true)
            throw Stop(msg)
        }
        if (stalled >= 2) {
            log("Geseran " + scrollNo + " (" + tag + "): layar tidak berubah " + stalled + " kali, terlihat " + listSeen.size + " dari " + expect + " server. Lanjut geser pelan")
            sleepMs(800)
        } else {
            log("Geseran " + scrollNo + " (" + tag + "): melewati baris yang sudah terlihat, layar " + rangeText(after))
        }
        return Step(scr, true, false)
    }

    // Geser daftar ke paling atas (berhenti kalau daftar tidak bergerak lagi)
    private fun scrollTop(first: Scr): Scr {
        var s = first
        for (i in 0 until 25) {
            val st = scrollStep(s, false, "ke atas", 1)
            s = st.scr
            if (!st.moved) break
        }
        return s
    }

    private fun tapAvatar(s: Scr, useTemplate: Boolean) {
        if (useTemplate) {
            val av = Matcher.find(s, "avatar")
            if (av != null) {
                tapXY(av.x, av.y, "ikon profil (template avatar)")
                return
            }
        }
        val px = s.w * Prefs.avatarX
        val py = s.h * Prefs.avatarY
        tapXY(px, py, "ikon profil (posisi tetap " + px.toInt() + "," + py.toInt() + ")")
    }

    // Tunggu daftar karakter selesai dimuat (bisa beberapa detik kalau jaringan lambat)
    private fun waitServerList(sec: Double): Scr? {
        val t0 = now()
        var logged = false
        while (now() - t0 < (sec * 1000).toLong()) {
            val l = cap(R_SERVER)
            if (isServerList(l)) return l
            if (!logged) {
                log("Menunggu daftar karakter dimuat...")
                logged = true
            }
            sleepS(0.5)
        }
        return null
    }

    // Layar Pengaturan Akun (dibaca area kanan): ketuk Ganti Server, tunggu daftar karakter
    private fun accountToList(a: Scr): Scr? {
        readAccountId(a)
        val gs = a.exact("gantiserver") ?: a.find("gantiserver")
        if (gs == null) {
            log("Di layar Pengaturan Akun, tombol Ganti Server tidak terbaca (teks area kanan: " + a.all.take(120) + ")")
            sleepS(1.0)
            return null
        }
        tapLine(gs, "Ganti Server")
        val l = waitServerList(12.0)
        if (l != null) return l
        log("Daftar karakter belum terbaca 12 detik setelah Ganti Server")
        return null
    }

    // Layar Profil (dibaca area kiri): baca ID, ketuk Akun, tunggu Pengaturan Akun
    private fun profileToList(p: Scr): Scr? {
        readProfileInfo(p)
        val tab = akunTab(p)
        tapXY(tab.first, tab.second, "tab Pengaturan Akun", true)
        sleepS(1.0)
        // Kalau screenshot gagal berulang di sini, cadangan: ketuk posisi tetap Ganti Server
        setBlind(0.807f, 0.676f, "Ganti Server (posisi tetap, layar Pengaturan Akun tidak bisa dibaca)")
        val a = try {
            cap(R_AKUN)
        } finally {
            clearBlind()
        }
        if (isAccount(a)) return accountToList(a)
        log("Setelah ketuk Akun, Pengaturan Akun belum terbaca (teks: " + a.all.take(100) + ")")
        return null
    }

    // Jalur cepat dari lobby: ketuk ikon profil, ketuk Akun (posisi tetap) 3x, baca layar Pengaturan Akun
    // sekali, lalu Ganti Server. Layar Profil tidak dibaca. null = pakai jalur biasa.
    private fun fastToList(s: Scr): Scr? {
        tapAvatar(s, false)
        val t0 = now()
        var lastAv = now()
        var backTaps = 0
        var tabTaps = 0
        var lastTab = 0L
        while (now() - t0 < 7000L) {
            val a = cap()
            if (isAccount(a)) return accountToList(a)
            if (isServerList(a)) return a
            if (isProfile(a)) {
                if (featurePopup(a) && backTaps < 1) {
                    backTaps++
                    log("Popup fitur baru (tanpa tombol X) ditutup dengan Back")
                    // Back langsung (tanpa jeda antar langkah), tunggu sebentar, lalu ketuk tab dengan posisi yang sudah dibaca
                    ClaimService.inst?.back()
                    lastActive = now()
                    sleepMs(300)
                    val tb = akunTab(a)
                    lastTab = now()
                    tabTaps++
                    tapXY(tb.first, tb.second, "tab Pengaturan Akun", true)
                    continue
                }
                if (now() - lastTab > 1200L) {
                    lastTab = now()
                    tabTaps++
                    if (tabTaps >= 3) log("Tab Pengaturan Akun diketuk " + tabTaps + "x tapi belum pindah. Teks layar: " + a.all.take(120))
                    val tab = akunTab(a)
                    tapXY(tab.first, tab.second, "tab Pengaturan Akun", true)
                } else {
                    sleepMs(150)
                }
            } else if (isLobby(a)) {
                if (darkPopupX(a)) continue
                if (now() - lastAv > 1500L) {
                    lastAv = now()
                    tapAvatar(a, false)
                }
            } else {
                popups(a)
            }
        }
        log("Jalur cepat: Pengaturan Akun belum terbaca dalam 7 detik")
        return null
    }

    private fun openServerList(): Scr {
        var avatarFails = 0
        var fastFails = 0
        var unknown = 0
        for (attempt in 0 until 10) {
            var s = cap()
            if (isServerList(s)) return s
            log("Buka daftar server: layar " + (if (isAccount(s)) "Pengaturan Akun" else if (isProfile(s)) "Profil" else if (isLobby(s)) "lobby" else "lain") + " [" + s.all.take(50) + "]")
            if (s.has("daftarkarakter") && !isAccount(s)) {
                val w = waitServerList(12.0)
                if (w != null) return w
                continue
            }
            if (isAccount(s)) {
                val r = accountToList(s)
                if (r != null) return r
                continue
            }
            if (isProfile(s)) {
                val r = profileToList(s)
                if (r != null) return r
                continue
            }
            if (!isLobby(s) || eventOpen(s)) {
                if (!popups(s)) {
                    unknown++
                    if (unknown >= 3) {
                        unknown = 0
                        back()
                    } else {
                        log("Layar belum dikenali, menunggu. Teks terbaca: " + s.all.take(160))
                        sleepS(1.2)
                    }
                }
                continue
            }
            // Di lobby: ketuk ikon profil (posisi tetap dulu, template avatar hanya cadangan)
            if (darkPopupX(s)) continue
            if (fastFails == 0 && avatarFails == 0) {
                val fl = fastToList(s)
                if (fl != null) return fl
                fastFails++
                log("Jalur cepat gagal, memakai jalur biasa")
                continue
            }
            tapAvatar(s, avatarFails >= 1 && Matcher.has("avatar"))
            sleepS(1.5)
            val pr = cap(R_PROFIL)
            if (isProfile(pr)) {
                val r = profileToList(pr)
                if (r != null) return r
                continue
            }
            s = cap()
            if (isServerList(s)) return s
            if (isAccount(s) || isProfile(s)) continue
            if (popups(s)) continue
            avatarFails++
            val px = s.w * Prefs.avatarX
            val py = s.h * Prefs.avatarY
            log("Setelah mengetuk ikon profil, layar BUKAN menu Profil/Akun. Titik ketuk: " +
                px.toInt() + "," + py.toInt() + " px dari layar " + s.w + "x" + s.h + ". Teks terbaca: " + s.all.take(160))
            if (avatarFails >= 2) {
                throw Stop("Ikon profil tidak membuka menu Profil/Akun. Jalankan Diagnosa di lobby dan kirim lognya.")
            }
        }
        throw Stop("Daftar server tidak terbuka (jalankan Diagnosa di menu profil)")
    }

    private fun pickNext(): String? {
        hardNext = false
        var s = openServerList()
        val rc = readCurrent(s)
        if (rc != null) {
            val lc = lastClaimed
            if (lc != null && lc != rc) {
                log("Peringatan: server saat ini di daftar (" + rc + ") tidak sama dengan yang baru diklaim (" + lc + "). Pergantian server mungkin gagal, " + lc + " dicatat belum selesai.")
                done.remove(lc)
                Prefs.removeDone(lc, acct)
                refreshCount()
            }
            lastClaimed = null
            cur = rc
        }
        // Daftar yang baru dibuka selalu di posisi atas: ambil server paling atas yang belum selesai,
        // dan baru menggeser kalau semua server yang terlihat sudah selesai.
        beginList(s)
        for (page in 0 until 3000) {
            alive()
            for ((n, _) in serverNums(s)) {
                if (!isDone(n) && n != cur) {
                    if (!order.contains(n)) order.add(n)
                    return n
                }
            }
            val st = scrollNext(s, "cari server berikutnya")
            s = st.scr
            if (!st.moved) {
                log(if (st.lost) "Peringatan: geseran gagal, daftar mungkin belum habis" else "Ujung daftar: tidak ada server yang belum selesai")
                break
            }
        }
        return null
    }

    private fun switchTo(name: String) {
        log("Ganti server -> " + name)
        status = "Ganti server " + name
        hardNext = false
        var s = cap(R_SERVER)
        if (!isServerList(s)) {
            s = openServerList()
            beginList(s)
        }
        var found: Line? = null
        for (pass in 0 until 2) {
            for (page in 0 until 60) {
                alive()
                val l = serverNums(s)[name]
                if (l != null) {
                    found = l
                    break
                }
                val st = if (pass == 0) scrollNext(s, "cari " + name) else scrollStep(s, false, "cari " + name)
                s = st.scr
                if (!st.moved) break
            }
            if (found != null) break
        }
        val l = found ?: throw Stop("Server " + name + " tidak ditemukan di daftar")
        tapLine(l, "Pilih server " + name)
        sleepMs(300)
        confirmSwitch()
        awaitLobby(200, true)
        settleLobby()
    }

    // Tombol OK: abu-abu = masih memuat (belum bisa ditekan), emas/coklat = siap. Diukur dari warna di kiri
    // dan kanan tulisan OK (merah jauh lebih tinggi dari biru pada tombol emas).
    private fun okDiff(s: Scr, b: Line): Int {
        var r = 0
        var bl = 0
        var n = 0
        val bmp = s.bmp
        for (dx in intArrayOf(-110, -90, 90, 110)) {
            for (dy in intArrayOf(-14, 0, 14)) {
                val x = (b.cx + dx).toInt()
                val y = (b.cy + dy).toInt()
                if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
                val c = bmp.getPixel(x, y)
                r += (c shr 16) and 255
                bl += c and 255
                n++
            }
        }
        return if (n == 0) 999 else (r - bl) / n
    }

    private fun confirmSwitch() {
        var gsTaps = 0
        var lastGs = 0L
        var okSeen = 0L
        var okGrayLogged = false
        val t0 = now()
        while (now() - t0 < 25_000L) {
            val s = cap(R_SERVER)
            if (s.has("memuatgame")) {
                log("Game memuat ulang")
                return
            }
            // Popup "Pemberitahuan Sistem: Server telah diganti..." -> tekan OK
            if (s.has("pemberitahuansistem", "melakukanrestart", "restartgame", "servertelahdiganti")) {
                val b = s.exact("ok") ?: s.exact("konfirmasi") ?: s.exact("ya")
                if (b != null) {
                    val diff = okDiff(s, b)
                    if (okSeen == 0L) okSeen = now()
                    if (diff <= 35 && now() - okSeen < 6000L) {
                        if (!okGrayLogged) {
                            okGrayLogged = true
                            log("Tombol OK masih abu-abu (memuat, selisih merah-biru " + diff + "), menunggu sampai emas")
                        }
                        continue
                    }
                    tapLine(b, "OK (server telah diganti, selisih merah-biru " + diff + ")")
                    // tunggu popup hilang atau game mulai restart; ulangi OK kalau popup masih terlihat
                    val t1 = now()
                    var retap = 0
                    while (now() - t1 < 4000L) {
                        val s2 = cap(R_SERVER)
                        if (!s2.has("pemberitahuansistem", "servertelahdiganti", "restartgame", "melakukanrestart")) break
                        val b2 = s2.exact("ok")
                        if (b2 != null && retap < 3 && now() - t1 > 900L * (retap + 1)) {
                            retap++
                            tapLine(b2, "OK diulang (popup masih terlihat)")
                        }
                    }
                    return
                }
            }
            // Daftar karakter: tombol emas "Ganti Server" di bawah daftar
            if (isServerList(s)) {
                val gs = s.exact("gantiserver")
                if (gs != null && gsTaps < 3 && now() - lastGs > 900L) {
                    tapLine(gs, "Ganti Server (tombol emas)")
                    gsTaps++
                    lastGs = now()
                }
            }
        }
    }

    // Tunggu halaman Hadiah terbuka (berhenti segera setelah terlihat)
    private fun waitEventOpen(sec: Double): Boolean {
        val t0 = now()
        while (now() - t0 < (sec * 1000).toLong()) {
            if (eventOpen(cap(R_EVT))) return true
        }
        return false
    }

    // Judul halaman: tulisan di pojok kiri atas
    private fun pageTitle(s: Scr): String =
        s.lines.filter { it.cy < s.h * 0.10f && it.cx < s.w * 0.5f }.sortedBy { it.cx }.joinToString("") { it.norm }

    // Setelah mengetuk sebuah tab: tunggu SABAR sampai halaman Hadiah terbuka (jaringan lambat bisa butuh
    // beberapa detik). Pindah ke tab lain hanya kalau halaman sudah jelas bukan Hadiah.
    private fun waitHadiah(before: String): Boolean {
        val t0 = now()
        var titled = false
        while (now() - t0 < 9000L) {
            val m = cap(R_EVT)
            if (eventOpen(m)) return true
            if (m.exact("hadiah") != null) {
                if (!titled) {
                    titled = true
                    log("Judul Hadiah terlihat, menunggu daftar menu dimuat...")
                }
            } else if (!titled && before.isNotEmpty() && now() - t0 > 2500L) {
                val t = pageTitle(m)
                if (t.isNotEmpty()) {
                    log("Halaman setelah ketuk tab: judul \"" + t + "\" (bukan Hadiah)")
                    return false
                }
            }
        }
        return titled
    }

    // Simpan potongan ikon petir dari layar Anda sendiri, supaya lain kali dicari lewat gambar
    private fun learnPetir(s: Scr, x: Float, y: Float) {
        try {
            val c = app ?: return
            if (Matcher.names(c).any { it.startsWith("petir") }) return
            val sz = (s.h * 0.10f).toInt()
            val x0 = (x - sz / 2f).toInt().coerceIn(0, s.w - sz)
            val y0 = (y - sz / 2f).toInt().coerceIn(0, s.h - sz)
            val b = Bitmap.createBitmap(s.bmp, x0, y0, sz, sz)
            val f = java.io.File(Matcher.dir(c), "petir_auto.png")
            java.io.FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Matcher.reload(c)
            log("Template petir_auto disimpan dari layar Anda sendiri (" + sz + " px di " + x0 + "," + y0 + "). Bisa dihapus di tab Templat")
        } catch (e: Throwable) {
            log("Gagal menyimpan template petir otomatis: " + e.javaClass.simpleName)
        }
    }

    private fun openEvent() {
        var cycles = 0
        var quietSince = 0L
        var lastTxt = ""
        val tEnd = now() + 45_000L
        while (now() < tEnd) {
            val s = cap()
            if (eventOpen(s)) return
            if (s.exact("hadiah") != null && waitHadiah("")) return
            if (popups(s)) continue
            lastTxt = s.all.take(120)
            if (!isLobby(s)) {
                // Bukan lobby dan bukan halaman Hadiah: kemungkinan layar Event tab lain
                // (mis. Festival Pemuda). Cari ikon petir (tab Hadiah) dan ketuk SATU kali.
                val before = pageTitle(s)
                val col = Rect(0, (s.h * 0.10f).toInt(), (s.w * 0.12f).toInt(), (s.h * 0.95f).toInt())
                val p = Matcher.findPrefix(s, "petir", col)
                if (p != null) {
                    tapXY(p.x, p.y, "tab Hadiah (template petir, cocok " + (p.score * 100).toInt() + "%)")
                    if (waitHadiah(before)) return
                    continue
                }
                // Halaman masih memuat (hampir tidak ada tulisan): tunggu dulu, jangan mengetuk tab
                if (before.isEmpty() && s.lines.size < 4) {
                    if (quietSince == 0L) quietSince = now()
                    if (now() - quietSince < 4000L) continue
                } else {
                    quietSince = 0L
                }
                // Tanpa template: coba posisi umum ikon petir satu per satu. Pindah ke posisi
                // berikutnya hanya kalau halaman sudah jelas bukan Hadiah.
                for (yy in listOf(PETIR_Y, 0.347f, 0.671f, 0.185f)) {
                    tapXY(s.w * PETIR_X, s.h * yy, "ikon tab kiri y=" + (yy * 100).toInt() + "% (cari tab Hadiah)")
                    if (waitHadiah(before)) {
                        learnPetir(s, s.w * PETIR_X, s.h * yy)
                        return
                    }
                }
                cycles++
                log("Halaman Hadiah belum terbuka setelah semua tab dicoba (putaran " + cycles + "). Teks layar: " + lastTxt)
                if (cycles >= 2) {
                    cycles = 0
                    tapArrow(s)
                    sleepS(1.0)
                }
                continue
            }
            if (darkPopupX(s)) continue
            val l = s.exact("event")
            if (l != null) {
                tapLine(l, "Event")
                continue
            }
            val h = Matcher.find(s, "event")
            if (h != null) {
                tapXY(h.x, h.y, "Event (gambar)")
                continue
            }
            tapXY(s.w * PETIR_X, s.h * PETIR_Y, "Event (posisi tetap)")
        }
        throw Stop("Halaman Hadiah di Event tidak terbuka. Teks layar terakhir: " + lastTxt)
    }

    private fun openMenu(keys: List<String>, panelKeys: List<String>): Scr? {
        var seen = ""
        for (i in 0 until 7) {
            val s = cap(R_MENU)
            seen = s.all
            for (k in keys) {
                val l = s.find(k)
                if (l != null) {
                    tapLine(l, "menu " + k)
                    return waitPanel(panelKeys)
                }
            }
            val x = s.w * 0.15f
            if (i < 3) swipe(x, s.h * 0.75f, x, s.h * 0.30f) else swipe(x, s.h * 0.30f, x, s.h * 0.75f)
        }
        log("Menu " + keys[0] + " tidak ketemu setelah 7 kali baca. Teks menu terakhir: " + seen.take(140))
        return null
    }

    private fun okBtn(s: Scr): Line? = s.exact("ok") ?: s.exact("konfirmasi") ?: s.exact("berikutnya")

    private fun waitOk(): Boolean {
        val t0 = now()
        while (now() - t0 < 4000L) {
            val s = cap(R_OK)
            val b = okBtn(s)
            if (b != null) {
                tapLine(b, "OK")
                lastActive = now()
                // tunggu popup hilang (tanpa tidur tetap); ulangi OK kalau masih terlihat
                var tapT = now()
                val t1 = now()
                while (now() - t1 < 2500L) {
                    val s2 = cap(R_OK)
                    val b2 = okBtn(s2) ?: break
                    if (now() - tapT > 900L) {
                        tapLine(b2, "OK diulang")
                        tapT = now()
                    }
                }
                return true
            }
            val c = s.find("ketukuntuk")
            if (c != null) {
                tapLine(c, "lanjut")
                return true
            }
        }
        return false
    }

    private fun claimLoop(label: String, maxRounds: Int, first: Scr? = null): Int {
        var claimed = 0
        var idle = 0
        var noPopup = 0
        for (round in 0 until maxRounds) {
            if (now() - serverT0 > 15 * 60_000L) throw Stop("Server ini terlalu lama (>15 menit)")
            val s = if (round == 0 && first != null) first else cap(R_PANEL)
            val l = s.exact("klaimsemua") ?: s.find("klaimsemua") ?: s.exactAll("klaim").firstOrNull()
            if (s.has("sudahdiklaim")) {
                log(label + ": sudah diklaim")
                break
            }
            if (l == null) break
            tapLine(l, "Klaim (" + label + ")")
            claimed++
            lastActive = now()
            if (waitOk()) {
                noPopup = 0
            } else {
                // tidak ada popup hadiah: kemungkinan sudah terklaim semua, berhenti
                noPopup++
                if (noPopup >= 1) break
            }
        }
        return claimed
    }

    private val menuSupply = listOf("vanguardcoinsupply", "vanguardcoin", "coinsupply")
    private val menuUpdate = listOf("hadiahpembaruan", "hadiahpembaharuan", "pembaruan", "pembaharuan")
    private val menuFeedback = listOf("masukanadvanceserver", "masukanadvance", "masukan")

    // Tulisan khas panel kanan tiap menu, untuk memastikan halaman yang benar sudah terbuka
    private val panelSupply = listOf("vanguardcoin", "selesaikan", "klaimsemua", "logindalam")
    private val panelUpdate = listOf("hadiahpembaruan", "menggapaibintang", "patchbaru", "versigame", "sudahdiklaim")
    private val panelFeedback = listOf("berikanmasukan", "menemukanbug", "pengalamanburuk", "mengklaim")

    private fun waitPanel(keys: List<String>, sec: Double = 3.0): Scr {
        val t0 = now()
        var s = cap(R_PANEL)
        while (!s.has(*keys.toTypedArray())) {
            if (now() - t0 > (sec * 1000).toLong()) {
                log("Panel menu belum terverifikasi dalam " + sec + " detik, lanjut")
                break
            }
            s = cap(R_PANEL)
        }
        return s
    }

    // Cari menu: baca layar, kalau tidak ketemu geser turun SEKALI lalu baca lagi. Tidak geser naik.
    private fun openMenuQuick(keys: List<String>, panelKeys: List<String>): Scr? {
        var seen = ""
        for (i in 0 until 2) {
            val s = cap(R_MENU)
            seen = s.all
            for (k in keys) {
                val l = s.find(k)
                if (l != null) {
                    tapLine(l, "menu " + k)
                    return waitPanel(panelKeys)
                }
            }
            if (i == 0) {
                val x = s.w * 0.15f
                swipe(x, s.h * 0.75f, x, s.h * 0.30f)
            }
        }
        log("Menu " + keys[0] + " tidak ketemu (sudah geser turun sekali). Teks menu terakhir: " + seen.take(140))
        return null
    }

    private fun countPx(s: Scr, fx0: Float, fy0: Float, fx1: Float, fy1: Float, pred: (Int, Int, Int) -> Boolean): Int {
        val x0 = (s.w * fx0).toInt()
        val y0 = (s.h * fy0).toInt()
        val rw = (s.w * fx1).toInt() - x0
        val rh = (s.h * fy1).toInt() - y0
        if (rw <= 0 || rh <= 0) return 0
        val px = IntArray(rw * rh)
        s.bmp.getPixels(px, 0, rw, x0, y0, rw, rh)
        var n = 0
        for (c in px) {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            if (pred(r, g, b)) n++
        }
        return n
    }

    // Piksel emas di bintang koin x20 (abu-abu = sudah diklaim)
    private fun coinGold(s: Scr): Int =
        countPx(s, 0.715f, 0.43f, 0.83f, 0.65f) { r, g, b -> r > 200 && g > 140 && b < 110 && r - b > 100 }

    // Titik merah kecil di pojok kanan atas koin (siap diklaim)
    private fun coinRed(s: Scr): Int =
        countPx(s, 0.80f, 0.42f, 0.84f, 0.47f) { r, g, b -> r > 215 && g < 75 && b > 40 && b < 130 }

    // Piksel abu-abu di koin (koin yang sudah diklaim berwarna abu-abu)
    private fun coinGray(s: Scr): Int =
        countPx(s, 0.715f, 0.43f, 0.83f, 0.65f) { r, g, b ->
            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            mx - mn < 35 && mx > 80 && mx < 210
        }

    // Halaman kanan "Masukan Advance Server" masih terbuka (dibaca dari area panel kanan)
    private fun panelUp(s: Scr): Boolean =
        s.has("berikanmasukan", "menemukanbug", "pengalamanburuk", "mengklaim") || s.exact("pergi") != null

    private fun claimCoin(first: Scr) {
        var s = first
        var gold = 0
        var red = 0
        var gray = 0
        var panel = false
        // Tunggu sampai halaman kanan dan koin tergambar (maksimal 3 kali baca)
        val tw = now()
        while (true) {
            panel = s.has("berikanmasukan", "menemukanbug", "pengalamanburuk")
            gold = coinGold(s)
            red = coinRed(s)
            gray = coinGray(s)
            if (panel && (gold >= GOLD_MIN || gray >= GRAY_MIN)) break
            if (now() - tw > 2500L) break
            s = cap(R_PANEL)
        }
        log("Koin x20: emas=" + gold + ", abu=" + gray + ", titik merah=" + red + (if (panel) "" else " (halaman kanan belum terbaca)"))
        var needPergi = true
        if (panel && red >= RED_MIN) {
            needPergi = false
            log("Koin x20 siap diklaim, Pergi dilewati")
        } else if (panel && gold < GOLD_MIN && gray >= GRAY_MIN) {
            log("Koin x20 abu-abu: sudah diklaim, dilewati")
            return
        } else if (!(panel && gold >= GOLD_MIN)) {
            log("Koin x20 belum terbaca jelas, memakai jalur Pergi")
        }
        if (needPergi) {
            val g = s.exact("pergi")
            if (g != null) {
                tapLine(g, "Pergi")
                // Begitu halaman Pusat Bantuan terbaca, langsung kembali
                var seen = false
                val t0 = now()
                while (now() - t0 < 5000L) {
                    val sp = cap(R_TOP)
                    if (sp.has("pusatbantuan")) {
                        seen = true
                        break
                    }
                }
                log(if (seen) "Pusat Bantuan terbaca, kembali" else "Pusat Bantuan tidak terbaca, tetap kembali")
                back()
                if (!waitEventOpen(3.0)) {
                    log("Kembali dari Pergi, membuka Event lagi")
                    openEvent()
                    openMenu(menuFeedback, panelFeedback)
                }
            } else {
                log("Tombol Pergi tidak terbaca")
            }
        }
        // Tunggu titik merah muncul (siap diklaim), lalu ketuk koin
        var ready = false
        val tr = now()
        while (true) {
            s = cap(R_PANEL)
            if (!panelUp(s)) break
            if (coinRed(s) >= RED_MIN) {
                ready = true
                break
            }
            if (now() - tr > 2000L) break
        }
        if (panelUp(s)) {
            tapXY(s.w * COIN_X, s.h * COIN_Y, if (ready) "koin x20" else "koin x20 (titik merah belum terlihat)")
            waitOk()
        }
    }

    private val panelAge = listOf("verifikasiusia", "penghubunganakun", "orangtua", "satukali")

    // Tombol "Klaim Semua": emas = siap, abu-abu = sudah diklaim. Diukur dari warna di kiri dan kanan tulisan.
    private fun btnGold(s: Scr, b: Line): Boolean {
        var r = 0
        var bl = 0
        var n = 0
        val bmp = s.bmp
        for (x in intArrayOf(b.r.left - 16, b.r.right + 16)) {
            for (dy in intArrayOf(-12, 0, 12)) {
                val y = (b.cy + dy).toInt()
                if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) continue
                val c = bmp.getPixel(x, y)
                r += (c shr 16) and 255
                bl += c and 255
                n++
            }
        }
        if (n == 0) return true
        return (r - bl) / n > 35
    }

    private fun cancelPeti() {
        val s = cap(R_OK)
        val b = s.exact("batal") ?: s.find("batal")
        if (b != null) tapLine(b, "Batal (Isi Peti)") else back()
    }

    // Klaim Verifikasi Usia (sekali per server): menu Verifikasi Usia, Klaim Semua, Gunakan sekarang,
    // geser peti ke kiri sampai kartu Vanguard Coin, tombol + lalu Konfirmasi, lalu OK. Bisa dimatikan di Pengaturan.
    private fun claimAge() {
        val mm = cap(R_MENU)
        val ml = mm.find("verifikasiusia")
        if (ml == null) {
            log("Verifikasi Usia: menu tidak ada, lanjut")
            return
        }
        tapLine(ml, "menu verifikasi usia")
        val m = waitPanel(panelAge)
        val b = m.exact("klaimsemua") ?: m.find("klaimsemua")
        if (b == null) {
            log("Verifikasi Usia: tombol Klaim Semua tidak terbaca, lanjut")
            return
        }
        if (!btnGold(m, b)) {
            log("Verifikasi Usia: sudah diklaim")
            return
        }
        tapLine(b, "Klaim Semua (Verifikasi Usia)")
        lastActive = now()

        // Ketuk "Gunakan sekarang" di popup Memperoleh Hadiah
        var used = false
        val t0 = now()
        while (now() - t0 < 5000L) {
            alive()
            val s = cap(R_OK)
            val g = s.find("gunakansekarang")
            if (g != null) {
                tapLine(g, "Gunakan sekarang")
                used = true
                break
            }
        }
        if (!used) {
            log("Verifikasi Usia: tombol Gunakan sekarang tidak muncul, menutup popup hadiah")
            waitOk()
            return
        }

        // Isi Peti: geser ke kiri sampai kartu Vanguard Coin (kotak terakhir) terlihat
        var found: Scr? = null
        var seenPeti = false
        var swipes = 0
        val t1 = now()
        while (now() - t1 < 25_000L) {
            alive()
            val s = cap()
            if (s.has("isipeti", "terpilih")) seenPeti = true
            if (seenPeti && s.has("vanguardcoin")) {
                found = s
                break
            }
            if (seenPeti && swipes < 14) {
                doSwipe(s.w * 0.85f, s.h * 0.50f, s.w * 0.15f, s.h * 0.50f, 300, "geser peti ke kiri")
                swipes++
                sleepMs(450)
            }
        }
        val f0 = found
        if (f0 == null) {
            log("Verifikasi Usia: kartu Vanguard Coin tidak ketemu setelah " + swipes + " geseran, membatalkan")
            cancelPeti()
            return
        }
        sleepMs(450)
        val sp = cap()
        val lbl = sp.find("vanguardcoin") ?: f0.find("vanguardcoin")
        if (lbl == null) {
            log("Verifikasi Usia: tulisan Vanguard Coin hilang dari layar, membatalkan")
            cancelPeti()
            return
        }
        // Tombol + ada di kanan bawah kartu: sekitar 106 px ke kanan dan 123 px ke bawah dari tulisan (layar 2400x1080)
        val px = lbl.cx + sp.w * 0.0442f
        val py = lbl.cy + sp.h * 0.1139f
        var picked = false
        for (k in 0 until 3) {
            tapXY(px, py, "tambah (+) kotak Vanguard Coin", true)
            sleepMs(350)
            val s2 = cap()
            // Berhasil kalau tulisan "Terpilih 1/1" terbaca ATAU tombol Konfirmasi sudah berwarna emas (abu-abu = belum terpilih)
            val kb2 = s2.exact("konfirmasi") ?: s2.find("konfirmasi")
            if (s2.has("terpilih11") || (kb2 != null && btnGold(s2, kb2))) {
                picked = true
                break
            }
        }
        if (!picked) {
            log("Verifikasi Usia: Vanguard Coin belum terpilih (Terpilih 1/1 tidak terbaca), membatalkan")
            cancelPeti()
            return
        }
        val sk = cap(R_OK)
        val kb = sk.exact("konfirmasi") ?: sk.find("konfirmasi")
        if (kb == null) {
            log("Verifikasi Usia: tombol Konfirmasi tidak terbaca, membatalkan")
            cancelPeti()
            return
        }
        tapLine(kb, "Konfirmasi")
        lastActive = now()
        if (waitOk()) log("Verifikasi Usia selesai (Vanguard Coin 300)") else log("Verifikasi Usia: popup hasil tidak terbaca")
    }

    private fun claimEvents() {
        openEvent()

        if (Prefs.claimAge) claimAge()

        val m1 = openMenu(menuSupply, panelSupply)
        if (m1 != null) {
            val n = claimLoop("Vanguard Coin Supply", 3, m1)
            log("Vanguard Coin Supply selesai (" + n + " ketukan klaim)")
        } else log("Menu Vanguard Coin Supply tidak ada, lanjut")

        val m2 = openMenuQuick(menuUpdate, panelUpdate)
        if (m2 != null) {
            val n = claimLoop("Hadiah Pembaruan", 3, m2)
            log("Hadiah Pembaruan selesai (" + n + " ketukan klaim)")
        } else log("Menu Hadiah Pembaruan tidak ada, lanjut")

        val m3 = openMenu(menuFeedback, panelFeedback)
        if (m3 != null) {
            claimCoin(m3)
            log("Masukan Advance Server selesai")
        } else log("Menu Masukan Advance Server tidak ada, lanjut")

        backToLobby()
    }

    private fun isDone(n: String): Boolean = done.contains(n) || skipped.contains(n)

    private fun refreshCount() {
        val all = HashSet<String>()
        all.addAll(done)
        all.addAll(skipped)
        doneCount = all.size
    }

    private fun claimServer(name: String) {
        serverT0 = now()
        resetStats()
        status = "Klaim " + name
        log("===== SERVER " + name + " =====")
        claimEvents()
        log("Waktu klaim " + name + ": " + ((now() - serverT0) / 1000) + " dtk, " + statLine())
        done.add(name)
        Prefs.addDone(name, acct)
        lastClaimed = name
        refreshCount()
        log("Server " + name + " selesai. Progres " + doneCount + "/" + total)
    }

    private fun openOwnApp() {
        val c = app ?: return
        val i = Intent(c, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val ctx: Context = ClaimService.inst ?: c
        ctx.startActivity(i)
    }

    // Pilihan "Setelah selesai": 1 = kembali ke VG Klaim, 2 = ke Home, 3 = tetap di game
    private fun finishAction() {
        val mode = Prefs.afterDone
        val svc = ClaimService.inst
        if (mode == 3) {
            log("Setelah selesai: tetap di game")
            try {
                backToLobby()
            } catch (e: Stop) {
                log("Tidak bisa kembali ke lobby: " + e.message)
            }
            return
        }
        // Langsung pindah, tanpa menutup game dan tanpa membuka Recents
        if (mode == 1) {
            log("Setelah selesai: langsung kembali ke VG Klaim")
            openOwnApp()
        } else {
            log("Setelah selesai: langsung ke Home")
            svc?.home()
        }
    }

    private fun doneText(stopped: Boolean): String {
        val n = done.size
        val t = if (total > 0) total else n
        val sk = if (skipped.isNotEmpty()) " (" + skipped.size + " dilewati)" else ""
        if (stopped) return "Berhenti: " + n + " dari " + t + " server diklaim" + sk
        if (n >= t) return "Selesai, " + n + " server telah diklaim"
        return "Selesai: " + n + " dari " + t + " server diklaim" + sk
    }

    private fun showBanner(stopped: Boolean) {
        try {
            ClaimService.inst?.banner(doneText(stopped))
        } catch (_: Throwable) {
        }
    }

    private fun main() {
        val ctx = app ?: throw Stop("Aplikasi tidak siap")
        gamePkg = findGame(ctx) ?: throw Stop("Game MLBB tidak ditemukan. Pilih game di Beranda.")
        done.clear()
        skipped.clear()
        order.clear()
        cur = null
        acct = "-"
        lastClaimed = null
        firstPending = false
        Prefs.purgeOld()
        refreshCount()

        status = "Membuka game"
        log("Mulai klaim otomatis. Game: " + gamePkg)
        launch()
        sleepS(6.0)
        awaitLobby(240)
        settleLobby()

        // 1) Klaim server tempat game berada sekarang (ID akun belum diketahui)
        status = "Klaim server awal"
        serverT0 = now()
        resetStats()
        log("===== SERVER AWAL =====")
        claimEvents()
        log("Waktu klaim server awal: " + ((now() - serverT0) / 1000) + " dtk, " + statLine())
        firstPending = true

        // 2) Profil (baca ID + server saat ini) -> Akun -> Ganti Server -> daftar
        status = "Membaca akun dan daftar server"
        val ls = openServerList()
        if (acct == "-") log("Peringatan: ID akun tidak terbaca, progres disimpan tanpa ID akun")
        val rc = readCurrent(ls)
        if (rc != null) {
            if (cur != null && cur != rc) log("Peringatan: server dari Profil (" + cur + ") beda dengan daftar karakter (" + rc + "). Memakai daftar karakter")
            cur = rc
        }
        if (firstPending) {
            firstPending = false
            val c = cur
            if (c != null) {
                done.add(c)
                Prefs.addDone(c, acct)
                log("Server awal " + c + " dicatat selesai")
            } else {
                log("Peringatan: kode server awal tidak terbaca, tidak dicatat")
            }
            refreshCount()
        }

        val want = Prefs.serverCount
        val c0 = cur
        total = want
        if (want > 0 && order.isNotEmpty() && order.size != want) {
            log("Peringatan: pengaturan " + want + " server, terbaca " + order.size + ". Dilanjutkan.")
        }
        if (Prefs.skipAbove && order.isNotEmpty() && c0 != null) {
            val i = order.indexOf(c0)
            if (i > 0) {
                for (k in 0 until i) skipped.add(order[k])
                log("Server di atas posisi sekarang dilewati: " + i)
            }
        }
        refreshCount()
        log("Server sekarang: " + (cur ?: "tidak terbaca") + ", total " + total + ", sudah selesai " + doneCount)

        // 3) Pindah server satu per satu
        var guard = 0
        while (true) {
            alive()
            if (total > 0 && doneCount >= total) break
            val next = pickNext()
            if (next == null) {
                log("Tidak ada server berikutnya")
                break
            }
            switchTo(next)
            cur = next
            claimServer(next)
            guard++
            if (guard > 80) throw Stop("Terlalu banyak server")
        }
        if (total > 0 && Prefs.doneToday(acct).size >= total) {
            Prefs.markAccountDone(acct)
            log("Akun " + acct + " selesai semua. Akun yang selesai hari ini: " +
                Prefs.accountsDoneToday().joinToString(", "))
        }
        finishAction()
    }

    private var lastAvg = 0L

    private fun timeOcr(bmp: Bitmap, r: Rect?): String {
        val ts = ArrayList<Long>()
        var n = 0
        for (i in 0 until 3) {
            val t0 = now()
            val l = Ocr.read(bmp, r)
            ts.add(now() - t0)
            if (l == null) return "OCR gagal"
            n = l.size
        }
        lastAvg = ts.sum() / ts.size
        return ts.joinToString(", ") + " ms (rata-rata " + (ts.sum() / ts.size) + " ms, " + n + " baris)"
    }

    private fun one(bmp: Bitmap, r: Rect): String {
        val t0 = now()
        val l = Ocr.read(bmp, r)
        val dt = now() - t0
        if (l == null) return dt.toString() + " ms (gagal)"
        val smp = if (l.isEmpty()) "-" else l[0].norm.take(14)
        return dt.toString() + " ms/" + l.size + " baris/" + smp
    }

    // Uji tinggi potongan: pita lebar penuh dan potongan kolom kiri (menu), di beberapa posisi
    private fun diagStrips(bmp: Bitmap) {
        log("DIAGNOSA: uji tinggi pita (lebar penuh), 3 posisi atas/tengah/bawah")
        for (hp in listOf(8, 12, 16, 24, 34)) {
            val h = (bmp.height * hp / 100f).toInt()
            val sb = StringBuilder("  pita " + hp + "%:")
            for (pos in listOf(0.0f, 0.33f, 0.66f)) {
                val y0 = minOf((bmp.height * pos).toInt(), bmp.height - h)
                sb.append(" [y" + (pos * 100).toInt() + "%] " + one(bmp, Rect(0, y0, bmp.width, y0 + h)) + ";")
            }
            log(sb.toString())
        }
        log("DIAGNOSA: uji potongan kolom kiri (x 4-27%), 2 posisi")
        for (hp in listOf(12, 24, 34)) {
            val h = (bmp.height * hp / 100f).toInt()
            val sb = StringBuilder("  kolom " + hp + "%:")
            for (pos in listOf(0.08f, 0.50f)) {
                val y0 = minOf((bmp.height * pos).toInt(), bmp.height - h)
                sb.append(" [y" + (pos * 100).toInt() + "%] " + one(bmp, Rect((bmp.width * 0.04f).toInt(), y0, (bmp.width * 0.27f).toInt(), y0 + h)) + ";")
            }
            log(sb.toString())
        }
    }

    // Pengukuran Tahap 1: OCR layar penuh vs area kecil, uji screenshot, status baterai
    // Uji gaya titik melayang: bandingkan kecepatan OCR pada screenshot yang SAMA.
    // Gaya yang paling tidak terlihat tetapi masih jauh lebih cepat disimpan sebagai Prefs.dotStyle.
    private fun diagPriority(bmp: Bitmap) {
        log("DIAGNOSA: uji gaya titik melayang (layar penuh 3x + menu kiri 3x per gaya, skala " + Prefs.ocrScale + "%). Gaya putih terlihat hanya dicoba kalau gaya tanpa warna tidak membantu.")
        val svc = ClaimService.inst
        val res = LongArray(6)
        fun measure(m: Int) {
            alive()
            svc?.dot(m)
            if (m >= 1) sleepS(1.5)
            val a = timeOcr(bmp, null)
            res[m] = lastAvg
            val b = timeOcr(bmp, rect(bmp, R_MENU))
            log("DIAGNOSA gaya " + m + " (" + dotName(m) + "): layar penuh " + a + " | menu kiri " + b)
        }
        var best = 0
        try {
            measure(0)
            measure(5)
            measure(4)
            if (res[5] > 0 && res[5] < res[0] * 0.75) best = 5
            else if (res[4] > 0 && res[4] < res[0] * 0.75) best = 4
            else {
                measure(3)
                if (res[3] > 0 && res[3] < res[0] * 0.75) best = 3
                else {
                    measure(2)
                    measure(1)
                    if (res[2] > 0 && res[2] < res[0] * 0.75) best = 2
                    else if (res[1] > 0 && res[1] < res[0] * 0.75) best = 1
                }
            }
        } finally {
            svc?.dot(0)
        }
        Prefs.dotStyle = best
        if (best == 0) {
            log("DIAGNOSA: tidak ada gaya titik yang jauh lebih cepat dari tanpa titik (" + res.joinToString(", ") + " ms). Klaim memakai tanpa titik.")
        } else {
            log("DIAGNOSA: gaya " + best + " (" + dotName(best) + ") dipakai otomatis saat klaim: " + res[best] + " ms vs " + res[0] + " ms tanpa titik.")
        }
    }


    // Kembali ke Pengaturan Akun lalu buka Daftar Karakter lagi: daftar baru selalu berisi 5 baris awal
    private fun freshList(): Scr? {
        val s = cap(R_SERVER)
        if (isServerList(s)) tapArrow(s)
        val t0 = now()
        while (now() - t0 < 8000L) {
            val a = cap(R_AKUN)
            if (isAccount(a)) {
                val l = accountToList(a)
                if (l != null) return l
            }
        }
        return null
    }

    private fun diagExtra(ctx: Context, s: Scr) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            log("DIAGNOSA: pengecualian baterai: " + (if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "SUDAH" else "BELUM"))
        } catch (_: Throwable) {
        }
        log("DIAGNOSA: uji screenshot 5x")
        for (i in 1..5) {
            val t0 = now()
            val b = ClaimService.inst?.shot()
            log("  #" + i + ": " + (if (b != null) "ok" else "GAGAL") + " " + (now() - t0) + " ms")
            sleepMs(450)
        }
        diagPriority(s.bmp)
        val rl = Ocr.read(s.bmp, rect(s.bmp, R_ROLE), 100)
        val rid = if (rl != null) parseRole(rl) else null
        log("DIAGNOSA: Role (ID akun) dari strip kanan atas: " + (rid ?: "tidak terbaca (normal kalau bukan layar Memuat Game)"))
    }

    // Uji sampai bawah: buka daftar baru lalu geser dengan cara yang sama persis seperti saat klaim, sampai ujung.
    // Mencatat server yang terlihat dan membandingkannya dengan jumlah server yang diisi di Pengaturan.
    private fun reachTest(s0: Scr) {
        val svc = ClaimService.inst
        svc?.dot(if (Prefs.dotStyle > 0) Prefs.dotStyle else 5)
        try {
            sleepS(1.0)
            reachRun(s0)
        } finally {
            svc?.dot(0)
        }
    }

    private fun reachRun(s0: Scr) {
        val n = Prefs.testCount.coerceIn(2, 300)
        val expect = n - 1
        log("UJI SAMPAI BAWAH: jumlah server " + n + " (daftar berisi " + expect + " baris, server yang sedang dipakai tidak ikut). Mode " +
            "Geser cepat " + Prefs.swipeStart + "% ke " + Prefs.swipeEnd + "%, " + Prefs.swipeHardMs + "ms; geser pelan " +
                Prefs.swipeSlowStart + "% ke " + Prefs.swipeSlowEnd + "%, " + Prefs.swipeSlowMs + "ms; geser balik " + Prefs.swipeBackStart + "% ke " + Prefs.swipeBackEnd + "%, " +
                Prefs.swipeBackMs + "ms; x " + Prefs.swipeX + "%")
        if (!isServerList(s0)) {
            log("UJI SAMPAI BAWAH: ini bukan Daftar Karakter (teks: " + s0.all.take(100) + "). Buka Daftar Karakter lalu ulangi.")
            ClaimService.inst?.banner("Buka Daftar Karakter dulu, lalu ulangi uji", 8000L, false)
            return
        }
        var s: Scr? = freshList()
        if (s == null) {
            log("UJI SAMPAI BAWAH: gagal membuka ulang Daftar Karakter")
            ClaimService.inst?.banner("Gagal membuka daftar baru, ulangi uji", 8000L, false)
            return
        }
        val current = readCurrent(s)
        cur = current
        expectOverride = expect
        val t0 = now()
        var steps = 0
        try {
            beginList(s)
            while (steps < 3000) {
                alive()
                val st = scrollNext(s!!, "uji sampai bawah")
                s = st.scr
                steps++
                if (!st.moved) break
            }
        } finally {
            expectOverride = -1
        }
        val secs = (now() - t0) / 1000L
        val bottom = if (s != null) serverNums(s).entries.maxByOrNull { it.value.cy }?.key else null
        val ok = listSeen.size >= expect
        log("UJI SAMPAI BAWAH: terlihat " + listSeen.size + " dari " + expect + " server dalam " + steps + " geseran, " + secs + " detik. Baris terbawah di layar: " + (bottom ?: "-"))
        log("UJI SAMPAI BAWAH: urutan baris: " + rowOrder.joinToString(","))
        log("UJI SAMPAI BAWAH: " + (if (ok) "SEMUA SERVER TERJANGKAU" else "TIDAK SEMUA TERJANGKAU"))
        ClaimService.inst?.banner(
            if (ok) "Semua " + expect + " server terjangkau (" + secs + " dtk)" else "Hanya " + listSeen.size + " dari " + expect + " server terjangkau",
            12000L, false
        )
    }

    fun diagnose(ctx: Context, reach: Boolean = false) {
        Prefs.init(ctx)
        if (running) {
            log("DIAGNOSA: hentikan klaim dulu")
            return
        }
        app = ctx.applicationContext
        if (ClaimService.inst == null) {
            log("DIAGNOSA: Aksesibilitas belum aktif")
            return
        }
        Matcher.reload(ctx.applicationContext)
        running = true
        lastActive = now()
        Thread {
            try {
                log("DIAGNOSA (" + (if (reach) "uji sampai bawah" else "umum/OCR") + "): mulai 15 detik lagi. Pindah ke game dan buka layar yang mau diukur, lalu jangan sentuh layar.")
                ClaimService.inst?.banner("Diagnosa mulai: pindah ke game, jangan sentuh layar (15 dtk)", 12000L, false)
                sleepS(15.0)
                val s = cap()
                if (reach) {
                    reachTest(s)
                } else {
                log("DIAGNOSA: layar " + s.w + "x" + s.h + ", " + s.lines.size + " baris teks")
                for (l in s.lines) {
                    val px = (l.cx * 100 / s.w).toInt()
                    val py = (l.cy * 100 / s.h).toInt()
                    log("  [" + px + "%," + py + "%] " + l.text + "  => " + l.norm)
                }
                log("DIAGNOSA: lobby=" + isLobby(s) + " event=" + eventOpen(s) + " daftarServer=" + isServerList(s) +
                    " profil=" + isProfile(s) + " akun=" + isAccount(s))
                log("DIAGNOSA: titik ketuk ikon profil = " + (s.w * Prefs.avatarX).toInt() + "," + (s.h * Prefs.avatarY).toInt() +
                    " px (" + ((Prefs.avatarX * 1000).toInt() / 10f) + "%," + ((Prefs.avatarY * 1000).toInt() / 10f) + "%) dari layar " + s.w + "x" + s.h)
                log("DIAGNOSA: server saat ini=" + readCurrent(s) + " ; server terbaca=" + serverNums(s).keys.joinToString(","))
                val pp = parseProfile(s)
                log("DIAGNOSA: profil ID=" + (pp?.first ?: "-") + " server=" + (pp?.second ?: "-"))
                log("DIAGNOSA: koin x20 emas=" + coinGold(s) + " titikMerah=" + coinRed(s) + " (hanya berarti di halaman Masukan Advance Server)")
                diagExtra(ctx, s)
                for (n in Matcher.names(ctx)) {
                    val h = Matcher.find(s, n)
                    log("  template " + n + ": " + (if (h == null) "tidak cocok" else "cocok " + (h.score * 100).toInt() + "% di titik " + h.x.toInt() + "," + h.y.toInt()))
                }
                }
                log("DIAGNOSA selesai. Salin log di tab Log dan kirim ke Claude.")
                ClaimService.inst?.banner("Diagnosa selesai. Buka VG Klaim > Log", 15000L, false)
            } catch (e: Throwable) {
                log("DIAGNOSA error: " + e.message)
            } finally {
                running = false
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }
}
