package com.mantra.cockpit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/** The AGY palette (design-language.md 3): dark, amber, sand; colour is the state channel. */
object Palette {
    val bg = Color(0xFF0B0D10)
    val surface = Color(0xFF141A21)
    val line = Color(0xFF23303D)
    val amber = Color(0xFFF59E0B)
    val sand = Color(0xFFF2DDB4)
    val dim = Color(0xFF8A7A5C)
    val red = Color(0xFFEF4444)
    val green = Color(0xFF22C55E)
}

/**
 * THE SCREEN: the same dials as the Termux page, the apps used recently with a stop on each,
 * and the three permissions this app needs a person to grant, each with the button that opens
 * the screen where it is granted. Nothing appears or disappears: a dial with nothing to say is
 * dimmed, a button that cannot act is dimmed, and the reason is written next to it.
 */
class MainActivity : ComponentActivity() {

    private val bridge by lazy { Bridge.get(this) }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result -> bridge.onPermissionResult(code, result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        val store = Store(this)
        setContent { Screen(store) }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    @Composable
    private fun Screen(store: Store) {
        var snap by remember { mutableStateOf<Sampler.Snapshot?>(null) }
        var serviceOn by remember { mutableStateOf(CockpitService.running) }
        var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
        var status by remember { mutableStateOf("") }
        var tickVersion by remember { mutableStateOf(0) }
        val sampler = remember { Sampler(this) }
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) { store.serviceWanted = true; CockpitService.start(this); serviceOn = true }
        }

        LaunchedEffect(Unit) {
            while (true) {
                snap = CockpitService.latest ?: sampler.read(bridge)
                serviceOn = CockpitService.running
                if (bridge.granted && !bridge.ready) bridge.bind()
                tickVersion += 1
                delay(2000)
            }
        }

