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
        const val NOTIFICATION_ID = 1
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cockpit", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The CPU number in the status bar and the phone's health under it"
            setShowBadge(false)
        })
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val first = build(latest)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID, first)
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        val snap = sampler.read(Bridge.instance)
        latest = snap
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(snap))
    }

    private fun pending(intent: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The notification, from a snapshot (or the waiting one before the first reading). */
    fun build(s: Sampler.Snapshot?): Notification {
        val open = pending(Intent(this, MainActivity::class.java), 1)
        val page = pending(Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_COCKPIT)), 2)
        val apps = pending(Intent(this, MainActivity::class.java).putExtra("show", "apps"), 3)
        val refreshPi = PendingIntent.getService(this, 4, Intent(this, CockpitService::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pct = s?.busy?.pct
        val title = if (s == null) "Cockpit is reading the phone" else Probe.headline(s.busy, s.clusters, s.memory)
        val lines = ArrayList<String>()
        if (s != null) {
            val m = s.memory
            if (m != null) lines.add("RAM ${m.usedMb} of ${m.totalMb} MB used, ${m.availableMb} MB available" + (if (s.lowMemory) " · LOW" else "") + (if (m.swapTotalMb > 0) " · swap ${m.swapPct} %" else ""))
            if (s.batteryPct != null) lines.add("battery ${s.batteryPct} %" + (s.batteryTempC?.let { " · $it °C" } ?: "") + (if (s.batteryCharging) " · charging" else "") + " · thermal ${s.thermalName}")
            lines.add("up ${Probe.duration(s.uptimeS)} · storage ${String.format(java.util.Locale.US, "%.1f", s.storageFreeGb)} GB free" + (s.shellCpu?.let { " · shell says $it % cpu" } ?: ""))
            if (s.busy != null) lines.add("cores " + s.busy.perCore.joinToString(" ") { "$it" })
            if (s.recent.isNotEmpty()) lines.add("recent: " + s.recent.take(5).joinToString(", ") { "${it.label} ${ago(it.lastUsedAgoS)}" })
            else if (!s.usageAccess) lines.add("recent apps: give Cockpit Usage access in Settings")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(NumberIcon.of(pct))
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
