package com.mantra.cockpit

import java.io.File

/**
 * THE READERS, AND THEY IMPORT NOTHING FROM ANDROID.
 *
 * Every number the status bar and the notification show comes through here, from files an app
 * is allowed to read on this phone (measured 13.9.2026, Nothing Phone (2a), Android 16, from
 * Termux's own shell): /sys/devices/system/cpu/cpu<n>/cpuidle/state<n>/time, cpufreq, /proc/meminfo.
 * NOT /proc/stat, /proc/loadavg or /proc/uptime: an app is denied those. So the CPU number is the
 * idle counters: busy = 1 - (idle gained / wall time) per core, which is /proc/stat's arithmetic
 * from the other side of the ledger. COCKPIT_TERMUX/probe.py does the same sum in Python, so the
 * two numbers agree.
 *
 * Pure Kotlin over java.io, with the root injectable, so ProbeTest attacks every parser and every
 * piece of arithmetic on a desk with no phone: a fake sysfs tree, a captured /proc/meminfo.
 */
object Probe {

    const val CPU_ROOT = "/sys/devices/system/cpu"
    const val MEMINFO = "/proc/meminfo"

    data class CpuSample(val nanos: Long, val idleUs: Map<Int, Long>)
    data class Busy(val pct: Int, val perCore: List<Int>)
    data class Cluster(val name: String, val cpus: List<Int>, val curMhz: Int, val maxMhz: Int) {
        val pct: Int? get() = if (curMhz > 0 && maxMhz > 0) (100L * curMhz / maxMhz).toInt() else null
    }
    data class Memory(val totalMb: Int, val usedMb: Int, val availableMb: Int, val cachedMb: Int, val swapTotalMb: Int, val swapUsedMb: Int) {
        val usedPct: Int get() = if (totalMb > 0) (100L * usedMb / totalMb).toInt() else 0
        val swapPct: Int get() = if (swapTotalMb > 0) (100L * swapUsedMb / swapTotalMb).toInt() else 0
    }

    private fun read(path: String): String? = try {
        File(path).readText()
    } catch (e: Exception) {
        null
    }

