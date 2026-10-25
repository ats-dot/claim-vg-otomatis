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
        if (ClaimService.inst == null) return "Aktifkan dulu Aksesibilitas VG Klaim (tab Setelan)"
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
        3 -> "hampir transparan 5%"
        4 -> "transparan penuh"
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

    // Jeda setelah ketukan: 0 kalau "Mode tanpa jeda" aktif, selain itu sesuai pengaturan milidetik
    private fun stepWait() {
        if (!Prefs.noDelay) sleepMs(Prefs.stepMs.toLong().coerceIn(0L, 3000L))
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

    private fun serverNums(s: Scr): LinkedHashMap<String, Line> {
        // Baris di atas judul "Semua Server" (bagian "Server Saat Ini") tidak ikut dihitung
        val hdr = s.find("semuaserver")
        val minY: Float = hdr?.cy ?: -1f
        val sorted = s.lines
            .filter { it.cy > minY && it.cy > s.h * 0.17f && it.cy < s.h * 0.70f && SRV.containsMatchIn(it.norm) }
            .sortedWith(compareBy({ (it.cy / 40f).toInt() }, { it.cx }))
        val m = LinkedHashMap<String, Line>()
        for (l in sorted) {
            val k = SRV.find(l.norm)?.value ?: continue
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
                .mapNotNull { SRV.find(it.norm)?.value }
                .firstOrNull()
            if (c != null) return c
        }
        val m = Regex("serversaatini(?:[0-9]{4,6})?(s[0-9]{4,6})").find(s.all)
        return m?.groupValues?.get(1)
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

    private fun popups(s: Scr): Boolean {
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
        throw Stop("Lobby tidak terdeteksi (cek kata kunci lobby di Setelan, jalankan Diagnosa)")
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
        if (darkTaps >= 3) {
            darkTaps = 0
            topupBlockUntil = now() + 60_000L
            log("Lobby tetap gelap setelah 3 ketukan X. Pemeriksaan popup diabaikan 60 detik")
            return false
        }
        darkTaps++
        log("Lobby tampak gelap (kecerahan teks Event " + m + "), kemungkinan ada popup")
        tapXY(s.w * 0.856f, s.h * 0.09f, "tutup X (popup)")
        return true
    }

    // Pantau lobby terus-menerus: popup ditutup begitu muncul. Selesai kalau sudah tenang selama
    // "waktu tunggu lobby" detik sejak popup terakhir.
    private fun settleLobby() {
        val calm = (Prefs.lobbyWait * 1000L).coerceAtLeast(800L)
        val t0 = now()
        var last = now()
        while (now() - last < calm && now() - t0 < 30_000L) {
            val s = cap()
            if (popups(s) || darkPopupX(s)) last = now()
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

    private fun closeApp() {
        val svc = ClaimService.inst ?: return
        log("Menutup game")
        svc.recents()
        sleepS(1.5)
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        svc.swipe(w / 2, h * 0.6f, w / 2, h * 0.05f, 250)
        sleepS(1.0)
        svc.home()
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
    private class Sty(val ms: Long, val rows: Float, val name: String, val xf: Float, val hold: Long = 0L)

    // "Tarik panjang" (sekitar 55% tinggi layar, dari bawah daftar sampai dekat judul, 0,17 detik, x sekitar 77%)
    // adalah gerakan jari pada video contoh; gerakan ini yang memicu pemuatan baris server baru.
    private val STYLES = listOf(
        Sty(160L, 3.0f, "gesek 3 baris kanan", 0.77f),
        Sty(170L, 7.0f, "tarik panjang kanan", 0.77f),
        Sty(130L, 3.5f, "gesek cepat tengah", 0.60f),
        Sty(170L, 7.0f, "tarik panjang tengah", 0.60f),
        Sty(200L, 3.0f, "tarik cepat kanan", 0.78f),
        Sty(200L, 3.0f, "niru jari + tahan", 0.78f, 600L),
        Sty(700L, 2.0f, "tarik pelan tengah", 0.50f)
    )

    // Geser daftar satu langkah dan BUKTIKAN daftarnya bergerak (posisi baris yang sama bergeser).
    // moved=false setelah semua gaya geser dicoba = benar-benar ujung daftar.
    private fun scrollStep(s: Scr, forward: Boolean, tag: String, maxTry: Int = 7): Step {
        val before = serverNums(s)
        val rh = rowH(s)
        var cur = s
        var lost = false
        for (attempt in 0 until maxTry) {
            val idx = (styleIdx + attempt) % STYLES.size
            val st = STYLES[idx]
            val x0 = s.w * st.xf
            val x1 = s.w * (st.xf + 0.02f)
            val y0 = if (forward) s.h * 0.64f else s.h * 0.38f
            val maxD = if (forward) y0 - s.h * 0.06f else s.h * 0.90f - y0
            val dist = minOf(st.rows * rh, maxD)
            val y1 = if (forward) y0 - dist else y0 + dist
            if (st.hold > 0L) doSwipeHold(x0, y0, x1, y1, st.ms, st.hold, "geser daftar (" + st.name + ")")
            else doSwipe(x0, y0, x1, y1, st.ms, "geser daftar (" + st.name + ")")
            sleepMs(500)
            scrollNo++
            var after = serverNums(cap(R_SERVER).also { cur = it })
            if (after.isEmpty()) {
                after = serverNums(cap(R_SERVER).also { cur = it })
            }
            if (after.isEmpty()) {
                log("Geseran " + scrollNo + " (" + tag + "): daftar tidak terbaca setelah geser (" + st.name + ")")
                lost = true
                continue
            }
            if (before.isEmpty()) {
                log("Geseran " + scrollNo + " (" + tag + "): terlihat " + rangeText(after) + " (tidak bisa diverifikasi, daftar sebelumnya kosong)")
                return Step(cur, true, false)
            }
            var common: String? = null
            for (k in after.keys) if (before.containsKey(k)) {
                common = k
                break
            }
            if (common == null) {
                log("Geseran " + scrollNo + " (" + tag + "): gaya " + st.name + " terlalu jauh, baris mungkin terlewat. Geser balik")
                doSwipe(x1, y1, x0, y0, st.ms, "geser balik")
                sleepMs(500)
                cur = cap(R_SERVER)
                lost = true
                continue
            }
            val shift = Math.abs(before[common]!!.cy - after[common]!!.cy)
            val grew = after.keys.any { !before.containsKey(it) }
            if (shift < rh * 0.4f && !grew) {
                // Baris baru bisa dimuat beberapa saat setelah tarikan: tunggu sebentar lalu baca ulang
                sleepMs(1200)
                val a2 = serverNums(cap(R_SERVER).also { cur = it })
                if (a2.isNotEmpty()) {
                    val g2 = a2.keys.any { !before.containsKey(it) }
                    val c2 = a2.keys.firstOrNull { before.containsKey(it) }
                    val sh2 = if (c2 != null) Math.abs(before[c2]!!.cy - a2[c2]!!.cy) else 999f
                    if (g2 || sh2 >= rh * 0.4f) {
                        styleIdx = idx
                        log("Geseran " + scrollNo + " (" + tag + "): gaya " + st.name + ", baris baru muncul setelah menunggu: " + rangeText(a2))
                        return Step(cur, true, false)
                    }
                }
            }
            if (shift >= rh * 0.4f || grew) {
                styleIdx = idx
                log("Geseran " + scrollNo + " (" + tag + "): gaya " + st.name + " (x " + (st.xf * 100).toInt() + "%), terlihat " + rangeText(after) + ", bergerak " + String.format("%.1f", shift / rh) + " baris")
                return Step(cur, true, false)
            }
            log("Geseran " + scrollNo + " (" + tag + "): gaya " + st.name + " (x " + (st.xf * 100).toInt() + "%, " + st.ms + " ms) TIDAK menggerakkan daftar (bergerak " + String.format("%.1f", shift / rh) + " baris), coba gaya lain")
            lost = false
        }
        return Step(cur, false, lost)
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
        while (now() - t0 < 7000L) {
            val a = cap()
            if (isAccount(a)) return accountToList(a)
            if (isServerList(a)) return a
            if (isProfile(a)) {
                val tab = akunTab(a)
                tapXY(tab.first, tab.second, "tab Pengaturan Akun", true)
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
                throw Stop("Ikon profil tidak membuka menu Profil/Akun. Jalankan Diagnosa di lobby, lalu ubah angka X/Y ikon profil di tab Setelan.")
            }
        }
        throw Stop("Daftar server tidak terbuka (jalankan Diagnosa di menu profil)")
    }

    private fun readAll(first: Scr) {
        var s = scrollTop(first)
        val want = Prefs.serverCount
        var why = "batas halaman"
        for (page in 0 until 60) {
            alive()
            for ((n, _) in serverNums(s)) {
                if (!order.contains(n)) order.add(n)
            }
            val c = readCurrent(s)
            if (c != null) cur = c
            if (want > 0 && order.size >= want) {
                why = "jumlah " + want + " tercapai"
                break
            }
            val st = scrollStep(s, true, "baca daftar")
            s = st.scr
            if (!st.moved) {
                why = if (st.lost) "geseran gagal atau melewati baris (daftar mungkin tidak utuh)" else "ujung daftar"
                for ((n, _) in serverNums(s)) {
                    if (!order.contains(n)) order.add(n)
                }
                break
            }
        }
        log("Server terbaca: " + order.size + " (" + order.joinToString(",") + "). Berhenti membaca: " + why)
        if (want > 0 && order.size < want) {
            log("Peringatan: diisi " + want + " server, baru terbaca " + order.size)
        }
    }

    private fun pickNext(): String? {
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
        if (!Prefs.skipAbove) s = scrollTop(s)
        for (page in 0 until 60) {
            alive()
            for ((n, _) in serverNums(s)) {
                if (!isDone(n) && n != cur) {
                    if (!order.contains(n)) order.add(n)
                    return n
                }
            }
            val st = scrollStep(s, true, "cari server berikutnya")
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
        var s = cap(R_SERVER)
        if (!isServerList(s)) s = openServerList()
        var found: Line? = null
        for (pass in 0 until 2) {
            for (page in 0 until 60) {
                alive()
                val l = serverNums(s)[name]
                if (l != null) {
                    found = l
                    break
                }
                val st = scrollStep(s, pass == 0, "cari " + name)
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

    private fun confirmSwitch() {
        var gsTaps = 0
        var lastGs = 0L
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
                    tapLine(b, "OK (server telah diganti)")
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

    // Cari menu: baca layar, geser turun SEKALI, baca lagi. Tidak geser naik.
    // Menu Hadiah Pembaruan ada di paling atas daftar: cukup baca, tanpa geser
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
        }
        log("Menu " + keys[0] + " tidak ketemu. Teks menu terakhir: " + seen.take(140))
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

    private fun claimEvents() {
        openEvent()

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
        if (Prefs.closeGame) closeApp() else svc?.home()
        if (mode == 1) {
            sleepS(0.8)
            log("Setelah selesai: kembali ke VG Klaim")
            openOwnApp()
        } else {
            log("Setelah selesai: ke Home")
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
        if (!Prefs.fast || want <= 0) {
            readAll(ls)
        }
        val c0 = cur
        total = if (want > 0) want else (order.size + (if (c0 != null && !order.contains(c0)) 1 else 0))
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
        log("DIAGNOSA: uji gaya titik melayang (layar penuh 3x + menu kiri 3x per gaya, skala " + Prefs.ocrScale + "%)")
        val svc = ClaimService.inst
        val res = LongArray(5)
        try {
            for (m in 0..4) {
                alive()
                svc?.dot(m)
                sleepS(1.5)
                val a = timeOcr(bmp, null)
                res[m] = lastAvg
                val b = timeOcr(bmp, rect(bmp, R_MENU))
                log("DIAGNOSA gaya " + m + " (" + dotName(m) + "): layar penuh " + a + " | menu kiri " + b)
            }
        } finally {
            svc?.dot(0)
        }
        // pilih gaya paling tersamar (nomor terbesar) yang jauh lebih cepat dari tanpa titik
        var best = 0
        for (m in 1..4) if (res[m] > 0 && res[m] < res[0] * 0.75) best = m
        Prefs.dotStyle = best
        if (best == 0) {
            log("DIAGNOSA: tidak ada gaya titik yang jauh lebih cepat dari tanpa titik (" + res.joinToString(", ") + " ms). Klaim memakai tanpa titik.")
        } else {
            log("DIAGNOSA: gaya " + best + " (" + dotName(best) + ") dipakai otomatis saat klaim: " + res[best] + " ms vs " + res[0] + " ms tanpa titik.")
        }
    }

    private class Var(val name: String, val run: () -> Unit)

    private fun resetTop(topKey: String): Boolean {
        for (i in 0 until 6) {
            val s = cap(R_SERVER)
            val n = serverNums(s)
            if (n.isNotEmpty() && n.keys.first() == topKey) return true
            doSwipe(s.w * 0.77f, s.h * 0.40f, s.w * 0.78f, s.h * 0.90f, 170, "kembali ke atas")
            sleepMs(1000)
        }
        return false
    }

    private fun frameLine(t0: Long, s: Scr, base: Set<String>): String {
        val n = serverNums(s)
        val last = n.values.maxByOrNull { it.cy }
        val first = n.values.minByOrNull { it.cy }
        val baru = n.keys.filter { !base.contains(it) }
        return "    +" + (now() - t0) + " ms: " + rangeText(n) +
            ", baris pertama y=" + (if (first != null) (first.cy * 100 / s.h).toInt() else -1) +
            "%, terakhir y=" + (if (last != null) (last.cy * 100 / s.h).toInt() else -1) + "%" +
            (if (baru.isNotEmpty()) ", BARU: " + baru.joinToString(",") else "")
    }

    // Jalankan gesekan di thread lain sambil memotret daftar sekitar 2x per detik
    private fun sampleDuring(base: Set<String>, act: () -> Unit): Boolean {
        val t0 = now()
        val th = Thread {
            try {
                act()
            } catch (e: Throwable) {
                log("DIAGNOSA GESER: gesekan error " + e.javaClass.simpleName)
            }
        }
        th.isDaemon = true
        th.start()
        var grew = false
        while (true) {
            val s = cap(R_SERVER)
            val n = serverNums(s)
            log(frameLine(t0, s, base))
            if (n.keys.any { !base.contains(it) }) grew = true
            val el = now() - t0
            if (!th.isAlive && el >= 3000L) break
            if (el > 12_000L) break
        }
        return grew
    }

    // Diagnosa geser v3: titik transparan aktif (supaya baca cepat), tiap uji dimulai dari posisi atas
    private fun diagScroll(s0: Scr) {
        val svc = ClaimService.inst
        svc?.dot(if (Prefs.dotStyle > 0) Prefs.dotStyle else 4)
        try {
            sleepS(1.0)
            diagScrollRun(s0)
        } finally {
            svc?.dot(0)
        }
    }

    private fun diagScrollRun(s0: Scr) {
        log("DIAGNOSA GESER v3: layar " + s0.w + "x" + s0.h + ", " + s0.lines.size + " baris teks")
        if (!isServerList(s0)) {
            log("DIAGNOSA GESER: ini bukan Daftar Karakter (teks: " + s0.all.take(100) + "). Buka Daftar Karakter lalu ulangi.")
            return
        }
        val first = serverNums(s0)
        if (first.isEmpty()) {
            log("DIAGNOSA GESER: tidak ada baris server terbaca")
            return
        }
        val topKey = first.keys.first()
        val rh = rowH(s0)
        log("DIAGNOSA GESER: awal terlihat " + rangeText(first) + ", server saat ini " + readCurrent(s0) + ", tinggi baris " + rh.toInt() + " px")
        val h = s0.h.toFloat()
        val w = s0.w.toFloat()
        val tests = listOf(
            Var("A tarik panjang x77%, 170ms, dari 66% ke 8% tinggi layar (seperti foto)") { doSwipe(w * 0.77f, h * 0.66f, w * 0.78f, h * 0.08f, 170, "A") },
            Var("B tarik panjang x60%, 170ms") { doSwipe(w * 0.60f, h * 0.66f, w * 0.61f, h * 0.08f, 170, "B") },
            Var("C tarik panjang x77% + tahan 600ms") { doSwipeHold(w * 0.77f, h * 0.66f, w * 0.78f, h * 0.08f, 170, 600, "C") },
            Var("D tarik panjang pelan 350ms x77%") { doSwipe(w * 0.77f, h * 0.66f, w * 0.78f, h * 0.08f, 350, "D") },
            Var("E gesek pendek 3 baris x77%, 160ms") { doSwipe(w * 0.77f, h * 0.64f, w * 0.78f, h * 0.64f - 3.0f * rh, 160, "E") },
            Var("F 40% tinggi layar x60%, 130ms (cara lama yang gagal)") { doSwipe(w * 0.60f, h * 0.66f, w * 0.60f, h * 0.26f, 130, "F") }
        )
        var winner = ""
        for (t in tests) {
            alive()
            val topOk = resetTop(topKey)
            val base = serverNums(cap(R_SERVER)).keys.toSet()
            log("DIAGNOSA GESER uji " + t.name + " (mulai: " + rangeText(serverNums(cap(R_SERVER))) + (if (topOk) "" else ", PERINGATAN: reset ke atas belum pasti") + ")")
            val grew = sampleDuring(base, t.run)
            if (grew) {
                winner = t.name
                log("DIAGNOSA GESER: BERHASIL, baris baru muncul dengan: " + t.name)
                break
            } else {
                log("DIAGNOSA GESER: " + t.name + " tidak memunculkan baris baru")
            }
        }
        if (winner.isEmpty()) log("DIAGNOSA GESER: tidak ada gesekan yang memunculkan baris baru")
        log("DIAGNOSA GESER: selesai")
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

    fun diagnose(ctx: Context) {
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
                log("DIAGNOSA (" + (if (Prefs.diagMode == 1) "geser daftar" else "umum/OCR") + "): mulai 15 detik lagi. Pindah ke game dan buka layar yang mau diukur, lalu jangan sentuh layar.")
                ClaimService.inst?.banner("Diagnosa mulai: pindah ke game, jangan sentuh layar (15 dtk)", 12000L, false)
                sleepS(15.0)
                val s = cap()
                if (Prefs.diagMode == 1) {
                    diagScroll(s)
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