        val s = snap
        val notifOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(buildString { append("COCK"); append("PIT") }, color = Palette.sand, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                    Text(Updates.describe(update, BuildConfig.VERSION_CODE) + " · " + (if (s != null) "read " + ago(s.at) else "reading"), color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { UpdateCheck.check(BuildConfig.VERSION_CODE) { st -> update = st; (st as? UpdateState.Available)?.let { UpdateCheck.download(this@MainActivity, it.url) } } })
                }
            }
            item { Heading("1 · the number in the bar") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(if (serviceOn) "number is on · stop it" else "put the number in the status bar", serviceOn, Modifier.weight(1f)) {
                        if (serviceOn) { store.serviceWanted = false; CockpitService.stop(this@MainActivity); serviceOn = false }
                        else if (notifOk) { store.serviceWanted = true; CockpitService.start(this@MainActivity); serviceOn = true }
                        else askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                Text(if (notifOk) "every two seconds while the screen is on; it sleeps with the screen" else "Android 13+: allow notifications first (the button asks)", color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            item { Heading("2 · now" + (s?.let { "  ·  up " + Probe.duration(it.uptimeS) } ?: "")) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dial("% cpu", s?.busy?.pct?.toString(), s?.busy?.let { b -> "cores " + b.perCore.joinToString(" ") + (s.shellCpu?.let { "\nshell says $it %" } ?: "") } ?: "the idle counters of every core", s?.busy?.pct, (s?.busy?.pct ?: 0) >= 90, Modifier.weight(1f))
                    val top = s?.clusters?.maxByOrNull { it.curMhz }
                    Dial("GHz", top?.let { String.format(java.util.Locale.US, "%.2f", it.curMhz / 1000.0) }, s?.clusters?.joinToString(" · ") { "${it.name.replace("policy", "c")} ${String.format(java.util.Locale.US, "%.1f", it.curMhz / 1000.0)}/${String.format(java.util.Locale.US, "%.1f", it.maxMhz / 1000.0)}" } ?: "clusters", top?.pct, false, Modifier.weight(1f))
                }
            }
            item {
                val m = s?.memory
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dial("% ram", m?.usedPct?.toString(), m?.let { "${String.format(java.util.Locale.US, "%.1f", it.usedMb / 1024.0)} of ${String.format(java.util.Locale.US, "%.1f", it.totalMb / 1024.0)} GB · ${it.availableMb} MB available" + (if (s.lowMemory) " · LOW" else "") } ?: "used of total", m?.usedPct, s?.lowMemory == true, Modifier.weight(1f))
                    Dial("% swap", m?.let { if (it.swapTotalMb > 0) it.swapPct.toString() else "0" }, m?.let { if (it.swapTotalMb > 0) "${String.format(java.util.Locale.US, "%.1f", it.swapUsedMb / 1024.0)} of ${String.format(java.util.Locale.US, "%.1f", it.swapTotalMb / 1024.0)} GB" else "no swap" } ?: "swap", m?.swapPct, (m?.swapPct ?: 0) > 90, Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dial("% battery", s?.batteryPct?.toString(), s?.let { (it.batteryTempC?.let { t -> "$t °C · " } ?: "") + (if (it.batteryCharging) "charging" else "discharging") } ?: "battery", s?.batteryPct, (s?.batteryPct ?: 100) < 15, Modifier.weight(1f))
                    Dial("thermal", s?.thermalName, s?.let { "status ${it.thermal ?: "?"} of 6" } ?: "PowerManager", s?.thermal?.let { it * 100 / 6 }, (s?.thermal ?: 0) >= 3, Modifier.weight(1f))
                }
            }
            item {
                Dial("GB free", s?.let { String.format(java.util.Locale.US, "%.1f", it.storageFreeGb) }, s?.let { "of ${String.format(java.util.Locale.US, "%.1f", it.storageTotalGb)} GB" } ?: "storage", s?.let { (100 * (it.storageTotalGb - it.storageFreeGb) / it.storageTotalGb).toInt() }, (s?.storageFreeGb ?: 10.0) < 2, Modifier.fillMaxWidth())
            }
            item { Heading("3 · in memory") }
            item {
                Text(bridge.describe(), color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(if (bridge.ready) "shizuku: ready" else "ask shizuku", bridge.alive && !bridge.ready, Modifier.weight(1f)) { bridge.request(); status = bridge.describe() }
                    Button("kill every background process", bridge.ready, Modifier.weight(1f), danger = true) { status = if (bridge.killAll()) "every background process was told to go" else "the shell did not answer" }
                }
                if (!(s?.usageAccess ?: false)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Button("give Cockpit Usage access (to list recent apps)", true, Modifier.weight(1f)) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    }
                }
                if (status.isNotEmpty()) Text(status, color = Palette.green, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
            val procs = s?.shellProcs ?: emptyList()
            if (procs.isNotEmpty()) {
                item { Text("${procs.size} processes in memory (dumpsys meminfo, via the shell)", color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                items(procs.take(40)) { p ->
                    AppRow("${p.pssMb} MB", p.name, "pid ${p.pid}" + (if (p.note.isNotEmpty()) " · ${p.note}" else ""), true) {
                        status = if (bridge.forceStop(p.name.substringBefore(":"))) "stopped ${p.name}" else "could not stop ${p.name}"
                    }
                }
            } else {
                val recent = s?.recent ?: emptyList()
                item { Text(if (recent.isEmpty()) (if (s?.usageAccess == true) "no app used in the last twelve hours" else "apps used recently need Usage access; every process needs Shizuku") else "${recent.size} apps used recently · stop opens App Info (Force stop is there)" + (if (bridge.ready) ", or stops through the shell" else ""), color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                items(recent) { r ->
                    AppRow("${r.foregroundMinutes} min", r.label, "${r.pkg} · used ${ago(System.currentTimeMillis() - r.lastUsedAgoS * 1000)}", true) {
                        if (bridge.ready) status = if (bridge.forceStop(r.pkg)) "stopped ${r.label}" else "could not stop ${r.label}"
                        else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
                    }
                }
            }
            item { Heading("4 · the rest of the cockpit") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("open the Termux cockpit page", true, Modifier.weight(1f)) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CockpitService.TERMUX_COCKPIT))) }
                    Button("battery: unrestricted", true, Modifier.weight(1f)) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                Text("the Termux page (cockpit, 127.0.0.1:8860) shows every process and stops them over the ADB bridge; this app's number and its RAM figures come from the same counters, so the two agree. Battery: set Cockpit and Termux to Unrestricted or Android puts the number to sleep. Autostart after a reboot: ${if (store.autostart) "on" else "off"} (tap to change).", color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp).clickable { store.autostart = !store.autostart; tickVersion += 1 })
                if (tickVersion < 0) Text("")   // keeps the recomposition key in use
            }
        }
    }

    @Composable
    private fun Heading(text: String) {
        Text(text.uppercase(), color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 10.dp))
    }

    @Composable
    private fun Dial(unit: String, value: String?, caption: String, pct: Int?, hot: Boolean, modifier: Modifier) {
        val on = value != null
        Column(modifier.background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value ?: "–", color = if (on) Palette.sand else Palette.dim, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                Text(unit, color = Palette.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
            }
            Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 0.dp).background(Palette.line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(((pct ?: 0).coerceIn(0, 100)) / 100f).height(6.dp).background(if (hot) Palette.red else Palette.amber, RoundedCornerShape(3.dp)))
            }
            Text(caption, color = Palette.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun Button(label: String, enabled: Boolean, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
        Box(
            modifier.background(Palette.surface, RoundedCornerShape(999.dp)).clickable(enabled = enabled) { onClick() }.padding(vertical = 11.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (!enabled) Palette.dim else if (danger) Palette.red else Palette.sand, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun AppRow(figure: String, name: String, detail: String, canStop: Boolean, onStop: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(figure, color = Palette.sand, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Palette.sand, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(detail, color = Palette.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Button("stop", canStop, Modifier, danger = true, onClick = onStop)
        }
    }

    private fun ago(atMillis: Long): String {
        val s = (System.currentTimeMillis() - atMillis) / 1000
        return when { s < 3 -> "now"; s < 90 -> "$s s ago"; s < 5400 -> "${s / 60} min ago"; else -> "${s / 3600} h ago" }
    }
}
