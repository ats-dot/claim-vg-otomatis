package id.pribadi.vgklaim

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val buf = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(m: String) {
        buf.add(fmt.format(Date()) + " " + m)
        while (buf.size > 1200) buf.removeAt(0)
    }

    @Synchronized
    fun text(): String = if (buf.isEmpty()) "Belum ada log." else buf.joinToString("\n")

    @Synchronized
    fun clear() {
        buf.clear()
    }
}
