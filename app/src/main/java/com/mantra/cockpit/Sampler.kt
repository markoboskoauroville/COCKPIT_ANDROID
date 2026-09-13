package com.mantra.cockpit

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs

/**
 * ONE SNAPSHOT OF THE PHONE, read on demand by the service and the screen.
 *
 * The idle counters need two readings; the sampler keeps the previous one. Everything else is
 * read fresh each time it is asked, which is every two seconds while the screen is on and never
 * while it is off (the service stops its clock on SCREEN_OFF: a monitor that wakes the phone to
 * measure the phone is the drain it claims to watch).
 */
class Sampler(private val context: Context) {

    data class RecentApp(val pkg: String, val label: String, val lastUsedAgoS: Long, val foregroundMinutes: Long)

    data class Snapshot(
        val at: Long,
        val busy: Probe.Busy?,
        val clusters: List<Probe.Cluster>,
        val memory: Probe.Memory?,
        val lowMemory: Boolean,
        val batteryPct: Int?,
        val batteryTempC: Float?,
        val batteryCharging: Boolean,
        val thermal: Int?,
        val uptimeS: Long,
        val storageFreeGb: Double,
        val storageTotalGb: Double,
        val recent: List<RecentApp>,
        val usageAccess: Boolean,
        val shellCpu: Int?,
        val shellProcs: List<ShellText.Proc>,
    ) {
        val thermalName: String get() = when (thermal) { null -> "?"; 0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; else -> "shutdown" }
    }

    private var prev: Probe.CpuSample? = null
    private var prevStat: Pair<Long, Long>? = null
    private var lastRecent: List<RecentApp> = emptyList()
    private var lastRecentAt = 0L
    private var lastProcs: List<ShellText.Proc> = emptyList()
    private var lastProcsAt = 0L

    fun read(bridge: Bridge? = null): Snapshot {
        val now = Probe.sample(SystemClock.elapsedRealtimeNanos())
        val busy = Probe.busy(prev, now)
        prev = now
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val mem = Probe.memory() ?: Probe.Memory((mi.totalMem / 1048576).toInt(), ((mi.totalMem - mi.availMem) / 1048576).toInt(), (mi.availMem / 1048576).toInt(), 0, 0, 0)
        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) 100 * level / scale else null
        val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10f }
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else null
        val st = StatFs(context.filesDir.path)
        val t = System.currentTimeMillis()
        if (t - lastRecentAt > 30_000) { lastRecent = recentApps(); lastRecentAt = t }
        var shellCpu: Int? = null
        if (bridge != null && bridge.ready && t - lastProcsAt > 8_000) {
            val stat = ShellText.procStat(bridge.run("cat /proc/stat", 3_000))
            shellCpu = ShellText.cpuFromStat(prevStat, stat)
            prevStat = stat
            lastProcs = ShellText.meminfoProcesses(bridge.run("dumpsys meminfo", 20_000))
            lastProcsAt = t
        }
        return Snapshot(
            at = t, busy = busy, clusters = Probe.clusters(), memory = mem, lowMemory = mi.lowMemory,
            batteryPct = pct, batteryTempC = temp, batteryCharging = charging, thermal = thermal,
            uptimeS = SystemClock.elapsedRealtime() / 1000,
            storageFreeGb = st.availableBytes / 1e9, storageTotalGb = st.totalBytes / 1e9,
            recent = lastRecent, usageAccess = hasUsageAccess(), shellCpu = shellCpu, shellProcs = lastProcs,
        )
    }

    /** Whether the person granted Usage access in Settings. Asked of the system, never assumed. */
    fun hasUsageAccess(): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 24 * 3600_000L, end)
        return !stats.isNullOrEmpty()
    }

    /**
     * THE APPS THAT WERE USED, most recent first: the only list of "what is in memory" an app may
     * make on Android 14 or newer without the shell's rights. Foreground minutes over the last
     * day; last used from the same stats. Launchers and this app are left out.
     */
    fun recentApps(limit: Int = 12): List<RecentApp> {
        if (!hasUsageAccess()) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 24 * 3600_000L, end) ?: return emptyList()
        val pm = context.packageManager
        val byPkg = HashMap<String, Pair<Long, Long>>()   // pkg -> (lastUsed, foregroundMs)
        for (s in stats) {
            val cur = byPkg[s.packageName]
            byPkg[s.packageName] = Pair(maxOf(cur?.first ?: 0L, s.lastTimeUsed), (cur?.second ?: 0L) + s.totalTimeInForeground)
        }
        return byPkg.entries
            .filter { it.value.first > end - 12 * 3600_000L && it.key != context.packageName }
            .sortedByDescending { it.value.first }
            .mapNotNull { (pkg, v) ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { return@mapNotNull null }
                RecentApp(pkg, label, (end - v.first) / 1000, v.second / 60_000)
            }
            .take(limit)
    }
}
