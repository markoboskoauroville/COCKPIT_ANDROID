package com.mantra.cockpit

/**
 * WHICH NUMBERS SIT AT THE TOP OF THE PHONE.
 *
 * A notification carries one small icon, so one number at the top is one notification. The
 * foreground one the service must keep carries the FIRST chosen number; every other chosen number
 * gets its own, quiet, on a separate channel. The ones not chosen are cancelled every tick, because
 * a notification nobody cancels stays at the top after its dial is switched off, which is exactly
 * the kind of failure that makes no noise (silent-failure.md).
 *
 * This file imports nothing from Android, like Probe.kt, so the choosing and the glyphs are tested
 * on a desk: ProbeTest walks every combination rather than sampling it.
 */
enum class Dial(val key: String, val label: String, val unit: String, val caption: String) {
    CPU("cpu", "processor", "% cpu", "the idle counters of every core"),
    DOWN("down", "download", "MB/s down", "bytes in, since the last reading"),
    UP("up", "upload", "MB/s up", "bytes out, since the last reading"),
    WATTS("watts", "power", "watts", "volts times amps, charging or drawing"),
    ;

    companion object {
        fun byKey(key: String): Dial? = entries.firstOrNull { it.key == key }
    }
}

object Bar {

    /** The service's own notification. It exists for as long as the service does. */
    const val FOREGROUND_ID = 1

    /** Every other dial's notification, well clear of the foreground one. */
    fun idOf(dial: Dial): Int = 10 + dial.ordinal

    /**
     * What is switched OFF is what is stored (design-language.md 7), so a dial added in a later
     * version is live for somebody whose choice predates it rather than invisible to them.
     */
    fun visible(hidden: Set<String>): List<Dial> = Dial.entries.filter { it.key !in hidden }

    /** The first visible dial rides on the foreground notification; these get their own. */
    fun extras(hidden: Set<String>): List<Dial> = visible(hidden).drop(1)

    /** Every id that must NOT be showing now, including the lead dial's unused own id. */
    fun stale(hidden: Set<String>): List<Int> {
        val posted = extras(hidden).toSet()
        return Dial.entries.filter { it !in posted }.map { idOf(it) }
    }

    /** The three characters for the bar. Never longer: Probe.short is the only writer of them. */
    fun glyph(dial: Dial, cpuPct: Int?, downBps: Long?, upBps: Long?, watts: Double?): String = when (dial) {
        Dial.CPU -> cpuPct?.coerceIn(0, 100)?.toString() ?: "–"
        Dial.DOWN -> Probe.barRate(downBps)
        Dial.UP -> Probe.barRate(upBps)
        Dial.WATTS -> Probe.barWatts(watts)
    }

    /** The line under the number, where there is room for the unit and the direction. */
    fun line(dial: Dial, cpuPct: Int?, downBps: Long?, upBps: Long?, watts: Double?): String = when (dial) {
        Dial.CPU -> if (cpuPct == null) "cpu: no reading" else "$cpuPct % cpu"
        Dial.DOWN -> "down " + Probe.perSecond(downBps)
        Dial.UP -> "up " + Probe.perSecond(upBps)
        Dial.WATTS -> Probe.wattsText(watts)
    }
}
