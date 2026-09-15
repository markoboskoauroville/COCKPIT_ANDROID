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

    /**
     * The dials switched OFF at the top of the phone, by key. What is off is what is stored
     * (design-language.md 7), so a dial added in a later version arrives live rather than
     * invisible to anybody whose choice predates it.
     *
     * getStringSet hands back a set the preferences still own, so it is copied on the way out and
     * on the way in: editing that set in place is a change that lives until the process dies and
     * then quietly does not.
     */
    var barOff: Set<String>
        get() = p.getStringSet(KEY_BAR_OFF, null)?.toSet() ?: DEFAULT_BAR_OFF
        set(v) = p.edit().putStringSet(KEY_BAR_OFF, v.toSet()).apply()

    /** Back to the way it shipped, in one press (design-language.md 7). */
    fun resetBar() = p.edit().remove(KEY_BAR_OFF).apply()

    val barIsDefault: Boolean get() = !p.contains(KEY_BAR_OFF)

    companion object {
        private const val KEY_BAR_OFF = "bar_off"

        /**
         * v1 put one number at the top and had no others. A phone upgrading from it must find the
         * bar it already had, so the three dials added in v2 start off — and a fresh install of v2
         * then matches an upgraded one exactly, which is the thing Test 4 checks.
         */
        val DEFAULT_BAR_OFF: Set<String> = setOf(Dial.DOWN.key, Dial.UP.key, Dial.WATTS.key, Dial.DRAW.key, Dial.CHARGE.key)
    }
}