    private fun readLong(path: String): Long? = read(path)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 18 && it.all { c -> c.isDigit() } }?.toLong()

    /** "0-7" -> 0..7; "0-3,6-7"; "0 1 2" (related_cpus); garbage -> empty. */
    fun parseCpuList(text: String?): List<Int> {
        val out = sortedSetOf<Int>()
        for (part in (text ?: "").trim().split(Regex("[,\\s]+"))) {
            if (part.isEmpty()) continue
            val range = part.split("-")
            if (range.size == 2 && range[0].all { it.isDigit() } && range[1].all { it.isDigit() } && range[0].isNotEmpty() && range[1].isNotEmpty()) {
                val a = range[0].toInt(); val b = range[1].toInt()
                if (a <= b && b - a < 1024) for (i in a..b) out.add(i)
            } else if (part.all { it.isDigit() }) {
                out.add(part.toInt())
            }
        }
        return out.toList()
    }

    fun cpus(root: String = CPU_ROOT): List<Int> {
        val online = parseCpuList(read("$root/online"))
        if (online.isNotEmpty()) return online
        val dir = File(root).listFiles() ?: return emptyList()
        return dir.mapNotNull { f -> Regex("cpu(\\d+)").matchEntire(f.name)?.groupValues?.get(1)?.toInt() }.sorted()
    }

    /** Microseconds this core spent in any idle state since boot, or null when unreadable. */
    fun idleUs(cpu: Int, root: String = CPU_ROOT): Long? {
        val dir = File("$root/cpu$cpu/cpuidle").listFiles() ?: return null
        var total = 0L
        var seen = false
        for (st in dir) {
            if (!Regex("state\\d+").matches(st.name)) continue
            val v = readLong("${st.path}/time") ?: continue
            total += v
            seen = true
        }
        return if (seen) total else null
    }

    fun sample(nanos: Long, root: String = CPU_ROOT): CpuSample =
        CpuSample(nanos, cpus(root).mapNotNull { c -> idleUs(c, root)?.let { c to it } }.toMap())

    /**
     * Two samples -> the number. Clamped to 0..100: a counter can run a little ahead of the wall
     * clock at the edges of a tick, and 103 % is not a number to show. A core whose counter
     * vanished between the two readings is left out; no cores at all, or no time passed, is null.
     */
    fun busy(prev: CpuSample?, now: CpuSample?): Busy? {
        if (prev == null || now == null) return null
        val dtUs = (now.nanos - prev.nanos) / 1000.0
        if (dtUs <= 0) return null
        val per = now.idleUs.toSortedMap().mapNotNull { (c, v) ->
            val p = prev.idleUs[c] ?: return@mapNotNull null
            val b = 1.0 - (v - p) / dtUs
            (b.coerceIn(0.0, 1.0) * 100).toInt()
        }
        if (per.isEmpty()) return null
        return Busy(Math.round(per.average()).toInt(), per)
    }

    /** Per cluster (cpufreq policy): which cores, current and highest MHz. */
    fun clusters(root: String = CPU_ROOT): List<Cluster> {
        val pol = File("$root/cpufreq").listFiles()?.filter { Regex("policy\\d+").matches(it.name) }?.sortedBy { it.name } ?: emptyList()
        return pol.map { p ->
            Cluster(p.name, parseCpuList(read("${p.path}/related_cpus")),
                ((readLong("${p.path}/scaling_cur_freq") ?: 0L) / 1000).toInt(),
                ((readLong("${p.path}/cpuinfo_max_freq") ?: 0L) / 1000).toInt())
        }
    }

    /** The kB numbers of /proc/meminfo by key; a line with a digit run over 18 long is not a number. */
    fun parseMeminfo(text: String?): Map<String, Long> {
        val out = HashMap<String, Long>()
        val re = Regex("^([A-Za-z_()0-9]+):\\s+(\\d{1,18})(?:\\s+kB)?\\s*$")
        for (line in (text ?: "").lineSequence()) {
            val m = re.matchEntire(line) ?: continue
            out[m.groupValues[1]] = m.groupValues[2].toLong()
        }
        return out
    }

    fun memory(text: String? = read(MEMINFO)): Memory? {
        val kb = parseMeminfo(text)
        val total = kb["MemTotal"] ?: return null
        if (total <= 0) return null
        val avail = kb["MemAvailable"] ?: kb["MemFree"] ?: 0L
        val st = kb["SwapTotal"] ?: 0L
        val sf = kb["SwapFree"] ?: 0L
        return Memory((total / 1024).toInt(), ((total - avail) / 1024).toInt(), (avail / 1024).toInt(), ((kb["Cached"] ?: 0L) / 1024).toInt(), (st / 1024).toInt(), ((st - sf) / 1024).toInt())
    }

    /** One line for the status bar's neighbour and the console: "37 % cpu · 2.0 GHz · RAM 5.0/7.4 GB". */
    fun headline(busy: Busy?, clusters: List<Cluster>, mem: Memory?): String {
        val parts = ArrayList<String>()
        parts.add(if (busy != null) "${busy.pct} % cpu" else "cpu –")
        val top = clusters.maxByOrNull { it.curMhz }
        if (top != null && top.curMhz > 0) parts.add(String.format(java.util.Locale.US, "%.1f GHz", top.curMhz / 1000.0))
        if (mem != null) parts.add(String.format(java.util.Locale.US, "RAM %.1f/%.1f GB", mem.usedMb / 1024.0, mem.totalMb / 1024.0))
        if (mem != null && mem.swapTotalMb > 0) parts.add(String.format(java.util.Locale.US, "swap %.1f GB", mem.swapUsedMb / 1024.0))
        return parts.joinToString(" · ")
    }

    /** "1d 3h 12m" from seconds. */
    fun duration(seconds: Long): String {
        val d = seconds / 86400; val h = seconds % 86400 / 3600; val m = seconds % 3600 / 60
        return (if (d > 0) "${d}d " else "") + "${h}h ${m}m"
    }

    // ------------------------------------------------------------------ THE LINE'S SPEED
    //
    // Two readings and a subtraction, the same shape as the idle counters above, from the byte
    // totals the system keeps since boot. /proc/net/dev is the file that looks obvious and it is
    // not readable by an app on this phone; TrafficStats is. Its totals are monotonic until a
    // reboot, and it answers -1 (UNSUPPORTED) on a device that does not keep them, so both of
    // those are a null rate rather than an enormous one.

    data class NetSample(val nanos: Long, val rxBytes: Long, val txBytes: Long)
    data class NetRate(val downBps: Long, val upBps: Long)

    fun rate(prev: NetSample?, now: NetSample?): NetRate? {
        if (prev == null || now == null) return null
        if (prev.rxBytes < 0 || prev.txBytes < 0 || now.rxBytes < 0 || now.txBytes < 0) return null
        if (now.rxBytes < prev.rxBytes || now.txBytes < prev.txBytes) return null
        val dtNs = now.nanos - prev.nanos
        if (dtNs <= 0) return null
        val secs = dtNs / 1_000_000_000.0
        return NetRate(Math.round((now.rxBytes - prev.rxBytes) / secs), Math.round((now.txBytes - prev.txBytes) / secs))
    }

    /** For the notification, where there is room for the unit: "0 B/s", "812 kB/s", "1.4 MB/s". */
    fun perSecond(bps: Long?): String = when {
        bps == null -> "–"
        bps < 1_000 -> "$bps B/s"
        bps < 999_500 -> "${(bps + 500) / 1_000} kB/s"
        else -> String.format(java.util.Locale.US, "%.1f MB/s", bps / 1e6)
    }

    // ------------------------------------------------------------------ THE WATTS
    //
    // Volts times amps, and the trap is that phones disagree about the units of both. The current
    // property is documented in microamps and several makers answer in milliamps under that name;
    // the voltage extra is documented in millivolts and some answer in microvolts. So neither
    // reading is trusted on its own: every plausible reading of the units is computed, and the
    // answer is given ONLY when exactly one of them lands in a band a phone can actually be in.
    // Two candidates in the band, or none, is a dash — a wrong number said confidently is worse
    // than no number, and this one would be read as a fact about a charger.
    //
    // The sign is NOT taken from the current's own sign, because that is the other thing makers
    // invert. It comes from the battery status, which is not ambiguous: charging is positive,
    // anything else is the draw.

    const val WATTS_MIN = 0.05
    const val WATTS_MAX_DRAW = 20.0
    const val WATTS_MAX_CHARGE = 150.0

    /** Millivolts, microvolts or whole volts, each recognised by the band it falls in. */
    fun volts(reported: Int?): Double? = when {
        reported == null -> null
        reported in 2_000..20_000 -> reported / 1_000.0
        reported in 2_000_000..20_000_000 -> reported / 1_000_000.0
        reported in 2..20 -> reported.toDouble()
        else -> null
    }

    fun watts(rawCurrent: Long?, reportedVoltage: Int?, charging: Boolean): Double? {
        if (rawCurrent == null || rawCurrent == 0L || rawCurrent == Long.MIN_VALUE) return null
        val v = volts(reportedVoltage) ?: return null
        val amps = Math.abs(rawCurrent).toDouble()
        val ceiling = if (charging) WATTS_MAX_CHARGE else WATTS_MAX_DRAW
        val candidates = listOf(v * amps / 1_000_000.0, v * amps / 1_000.0).filter { it >= WATTS_MIN && it <= ceiling }
        if (candidates.size != 1) return null
        return if (charging) candidates[0] else -candidates[0]
    }

    /** For the notification: "charging 23.4 W", "drawing 4.1 W", "no reading". */
    fun wattsText(w: Double?): String = when {
        w == null -> "watts: no reading this phone agrees with"
        w >= 0 -> String.format(java.util.Locale.US, "charging %.1f W", w)
        else -> String.format(java.util.Locale.US, "drawing %.1f W", -w)
    }

    // ------------------------------------------------------------------ THE GLYPH AT THE TOP
    //
    // A status-bar icon is a square a few millimetres across. Three characters is what fits and
    // stays legible (android-app.md 4): more than three and the bar shows a smudge. So every
    // number that can go up there passes through here, and Test 1 asserts the length at every
    // magnitude rather than trusting the formatting to be reasonable.

    const val BAR_GLYPHS = 3

    private fun short(value: Double): String = when {
        value >= 99.5 -> "99+"
        value >= 9.95 -> Math.round(value).toString()
        else -> String.format(java.util.Locale.US, "%.1f", value)
    }

    /** Megabytes a second, always, so two numbers at the top can be compared without a unit. */
    fun barRate(bps: Long?): String = if (bps == null) "–" else short(bps / 1e6)

    /** The magnitude only: the phone's own charging bolt, one icon away, says which way. */
    fun barWatts(w: Double?): String = if (w == null) "–" else short(Math.abs(w))
}

