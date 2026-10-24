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

    private val RNN = Regex("^[a-z]{1,16}([0-9]{2})$")

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
            val bmp = svc.shot()
            if (bmp != null) return Scr(bmp, Ocr.read(bmp))
            tries++
            if (tries >= 6) throw Stop("Screenshot terus gagal (layar mati / izin belum diberikan?)")
            sleepS(1.0)
        }
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
        val sorted = s.lines
            .filter { RNN.matches(it.norm) }
            .sortedWith(compareBy({ (it.cy / 40f).toInt() }, { it.cx }))
        val m = LinkedHashMap<String, Line>()
        for (l in sorted) if (!m.containsKey(l.norm)) m[l.norm] = l
        return m
    }

    private fun isServerList(s: Scr): Boolean =
        s.has("serversaatini") || serverNums(s).size >= 3

    private fun readCurrent(s: Scr): String? {
        for (l in s.lines) {
            if (l.norm.contains("serversaatini")) {
                val rest = l.norm.substringAfter("serversaatini")
                if (RNN.matches(rest)) return rest
            }
        }
        val m = Regex("serversaatini([a-z]{1,16}[0-9]{2})").find(s.all)
        return m?.groupValues?.get(1)
    }

    private fun popups(s: Scr): Boolean {
        val x = Matcher.findPrefix(s, "x")
        if (x != null) {
            tapXY(x.x, x.y, "tutup X")
            return true
        }
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
        while (now() - t0 < maxSec * 1000L) {
            val s = cap()
            lastActive = now()
            if (s.has("sayatelahmembaca", "persyaratandankebijakanprivasi")) {
                val a = s.find("sayatelahmembaca")
                if (a != null) tapLine(a, "setuju syarat")
                val m = s.exact("masuk")
                if (m != null) tapLine(m, "Masuk")
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
            if (s.has("memuatgame")) {
                log("Game sedang memuat...")
                sleepS(2.0)
                continue
            }
            if (!popups(s)) {
                log("Menunggu layar dikenali...")
                sleepS(1.5)
            }
            val elapsed = now() - t0
            if (elapsed > (relaunched + 1) * 100_000L && relaunched < 2) {
                relaunched++
                log("Lobby belum muncul, membuka game lagi (" + relaunched + "/2)")
                launch()
                sleepS(5.0)
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
        for (i in 0 until 9) {
            val s = cap()
            if (isLobby(s) && !eventOpen(s) && !isServerList(s)) return
            val x = Matcher.findPrefix(s, "x")
            if (x != null && !isServerList(s)) {
                tapXY(x.x, x.y, "tutup X")
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

    private fun scrollList(s: Scr, forward: Boolean) {
        val x = s.w * 0.5f
        val a = s.h * 0.72f
        val b = s.h * 0.28f
        if (forward) swipe(x, a, x, b) else swipe(x, b, x, a)
    }

    private fun openServerList(): Scr {
        for (attempt in 0 until 5) {
            var s = cap()
            if (isServerList(s)) return s
            if (!isLobby(s) || eventOpen(s)) {
                if (!popups(s)) back()
                continue
            }
            val av = Matcher.find(s, "avatar")
                ?: throw Stop("Ikon profil tidak ketemu. Buat template bernama 'avatar' di tab Templat.")
            tapXY(av.x, av.y, "ikon profil")
            sleepS(1.5)
            s = cap()
            var gs = s.find("gantiserver")
            if (gs == null) {
                val ak = s.find("gantiakun") ?: s.find("pusatakun") ?: s.exact("akun")
                if (ak != null) {
                    tapLine(ak, "Akun")
                    sleepS(1.2)
                    s = cap()
                    gs = s.find("gantiserver")
                }
            }
            if (gs != null) {
                tapLine(gs, "Ganti Server")
                sleepS(1.5)
            }
        }
        throw Stop("Daftar server tidak terbuka (jalankan Diagnosa di menu profil)")
    }

    private fun readAll(first: Scr) {
        var s = first
        for (i in 0 until 4) {
            scrollList(s, false)
        }
        s = cap()
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
            scrollList(s, true)
            s = cap()
        }
        log("Server terbaca: " + order.size + " (" + order.joinToString(",") + ")")
    }

    private fun pickNext(): String? {
        var s = openServerList()
        val downOnly = Prefs.skipAbove
        if (!downOnly) {
            for (i in 0 until 4) scrollList(s, false)
            s = cap()
        }
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
            scrollList(s, true)
            s = cap()
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
                scrollList(s, pass == 0)
                s = cap()
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
        var tapped = 0
        for (i in 0 until 10) {
            val s = cap()
            if (s.has("memuatgame")) {
                log("Game memuat ulang")
                return
            }
            val b = s.exact("konfirmasi") ?: s.exact("ok") ?: s.exact("ya")
            if (b != null) {
                tapLine(b, "konfirmasi ganti server")
                tapped++
                sleepS(1.2)
                if (s.has("melakukanrestart", "restartgame")) return
                continue
            }
            if (tapped > 0 && !isServerList(s)) return
            sleepS(0.8)
        }
    }

    private fun openEvent() {
        for (a in 0 until 5) {
            val s = cap()
            if (eventOpen(s)) return
            if (popups(s)) continue
            val l = s.exact("event") ?: s.find("event")
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
            sleepS(1.0)
        }
        throw Stop("Event tidak terbuka (tulisan 'Event' tidak ditemukan; buat template 'event')")
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

    private fun claimLoop(label: String, maxRounds: Int): Int {
        var claimed = 0
        var idle = 0
        for (round in 0 until maxRounds) {
            if (now() - serverT0 > 15 * 60_000L) throw Stop("Server ini terlalu lama (>15 menit)")
            val s = cap()
            val l = s.exact("klaimsemua") ?: s.find("klaimsemua") ?: s.exactAll("klaim").firstOrNull()
            if (l == null) {
                idle++
                if (idle >= 2) break
                sleepS(0.8)
                continue
            }
            idle = 0
            tapLine(l, "Klaim (" + label + ")")
            claimed++
            lastActive = now()
            sleepS(0.8)
            waitOk()
        }
        return claimed
    }

    private val menuSupply = listOf("vanguardcoinsupply", "vanguardcoin", "coinsupply")
    private val menuUpdate = listOf("hadiahpembaruan", "hadiahpembaharuan", "pembaruan", "pembaharuan")
    private val menuFeedback = listOf("masukanadvanceserver", "masukanadvance", "masukan")
    private val menuLogin = listOf("bonuslogin", "loginbonus")

    private fun claimEvents() {
        openEvent()

        if (openMenu(menuSupply) != null) {
            val n = claimLoop("Vanguard Coin Supply", 8)
            log("Vanguard Coin Supply selesai (" + n + " klaim)")
        } else log("Menu Vanguard Coin Supply tidak ada, lanjut")

        if (openMenu(menuUpdate) != null) {
            val n = claimLoop("Hadiah Pembaruan", 4)
            log("Hadiah Pembaruan selesai (" + n + " klaim)")
        } else log("Menu Hadiah Pembaruan tidak ada, lanjut")

        if (openMenu(menuFeedback) != null) {
            val s = cap()
            val g = s.exact("pergi")
            if (g != null) {
                tapLine(g, "Pergi")
                sleepS(3.0)
                back()
                sleepS(1.5)
                val s2 = cap()
                if (!eventOpen(s2)) {
                    log("Kembali dari Pergi, membuka Event lagi")
                    openEvent()
                    openMenu(menuFeedback)
                }
            }
            val n = claimLoop("Koin x20", 4)
            log("Masukan Advance Server selesai (" + n + " klaim)")
        } else log("Menu Masukan Advance Server tidak ada, lanjut")

        if (openMenu(menuLogin) != null) {
            val n = claimLoop("Bonus Login", 4)
            log("Bonus Login selesai (" + n + " klaim)")
        }

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
        status = "Klaim " + name
        log("===== SERVER " + name + " =====")
        claimEvents()
        done.add(name)
        Prefs.addDone(name)
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
        done.addAll(Prefs.doneToday())
        refreshCount()

        status = "Membuka game"
        log("Mulai klaim otomatis. Game: " + gamePkg)
        launch()
        sleepS(6.0)
        awaitLobby(240)
        settleLobby()

        status = "Membaca daftar server"
        val ls = openServerList()
        cur = readCurrent(ls)
        val want = Prefs.serverCount
        if (!Prefs.fast || want <= 0) {
            readAll(ls)
        }
        total = if (want > 0) want else (order.size + (if (cur != null && !order.contains(cur!!)) 1 else 0))
        if (want > 0 && order.isNotEmpty() && order.size != want) {
            log("Peringatan: pengaturan " + want + " server, terbaca " + order.size + ". Dilanjutkan.")
        }
        if (Prefs.skipAbove && order.isNotEmpty() && cur != null) {
            val i = order.indexOf(cur!!)
            if (i > 0) {
                for (k in 0 until i) skipped.add(order[k])
                log("Server di atas posisi sekarang dilewati: " + i)
            }
        }
        refreshCount()
        log("Server sekarang: " + (cur ?: "tidak terbaca") + ", total " + total)
        backToLobby()

        var guard = 0
        while (true) {
            alive()
            val c = cur
            if (c == null) {
                claimServer("saat-ini")
            } else if (!isDone(c)) {
                claimServer(c)
            }
            if (total > 0 && doneCount >= total) break
            val next = pickNext()
            if (next == null) {
                log("Tidak ada server berikutnya")
                backToLobby()
                break
            }
            switchTo(next)
            cur = next
            guard++
            if (guard > 80) throw Stop("Terlalu banyak server")
        }
        if (Prefs.closeGame) closeApp()
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
                log("DIAGNOSA: lobby=" + isLobby(s) + " event=" + eventOpen(s) + " daftarServer=" + isServerList(s))
                for (n in Matcher.names(ctx)) {
                    val h = Matcher.find(s, n)
                    log("  template " + n + ": " + (if (h == null) "tidak cocok" else "cocok " + (h.score * 100).toInt() + "%"))
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
