package com.mantra.cockpit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * THE NUMBER IN THE STATUS BAR, AND THE PHONE'S HEALTH UNDER IT.
 *
 * A foreground service whose notification's small icon is the CPU percentage, redrawn every two
 * seconds while the screen is on. The notification's text is the headline (GHz, RAM, swap) and
 * its expanded form the rest: battery, thermal, uptime, storage, and the apps used most
 * recently. Three buttons, which is all a notification gets: the Termux cockpit page, the apps
 * screen of this app, and refresh.
 *
 * The clock STOPS on SCREEN_OFF and starts again on SCREEN_ON. A monitor that wakes the phone to
 * measure the phone is the drain it claims to watch; the number is for a person looking at the
 * bar, and nobody looks at a dark bar.
 */
class CockpitService : Service() {

    companion object {
        const val CHANNEL = "cockpit"
        /** The extra numbers ride on their own channel so they can be silenced separately. */
        const val BAR_CHANNEL = "cockpit_bar"
        const val NOTIFICATION_ID = Bar.FOREGROUND_ID
        const val ACTION_STOP = "com.mantra.cockpit.STOP"
        const val ACTION_REFRESH = "com.mantra.cockpit.REFRESH"
        const val TERMUX_COCKPIT = "http://127.0.0.1:8860/"
        const val PERIOD_MS = 2_000L
        @Volatile var latest: Sampler.Snapshot? = null
        @Volatile var running = false

        fun start(context: Context) {
            val i = Intent(context, CockpitService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CockpitService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sampler: Sampler
    private lateinit var store: Store
    private var screenOn = true
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (screenOn) handler.postDelayed(this, PERIOD_MS)
        }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; handler.removeCallbacks(tick) }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { if (!screenOn) { screenOn = true; handler.removeCallbacks(tick); handler.post(tick) } }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sampler = Sampler(this)
        store = Store(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cockpit", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The CPU number in the status bar and the phone's health under it"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(BAR_CHANNEL, "Cockpit numbers", NotificationManager.IMPORTANCE_MIN).apply {
            description = "One more number at the top of the phone: a notification carries one icon"
            setShowBadge(false)
        })
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            cancelExtras()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val first = build(latest, Bar.visible(store.barOff).firstOrNull())
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID, first)
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        cancelExtras()
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * ONE NUMBER IS ONE NOTIFICATION. The foreground one carries the first dial the person chose;
     * every other chosen dial gets its own, and every dial not chosen is cancelled on every tick.
     * Cancelling the unchosen ones is not tidiness: a notification nobody takes down stays at the
     * top of the phone after its switch is off, showing a number that has stopped being refreshed.
     */
    private fun refresh() {
        val snap = sampler.read(Bridge.instance)
        latest = snap
        val off = store.barOff
        val nm = getSystemService(NotificationManager::class.java)
        val visible = Bar.visible(off)
        nm.notify(NOTIFICATION_ID, build(snap, visible.firstOrNull()))
        for (dial in Bar.extras(off)) nm.notify(Bar.idOf(dial), oneNumber(snap, dial))
        for (id in Bar.stale(off)) nm.cancel(id)
    }

    private fun cancelExtras() {
        val nm = getSystemService(NotificationManager::class.java)
        for (dial in Dial.entries) nm.cancel(Bar.idOf(dial))
    }

    private fun glyphOf(s: Sampler.Snapshot?, dial: Dial): String =
        Bar.glyph(dial, s?.busy?.pct, s?.net?.downBps, s?.net?.upBps, s?.watts)

    private fun lineOf(s: Sampler.Snapshot?, dial: Dial): String =
        Bar.line(dial, s?.busy?.pct, s?.net?.downBps, s?.net?.upBps, s?.watts)

    /** One of the extra numbers: its glyph at the top, its own line under it, and nothing else. */
    private fun oneNumber(s: Sampler.Snapshot?, dial: Dial): Notification =
        NotificationCompat.Builder(this, BAR_CHANNEL)
            .setSmallIcon(NumberIcon.of(glyphOf(s, dial)))
            .setContentTitle(lineOf(s, dial))
            .setContentText(dial.caption)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pending(Intent(this, MainActivity::class.java), 1))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun pending(intent: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * The notification, from a snapshot (or the waiting one before the first reading). Its icon is
     * the first dial the person chose; with every dial switched off it is a dash, because the
     * service must keep a notification for as long as it runs and an empty one would be a lie
     * about there being nothing to read.
     */
    fun build(s: Sampler.Snapshot?, lead: Dial? = Dial.CPU): Notification {
        val open = pending(Intent(this, MainActivity::class.java), 1)
        val page = pending(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_COCKPIT)), 2)
        val apps = pending(Intent(this, MainActivity::class.java).putExtra("show", "apps"), 3)
        val refreshPi = PendingIntent.getService(this, 4, Intent(this, CockpitService::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (s == null) "Cockpit is reading the phone" else Probe.headline(s.busy, s.clusters, s.memory)
        val lines = ArrayList<String>()
        if (s != null) {
            val m = s.memory
            if (m != null) lines.add("RAM ${m.usedMb} of ${m.totalMb} MB used, ${m.availableMb} MB available" + (if (s.lowMemory) " · LOW" else "") + (if (m.swapTotalMb > 0) " · swap ${m.swapPct} %" else ""))
            if (s.batteryPct != null) lines.add("battery ${s.batteryPct} %" + (s.batteryTempC?.let { " · $it °C" } ?: "") + (if (s.batteryCharging) " · charging" else "") + " · thermal ${s.thermalName}")
            lines.add("up ${Probe.duration(s.uptimeS)} · storage ${String.format(java.util.Locale.US, "%.1f", s.storageFreeGb)} GB free" + (s.shellCpu?.let { " · shell says $it % cpu" } ?: ""))
            lines.add("down " + Probe.perSecond(s.net?.downBps) + " · up " + Probe.perSecond(s.net?.upBps) + " · " + Probe.wattsText(s.watts))
            if (s.busy != null) lines.add("cores " + s.busy.perCore.joinToString(" ") { "$it" })
            if (s.recent.isNotEmpty()) lines.add("recent: " + s.recent.take(5).joinToString(", ") { "${it.label} ${ago(it.lastUsedAgoS)}" })
            else if (!s.usageAccess) lines.add("recent apps: give Cockpit Usage access in Settings")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(NumberIcon.of(if (lead == null) "–" else glyphOf(s, lead)))
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "the first reading takes two seconds")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Cockpit page", page)
            .addAction(0, "Apps", apps)
            .addAction(0, "Refresh", refreshPi)
            .build()
    }

    private fun ago(s: Long): String = when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}