/**
 * THE SHELL'S TEXT INTO NUMBERS, for what only the shell user can read (over Shizuku).
 * Pure Kotlin, the same parsers as COCKPIT_TERMUX/bridge.py, tested on the same captured output.
 */
object ShellText {

    data class Proc(val pssMb: Int, val name: String, val pid: Int, val note: String)

    /** The 'Total PSS by process' block of `dumpsys meminfo`, biggest first. */
    fun meminfoProcesses(text: String?): List<Proc> {
        val out = ArrayList<Proc>()
        var inBlock = false
        val re = Regex("([\\d,]+)K:\\s+(\\S+)\\s+\\(pid\\s+(\\d+)(?:\\s*/\\s*([^)]*))?\\)")
        for (raw in (text ?: "").lineSequence()) {
            val s = raw.trim()
            if (s.startsWith("Total PSS by process")) { inBlock = true; continue }
            if (!inBlock) continue
            if (s.isEmpty()) { inBlock = false; continue }
            val m = re.find(s) ?: continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            out.add(Proc((kb / 1024).toInt(), m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].trim()))
        }
        return out.sortedByDescending { it.pssMb }
    }

    /** The first line of /proc/stat -> busy jiffies to total jiffies, or null. */
    fun procStat(text: String?): Pair<Long, Long>? {
        val line = (text ?: "").lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val nums = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val idle = nums[3] + (if (nums.size > 4) nums[4] else 0L)
        return Pair(nums.sum() - idle, nums.sum())
    }

    fun cpuFromStat(prev: Pair<Long, Long>?, now: Pair<Long, Long>?): Int? {
        if (prev == null || now == null) return null
        val dt = now.second - prev.second
        if (dt <= 0) return null
        return (100L * (now.first - prev.first) / dt).toInt().coerceIn(0, 100)
    }

    /** A package name by shape, before anything is handed to a shell. */
    val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
}
