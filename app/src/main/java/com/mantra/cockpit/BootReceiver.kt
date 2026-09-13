package com.mantra.cockpit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The number comes back after a reboot, when the person had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store(context).autostart) CockpitService.start(context)
    }
}
