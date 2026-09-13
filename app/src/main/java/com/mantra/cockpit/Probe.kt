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
