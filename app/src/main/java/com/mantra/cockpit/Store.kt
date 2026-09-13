package com.mantra.cockpit

import android.content.Context

/** What the person chose, in one place. Store what is switched OFF where a default is on. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("cockpit", Context.MODE_PRIVATE)
    var autostart: Boolean
        get() = p.getBoolean("autostart", true)
        set(v) = p.edit().putBoolean("autostart", v).apply()
    var serviceWanted: Boolean
        get() = p.getBoolean("service", false)
        set(v) = p.edit().putBoolean("service", v).apply()
}
