package id.pribadi.vgklaim

import android.content.Context
import android.content.Intent
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
            main()
            status = "Selesai"
            log("SELESAI. Server terklaim hari ini: " + doneCount + " dari " + total)
        } catch (e: Stop) {
            status = "Berhenti"
            log("Berhenti: " + e.message)
        } catch (e: InterruptedException) {
            status = "Berhenti"
            log("Dihentikan")
        } catch (e: Throwable) {
            status = "Error"
            log("Error: " + e.toString())
        } finally {
            running = false
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
            Thread.sleep(s)
            left -= s
        }
    }

    private fun sleepS(sec: Double) = sleepMs((sec * 1000).toLong())

    private fun speed(): Double = Prefs.speed.toDouble().coerceIn(1.0, 3.0)

    private fun cap(): Scr {
        var tries = 0
        while (true) {
            alive()
            val svc = ClaimService.inst ?: throw Stop("Aksesibilitas mati")
            val gap = 380 - (now() - lastShot)
            if (gap > 0) sleepMs(gap)
            lastShot = now()
            val t1 = now()
            val bmp = svc.shot()
            val t2 = now()
            if (bmp != null) {
                var lines = Ocr.read(bmp)
                if (lines == null) {
                    log("OCR gagal, mencoba lagi")
                    lines = Ocr.read(bmp)
                }
                val t3 = now()
                capN++
                capShotMs += t2 - t1
                capOcrMs += t3 - t2
                if (t3 - t1 > 3000L) {
                    log("Baca layar lambat: " + (t3 - t1) + " ms (screenshot " + (t2 - t1) + ", OCR " + (t3 - t2) + ")")
                }
                return Scr(bmp, lines ?: emptyList())
            }
            tries++
            if (tries >= 15) throw Stop("Screenshot terus gagal (layar mati / izin belum diberikan?)")
            sleepS(1.0)
        }
    }

    private fun resetStats() {
        capN = 0
        capShotMs = 0L
        capOcrMs = 0L
    }

    private fun statLine(): String {
        val n = if (capN == 0) 1 else capN
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576L
        return "baca layar rata-rata " + ((capShotMs + capOcrMs) / n) + " ms (screenshot " + (capShotMs / n) +
            ", OCR " + (capOcrMs / n) + ") x " + capN + " kali, memori " + used + "/" + (rt.maxMemory() / 1048576L) + " MB"
    }

    private fun tapXY(x: Float, y: Float, why: String) {
        alive()
        log("Ketuk: " + why)
        ClaimService.inst?.tap(x, y)
        lastActive = now()
        sleepS(0.6 / speed())
    }

    private fun tapLine(l: Line, why: String) = tapXY(l.cx, l.cy, why)

    private fun back() {
        ClaimService.inst?.back()
        lastActive = now()
        sleepS(0.8)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        ClaimService.inst?.swipe(x1, y1, x2, y2, 380)
        sleepS(0.7)
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
            .filter { it.cy > minY && SRV.containsMatchIn(it.norm) }
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
    private fun parseProfile(s: Scr): Pair<String, String>? {
        val re = Regex("([0-9]{6,12})\\s*\\(\\s*([0-9]{4,6})\\s*\\)")
        for (l in s.lines) {
            val m = re.find(l.text) ?: continue
            return Pair(m.groupValues[1], "s" + m.groupValues[2])
        }
        return null
    }

    private fun readProfileInfo(s: Scr) {
        val p = parseProfile(s) ?: return
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

    // Cadangan: "Account ID: ..." di layar Pengaturan Akun
    private fun readAccountId(s: Scr) {
        if (acct != "-") return
        val m = Regex("accountid([0-9]{6,12})").find(s.all) ?: return
        setAccount(m.groupValues[1], "Pengaturan Akun")
    }

    private fun isAccount(s: Scr): Boolean = s.has("pusatakun", "gantiakun", "pengaturanakun")

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

    private fun awaitLobby(maxSec: Int): Scr {
        val t0 = now()
        var okCount = 0
        var relaunched = 0
        var quietLogged = false
        var loadLogged = false
        val quietMs = 15_000L
        while (now() - t0 < maxSec * 1000L) {
            val s = cap()
            lastActive = now()
            val elapsed = now() - t0
            if (elapsed > (relaunched + 1) * 100_000L && relaunched < 2) {
                relaunched++
                log("Lobby belum muncul, membuka game lagi (" + relaunched + "/2)")
                launch()
                sleepS(5.0)
                continue
            }
            if (isLobby(s) && !eventOpen(s) && !isServerList(s)) {
                okCount++
                if (okCount >= 2) {
                    log("Lobby terdeteksi")
                    return s
                }
                sleepS(0.5)
                continue
            }
            okCount = 0
            // Layar loading ("Memuat Game..." + tulisan persyaratan di bawahnya): jangan diketuk
            if (s.has("memuatgame", "sayatelahmembaca")) {
                if (!loadLogged) {
                    log("Game sedang memuat (tidak mengetuk apa pun)")
                    loadLogged = true
                }
                sleepS(2.0)
                continue
            }
            if (elapsed < quietMs) {
                // 15 detik pertama: game masih memuat, jangan mengetuk apa pun
                if (!quietLogged) {
                    log("Game sedang loading, menunggu (tidak mengetuk apa pun)")
                    quietLogged = true
                }
                sleepS(1.5)
            } else if (!popups(s)) {
                log("Menunggu layar dikenali...")
                sleepS(1.5)
            }
        }
        throw Stop("Lobby tidak terdeteksi (cek kata kunci lobby di Setelan, jalankan Diagnosa)")
    }

    private fun settleLobby() {
        sleepS(Prefs.lobbyWait.toDouble())
        var quiet = 0
        for (i in 0 until 8) {
            val s = cap()
            if (popups(s)) quiet = 0 else {
                quiet++
                if (quiet >= 2) break
            }
        }
    }

    private fun backToLobby() {
        for (i in 0 until 12) {
            val s = cap()
            if (isLobby(s) && !eventOpen(s) && !isServerList(s)) return
            if (!isServerList(s) && tapX(s)) {
                // X sudah diketuk
            } else if (i % 2 == 0 && (eventOpen(s) || isAccount(s) || isProfile(s) || isServerList(s))) {
                tapArrow(s)
            } else {
                back()
            }
            sleepS(0.6)
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
        ClaimService.inst?.swipe(x1, y1, x2, y2, 700)
        sleepS(0.8)
    }

    private fun scrollList(s: Scr, forward: Boolean, frac: Float = 0.26f) {
        val x = s.w * 0.5f
        val a = s.h * 0.62f
        val b = s.h * (0.62f - frac)
        if (forward) slowSwipe(x, a, x, b) else slowSwipe(x, b, x, a)
    }

    // Geser daftar. Kalau tidak ada satu pun server yang sama antara sebelum dan sesudah
    // geser, berarti ada baris yang terlewat: geser balik lalu coba lagi dengan jarak lebih pendek.
    private fun scrollSafe(s: Scr, forward: Boolean): Scr {
        var cur = s
        var frac = 0.26f
        for (attempt in 0 until 3) {
            val before = serverNums(cur).keys.toSet()
            scrollList(cur, forward, frac)
            val n = cap()
            val after = serverNums(n).keys
            if (before.isEmpty() || after.isEmpty() || before.any { it in after }) return n
            log("Tarikan terlalu jauh (baris terlewat), kembali dan tarik lebih pendek")
            scrollList(n, !forward, frac)
            cur = cap()
            frac *= 0.6f
        }
        return cur
    }

    private fun sig(s: Scr): String = serverNums(s).keys.joinToString(",")

    // Geser daftar ke paling atas (berhenti kalau isi layar tidak berubah lagi)
    private fun scrollTop(first: Scr): Scr {
        var s = first
        var last = ""
        for (i in 0 until 14) {
            val g = sig(s)
            if (g == last) break
            last = g
            s = scrollSafe(s, false)
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

    private fun openServerList(): Scr {
        var avatarFails = 0
        var unknown = 0
        for (attempt in 0 until 10) {
            var s = cap()
            if (isServerList(s)) return s
            if (isAccount(s)) {
                readAccountId(s)
                val gs = s.exact("gantiserver") ?: s.find("gantiserver")
                if (gs != null) {
                    tapLine(gs, "Ganti Server")
                    sleepS(1.5)
                } else {
                    log("Di layar Pengaturan Akun, tombol Ganti Server tidak terbaca")
                    sleepS(1.0)
                }
                continue
            }
            if (isProfile(s)) {
                readProfileInfo(s)
                val ak = s.exact("akun")
                if (ak != null) {
                    tapLine(ak, "Akun")
                    sleepS(1.2)
                } else {
                    log("Menu Akun tidak terbaca di layar Profil, kembali")
                    back()
                }
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
            tapAvatar(s, avatarFails >= 1 && Matcher.has("avatar"))
            sleepS(1.5)
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
        var stagn = 0
        for (page in 0 until 25) {
            var added = 0
            for ((n, _) in serverNums(s)) {
                if (!order.contains(n)) {
                    order.add(n)
                    added++
                }
            }
            val c = readCurrent(s)
            if (c != null) cur = c
            if (added == 0) {
                stagn++
                if (stagn >= 2) break
            } else stagn = 0
            s = scrollSafe(s, true)
        }
        log("Server terbaca: " + order.size + " (" + order.joinToString(",") + ")")
    }

    private fun pickNext(): String? {
        var s = openServerList()
        val downOnly = Prefs.skipAbove
        if (!downOnly) s = scrollTop(s)
        var stagn = 0
        var lastSig = ""
        for (page in 0 until 30) {
            val nums = serverNums(s)
            for ((n, _) in nums) {
                if (!isDone(n) && n != cur) {
                    if (!order.contains(n)) order.add(n)
                    return n
                }
            }
            val sig = nums.keys.joinToString(",")
            if (sig == lastSig) {
                stagn++
                if (stagn >= 2) break
            } else stagn = 0
            lastSig = sig
            s = scrollSafe(s, true)
        }
        return null
    }

    private fun switchTo(name: String) {
        log("Ganti server -> " + name)
        status = "Ganti server " + name
        var s = cap()
        if (!isServerList(s)) s = openServerList()
        var found: Line? = null
        for (pass in 0 until 2) {
            var stagn = 0
            var lastSig = ""
            for (page in 0 until 30) {
                val l = serverNums(s)[name]
                if (l != null) {
                    found = l
                    break
                }
                val sig = serverNums(s).keys.joinToString(",")
                if (sig == lastSig) {
                    stagn++
                    if (stagn >= 2) break
                } else stagn = 0
                lastSig = sig
                s = scrollSafe(s, pass == 0)
            }
            if (found != null) break
        }
        val l = found ?: throw Stop("Server " + name + " tidak ditemukan di daftar")
        tapLine(l, "Pilih server " + name)
        sleepS(1.0)
        confirmSwitch()
        sleepS(3.0)
        awaitLobby(200)
        settleLobby()
    }

    private fun confirmSwitch() {
        var gsTaps = 0
        for (i in 0 until 14) {
            val s = cap()
            if (s.has("memuatgame")) {
                log("Game memuat ulang")
                return
            }
            // Popup "Pemberitahuan Sistem: Server telah diganti..." -> tekan OK
            if (s.has("pemberitahuansistem", "melakukanrestart", "restartgame", "servertelahdiganti")) {
                val b = s.exact("ok") ?: s.exact("konfirmasi") ?: s.exact("ya")
                if (b != null) {
                    tapLine(b, "OK (server telah diganti)")
                    sleepS(1.0)
                    val s2 = cap()
                    if (s2.has("pemberitahuansistem", "servertelahdiganti")) {
                        val b2 = s2.exact("ok")
                        if (b2 != null) tapLine(b2, "OK diulang (popup masih terlihat)")
                    }
                    return
                }
            }
            // Daftar karakter: tombol emas "Ganti Server" di bawah daftar
            if (isServerList(s)) {
                val gs = s.exact("gantiserver")
                if (gs != null && gsTaps < 3) {
                    tapLine(gs, "Ganti Server (tombol emas)")
                    gsTaps++
                    sleepS(1.2)
                    continue
                }
            }
            sleepS(0.8)
        }
    }

    private fun openEvent() {
        for (a in 0 until 6) {
            val s = cap()
            if (eventOpen(s)) return
            if (popups(s)) continue
            if (!isLobby(s)) {
                // Bukan lobby dan bukan halaman Hadiah: kemungkinan layar Event tab lain
                // (mis. Festival Pemuda). Ketuk ikon petir untuk pindah ke tab Hadiah.
                val p = Matcher.findPrefix(s, "petir")
                if (p != null) {
                    tapXY(p.x, p.y, "tab Hadiah (template petir)")
                    sleepS(1.5)
                    continue
                }
                // Tanpa template: posisi ikon petir berpindah-pindah, jadi coba ikon tab
                // di sisi kiri satu per satu sampai halaman Hadiah terbuka.
                for (yy in listOf(PETIR_Y, 0.347f, 0.671f, 0.185f)) {
                    tapXY(s.w * PETIR_X, s.h * yy, "ikon tab kiri y=" + (yy * 100).toInt() + "% (cari tab Hadiah)")
                    sleepS(1.5)
                    if (eventOpen(cap())) return
                }
                continue
            }
            val l = s.exact("event")
            if (l != null) {
                tapLine(l, "Event")
                sleepS(2.0)
                continue
            }
            val h = Matcher.find(s, "event")
            if (h != null) {
                tapXY(h.x, h.y, "Event (gambar)")
                sleepS(2.0)
                continue
            }
            tapXY(s.w * PETIR_X, s.h * PETIR_Y, "Event (posisi tetap)")
            sleepS(2.0)
        }
        throw Stop("Halaman Hadiah di Event tidak terbuka (jalankan Diagnosa di layar Event)")
    }

    private fun openMenu(keys: List<String>): Scr? {
        for (i in 0 until 7) {
            val s = cap()
            for (k in keys) {
                val l = s.find(k)
                if (l != null) {
                    tapLine(l, "menu " + k)
                    sleepS(1.2)
                    return cap()
                }
            }
            val x = s.w * 0.15f
            if (i < 3) swipe(x, s.h * 0.75f, x, s.h * 0.30f) else swipe(x, s.h * 0.30f, x, s.h * 0.75f)
        }
        return null
    }

    private fun waitOk(): Boolean {
        for (i in 0 until 6) {
            val s = cap()
            val b = s.exact("ok") ?: s.exact("konfirmasi") ?: s.exact("berikutnya")
            if (b != null) {
                tapLine(b, "OK")
                lastActive = now()
                sleepS(0.8)
                return true
            }
            val c = s.find("ketukuntuk")
            if (c != null) {
                tapLine(c, "lanjut")
                return true
            }
            sleepS(0.4)
        }
        return false
    }

    private fun claimLoop(label: String, maxRounds: Int, first: Scr? = null): Int {
        var claimed = 0
        var idle = 0
        var noPopup = 0
        for (round in 0 until maxRounds) {
            if (now() - serverT0 > 15 * 60_000L) throw Stop("Server ini terlalu lama (>15 menit)")
            val s = if (round == 0 && first != null) first else cap()
            val l = s.exact("klaimsemua") ?: s.find("klaimsemua") ?: s.exactAll("klaim").firstOrNull()
            if (s.has("sudahdiklaim")) {
                log(label + ": sudah diklaim")
                break
            }
            if (l == null) break
            tapLine(l, "Klaim (" + label + ")")
            claimed++
            lastActive = now()
            sleepS(0.8)
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

    // Cari menu: baca layar, geser turun SEKALI, baca lagi. Tidak geser naik.
    private fun openMenuQuick(keys: List<String>): Scr? {
        for (i in 0 until 2) {
            val s = cap()
            for (k in keys) {
                val l = s.find(k)
                if (l != null) {
                    tapLine(l, "menu " + k)
                    sleepS(1.2)
                    return cap()
                }
            }
            if (i == 0) {
                val x = s.w * 0.15f
                swipe(x, s.h * 0.75f, x, s.h * 0.30f)
            }
        }
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

    private fun claimCoin(first: Scr) {
        var s = first
        var gold = 0
        var red = 0
        var gray = 0
        var panel = false
        // Tunggu sampai halaman kanan dan koin tergambar (maksimal 3 kali baca)
        for (k in 0 until 3) {
            panel = s.has("berikanmasukan", "menemukanbug", "pengalamanburuk")
            gold = coinGold(s)
            red = coinRed(s)
            gray = coinGray(s)
            if (panel && (gold >= GOLD_MIN || gray >= GRAY_MIN)) break
            if (k < 2) {
                sleepS(0.4)
                s = cap()
            }
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
                    val sp = cap()
                    if (sp.has("pusatbantuan")) {
                        seen = true
                        break
                    }
                }
                log(if (seen) "Pusat Bantuan terbaca, kembali" else "Pusat Bantuan tidak terbaca, tetap kembali")
                back()
                sleepS(0.8)
                val s2 = cap()
                if (!eventOpen(s2)) {
                    log("Kembali dari Pergi, membuka Event lagi")
                    openEvent()
                    openMenu(menuFeedback)
                }
            } else {
                log("Tombol Pergi tidak terbaca")
            }
        }
        // Tunggu titik merah muncul (siap diklaim), lalu ketuk koin
        var ready = false
        for (k in 0 until 3) {
            s = cap()
            if (!eventOpen(s)) break
            if (coinRed(s) >= RED_MIN) {
                ready = true
                break
            }
            sleepS(0.4)
        }
        if (eventOpen(s)) {
            tapXY(s.w * COIN_X, s.h * COIN_Y, if (ready) "koin x20" else "koin x20 (titik merah belum terlihat)")
            sleepS(1.0)
            waitOk()
        }
    }

    private fun claimEvents() {
        openEvent()

        val m1 = openMenu(menuSupply)
        if (m1 != null) {
            val n = claimLoop("Vanguard Coin Supply", 3, m1)
            log("Vanguard Coin Supply selesai (" + n + " ketukan klaim)")
        } else log("Menu Vanguard Coin Supply tidak ada, lanjut")

        val m2 = openMenuQuick(menuUpdate)
        if (m2 != null) {
            val n = claimLoop("Hadiah Pembaruan", 3, m2)
            log("Hadiah Pembaruan selesai (" + n + " ketukan klaim)")
        } else log("Menu Hadiah Pembaruan tidak ada, lanjut")

        val m3 = openMenu(menuFeedback)
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
        if (cur == null) cur = readCurrent(ls)
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
        if (Prefs.closeGame) closeApp() else backToLobby()
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
                log("DIAGNOSA: mulai 8 detik lagi. Buka game ke LOBBY sekarang.")
                sleepS(8.0)
                val s = cap()
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
                for (n in Matcher.names(ctx)) {
                    val h = Matcher.find(s, n)
                    log("  template " + n + ": " + (if (h == null) "tidak cocok" else "cocok " + (h.score * 100).toInt() + "% di titik " + h.x.toInt() + "," + h.y.toInt()))
                }
                log("DIAGNOSA selesai. Salin log di tab Log dan kirim ke Claude.")
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
