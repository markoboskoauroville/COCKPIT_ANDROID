package com.mantra.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TEST 1, the mechanism alone: every parser and every piece of arithmetic in Probe and ShellText,
 * on a desk, against numbers measured on the phone (13.9.2026) and a fake sysfs tree. The same
 * cases as COCKPIT_TERMUX/tests/test1_mechanism.py, so the two apps cannot drift apart quietly.
 */
class ProbeTest {

    private fun fakeRoot(cores: Int = 4, idleUs: Long = 1_000_000L, curMhz: Int = 1500, maxMhz: Int = 2000): File {
        val root = Files.createTempDirectory("cockpit-sysfs").toFile()
        File(root, "online").writeText("0-${cores - 1}\n")
        for (c in 0 until cores) for (st in 0..1) {
            val d = File(root, "cpu$c/cpuidle/state$st").apply { mkdirs() }
            File(d, "time").writeText("${idleUs / 2}\n")
            File(d, "name").writeText(if (st == 0) "WFI\n" else "off\n")
        }
        val pol = File(root, "cpufreq/policy0").apply { mkdirs() }
        File(pol, "scaling_cur_freq").writeText("${curMhz * 1000}\n")
        File(pol, "cpuinfo_max_freq").writeText("${maxMhz * 1000}\n")
        File(pol, "related_cpus").writeText((0 until cores).joinToString(" ") + "\n")
        return root
    }

    private fun setIdle(root: File, cpu: Int, us: Long) = File(root, "cpu$cpu/cpuidle/state0/time").writeText("$us\n")

    // ---- the cpu list
    @Test fun rangeParses() = assertEquals((0..7).toList(), Probe.parseCpuList("0-7\n"))
    @Test fun rangesAndSingles() = assertEquals(listOf(0, 1, 2, 3, 6, 7), Probe.parseCpuList("0-3,6-7"))
    @Test fun relatedCpusSpaceSeparated() = assertEquals(listOf(0, 1, 2, 3, 4, 5), Probe.parseCpuList("0 1 2 3 4 5"))
    @Test fun garbageIsEmpty() = assertEquals(emptyList<Int>(), Probe.parseCpuList("x-y,,7-3,"))
    @Test fun nullIsEmpty() = assertEquals(emptyList<Int>(), Probe.parseCpuList(null))
    @Test fun aHugeRangeIsRefused() = assertEquals(emptyList<Int>(), Probe.parseCpuList("0-999999"))

    // ---- the idle counters make the number
    @Test fun coresAreFound() = assertEquals(listOf(0, 1, 2, 3), Probe.cpus(fakeRoot().path))
    @Test fun idleSumsBothStates() = assertEquals(1_000_000L, Probe.idleUs(0, fakeRoot().path))
    @Test fun idleOfNowhereIsNull() = assertNull(Probe.idleUs(0, "/nonexistent"))

    private fun twoSamples(): Probe.Busy {
        val root = fakeRoot()
        val a = Probe.sample(0L, root.path)
        // one second later: core 0 slept the whole second, 1 half, 2 not at all, 3 more than the second
        for ((c, gain) in listOf(0 to 1_000_000L, 1 to 500_000L, 2 to 0L, 3 to 1_100_000L)) setIdle(root, c, 500_000L + gain)
        val b = Probe.sample(1_000_000_000L, root.path)
        return Probe.busy(a, b)!!
    }

    @Test fun aCoreAsleepAllSecondIsZero() = assertEquals(0, twoSamples().perCore[0])
    @Test fun aCoreAsleepHalfIsFifty() = assertEquals(50, twoSamples().perCore[1])
    @Test fun aCoreNeverAsleepIsHundred() = assertEquals(100, twoSamples().perCore[2])
    @Test fun aCounterAheadOfTheClockIsClampedToZero() = assertEquals(0, twoSamples().perCore[3])
    @Test fun theOverallIsTheMean() = assertEquals(38, twoSamples().pct)
    @Test fun noTimePassedNoNumber() { val s = Probe.sample(5L, fakeRoot().path); assertNull(Probe.busy(s, s)) }
    @Test fun noPreviousNoNumber() = assertNull(Probe.busy(null, Probe.sample(1L, fakeRoot().path)))
    @Test fun aVanishedCoreIsLeftOut() {
        val root = fakeRoot()
        val a = Probe.sample(0L, root.path)
        File(root, "cpu2/cpuidle/state0/time").delete(); File(root, "cpu2/cpuidle/state1/time").delete()
        val b = Probe.sample(1_000_000_000L, root.path)
        assertEquals(3, Probe.busy(a, b)!!.perCore.size)
    }
    @Test fun noCoresAtAllIsNull() = assertNull(Probe.busy(Probe.CpuSample(0, emptyMap()), Probe.CpuSample(1_000_000_000L, emptyMap())))

    // ---- the frequencies
    @Test fun onePolicyOverFourCores() { val c = Probe.clusters(fakeRoot().path); assertEquals(1, c.size); assertEquals(listOf(0, 1, 2, 3), c[0].cpus) }
    @Test fun currentMaxAndPercent() { val c = Probe.clusters(fakeRoot().path)[0]; assertEquals(1500, c.curMhz); assertEquals(2000, c.maxMhz); assertEquals(75, c.pct) }
    @Test fun noSysfsNoClusters() = assertEquals(emptyList<Probe.Cluster>(), Probe.clusters("/nonexistent"))
    @Test fun aFrequencyFileWithTextIsZero() {
        val root = fakeRoot(cores = 1)
        File(root, "cpufreq/policy0/scaling_cur_freq").writeText("notanumber\n")
        val c = Probe.clusters(root.path)[0]
        assertEquals(0, c.curMhz); assertNull(c.pct)
    }

    // ---- /proc/meminfo, captured on the phone 13.9.2026
    private val meminfo = """MemTotal:        7590488 kB
MemFree:          202880 kB
MemAvailable:    1837916 kB
Buffers:            1208 kB
Cached:          2010144 kB
SwapCached:         4008 kB
SwapTotal:       4194300 kB
SwapFree:         557464 kB
Dirty:               276 kB
"""

    @Test fun everyLineParsed() { val kb = Probe.parseMeminfo(meminfo); assertEquals(7590488L, kb["MemTotal"]); assertEquals(557464L, kb["SwapFree"]); assertEquals(276L, kb["Dirty"]) }
    @Test fun usedIsTotalMinusAvailable() = assertEquals(((7590488L - 1837916L) / 1024).toInt(), Probe.memory(meminfo)!!.usedMb)
    @Test fun swapUsedAndPercent() { val m = Probe.memory(meminfo)!!; assertEquals(((4194300L - 557464L) / 1024).toInt(), m.swapUsedMb); assertEquals(86, m.swapPct) }
    @Test fun usedPercent() = assertEquals(75, Probe.memory(meminfo)!!.usedPct)
    @Test fun garbageIsNull() { assertNull(Probe.memory("MemTotal: lots\n")); assertNull(Probe.memory("")); assertNull(Probe.memory(null)) }
    @Test fun noSwapIsZeroPercent() = assertEquals(0, Probe.memory("MemTotal: 1000 kB\nMemAvailable: 500 kB\nSwapTotal: 0 kB\nSwapFree: 0 kB\n")!!.swapPct)
    @Test fun aFiveThousandDigitLineIsSkipped() { val kb = Probe.parseMeminfo("MemTotal: " + "9".repeat(5000) + " kB\nMemFree: 5 kB\n"); assertNull(kb["MemTotal"]); assertEquals(5L, kb["MemFree"]) }

    // ---- the headline and the duration
    @Test fun headlineWithEverything() {
        val h = Probe.headline(Probe.Busy(37, listOf(37)), listOf(Probe.Cluster("policy0", listOf(0), 1950, 2000)), Probe.Memory(7412, 5004, 2408, 0, 4095, 3047))
        assertEquals("37 % cpu · 2.0 GHz · RAM 4.9/7.2 GB · swap 3.0 GB", h)
    }
    @Test fun headlineWithNothing() = assertEquals("cpu –", Probe.headline(null, emptyList(), null))
    @Test fun durationDays() = assertEquals("1d 3h 12m", Probe.duration(86400 + 3 * 3600 + 12 * 60))
    @Test fun durationHours() = assertEquals("0h 5m", Probe.duration(300))
    @Test fun durationZero() = assertEquals("0h 0m", Probe.duration(0))
    @Test fun headlineWithoutSwapSaysNoSwap() = assertEquals("12 % cpu · RAM 1.0/2.0 GB", Probe.headline(Probe.Busy(12, listOf(12)), emptyList(), Probe.Memory(2048, 1024, 1024, 0, 0, 0)))

    // ---- the shell's text, captured on the phone
    private val dumpsys = """Applications Memory Usage (in Kilobytes):

Total PSS by process:
    421,556K: com.android.systemui (pid 1832)
    318,004K: com.termux (pid 8030 / activities)
     97,120K: com.yahoo.mobile.client.android.weather (pid 21100)
     12,004K: com.termux:api (pid 7150)

Total PSS by OOM adjustment:
    900,000K: Native

Total RAM: 7,590,488K (status normal)
"""

    @Test fun meminfoProcessesBiggestFirst() { val p = ShellText.meminfoProcesses(dumpsys); assertEquals(4, p.size); assertEquals("com.android.systemui", p[0].name); assertEquals(421556 / 1024, p[0].pssMb) }
    @Test fun meminfoProcessPidAndNote() { val p = ShellText.meminfoProcesses(dumpsys)[1]; assertEquals(8030, p.pid); assertEquals("activities", p.note) }
    @Test fun meminfoWithoutTheBlockIsEmpty() = assertEquals(emptyList<ShellText.Proc>(), ShellText.meminfoProcesses("nothing here"))
    @Test fun meminfoCutMidLineIsEmpty() = assertEquals(emptyList<ShellText.Proc>(), ShellText.meminfoProcesses("Total PSS by process:\n    123,456K: com.x (pid"))
    @Test fun procStatBusyAndTotal() = assertEquals(Pair(1550L, 9650L), ShellText.procStat("cpu  1000 0 500 8000 100 0 50 0 0 0\ncpu0 1 2 3 4\n"))
    @Test fun procStatTooFewFields() = assertNull(ShellText.procStat("cpu 1 2\n"))
    @Test fun twoStatReadingsMakeAPercent() = assertEquals(30, ShellText.cpuFromStat(Pair(1550L, 9650L), Pair(1850L, 10650L)))
    @Test fun statNoTimePassed() = assertNull(ShellText.cpuFromStat(Pair(1L, 1L), Pair(1L, 1L)))

    // ---- what a package name may be, before it reaches a shell
    @Test fun aPackageName() = assertTrue(ShellText.PACKAGE.matches("com.android.systemui"))
    @Test fun aShellInjectionIsNot() { assertTrue(!ShellText.PACKAGE.matches("com.x; rm -rf /")); assertTrue(!ShellText.PACKAGE.matches("\$(id)")); assertTrue(!ShellText.PACKAGE.matches("com.x && am kill-all")) }
    @Test fun aBareWordIsNot() = assertTrue(!ShellText.PACKAGE.matches("systemui"))

    // ---- the update comparison, as numbers
    @Test fun versionOfTag() { assertEquals(35, Updates.versionOf("v35")); assertEquals(9, Updates.versionOf("V9")); assertNull(Updates.versionOf("v1.2")); assertNull(Updates.versionOf(null)) }
    @Test fun nineIsNotNewerThanThirtyFive() = assertTrue(Updates.compare(35, "v9", "http://x") is UpdateState.UpToDate)
    @Test fun thirtySixIsNewerThanThirtyFive() = assertTrue(Updates.compare(35, "v36", "http://x") is UpdateState.Available)
    @Test fun noVersionInTheReleaseFails() = assertTrue(Updates.compare(1, "latest", "http://x") is UpdateState.Failed)

    // ---- THE LINE'S SPEED: two byte totals and the time between them
    private fun net(s: Double, rx: Long, tx: Long) = Probe.NetSample((s * 1e9).toLong(), rx, tx)

    @Test fun aSecondOfTwoMegabytes() {
        val r = Probe.rate(net(10.0, 1_000_000, 500_000), net(11.0, 3_000_000, 700_000))!!
        assertEquals(2_000_000L, r.downBps); assertEquals(200_000L, r.upBps)
    }
    @Test fun halfASecondIsDoubledNotHalved() =
        assertEquals(2_000_000L, Probe.rate(net(10.0, 0, 0), net(10.5, 1_000_000, 0))!!.downBps)
    @Test fun noTimePassedIsNoRate() = assertNull(Probe.rate(net(10.0, 0, 0), net(10.0, 5, 5)))
    @Test fun timeGoingBackwardsIsNoRate() = assertNull(Probe.rate(net(11.0, 0, 0), net(10.0, 5, 5)))
    @Test fun aRebootResetsTheCountersAndThatIsNotTerabytes() =
        assertNull(Probe.rate(net(10.0, 9_000_000_000L, 9_000_000_000L), net(11.0, 1_000, 1_000)))
    @Test fun anUploadThatWentBackwardsIsAlsoNull() =
        assertNull(Probe.rate(net(10.0, 0, 9_000_000), net(11.0, 5_000, 1_000)))
    @Test fun unsupportedIsMinusOneAndNotAHugeNumber() {
        assertNull(Probe.rate(net(10.0, -1, -1), net(11.0, -1, -1)))
        assertNull(Probe.rate(net(10.0, 0, 0), net(11.0, -1, 0)))
    }
    @Test fun oneMissingSampleIsNoRate() { assertNull(Probe.rate(null, net(10.0, 0, 0))); assertNull(Probe.rate(net(10.0, 0, 0), null)) }
    @Test fun anIdleLineIsZeroNotNull() = assertEquals(0L, Probe.rate(net(10.0, 77, 77), net(12.0, 77, 77))!!.downBps)

    @Test fun theSpokenRate() {
        assertEquals("0 B/s", Probe.perSecond(0)); assertEquals("999 B/s", Probe.perSecond(999))
        assertEquals("1 kB/s", Probe.perSecond(1_000)); assertEquals("812 kB/s", Probe.perSecond(812_000))
        assertEquals("1.4 MB/s", Probe.perSecond(1_400_000)); assertEquals("\u2013", Probe.perSecond(null))
    }
    @Test fun theRateNeverPrintsAThousandKilobytes() = assertEquals("1.0 MB/s", Probe.perSecond(999_999))

    // ---- THE WATTS: volts times amps, and neither unit is to be trusted on its own
    @Test fun microampsCharging() = assertEquals(12.6, Probe.watts(3_000_000L, 4_200, true)!!, 0.05)
    @Test fun milliampsUnderAMicroampName() = assertEquals(12.6, Probe.watts(3_000L, 4_200, true)!!, 0.05)
    @Test fun theDrawIsNegativeWhateverSignThePhoneUsed() {
        assertTrue(Probe.watts(-300_000L, 3_900, false)!! < 0)
        assertTrue(Probe.watts(300_000L, 3_900, false)!! < 0)
        assertEquals(-1.17, Probe.watts(300_000L, 3_900, false)!!, 0.02)
    }
    @Test fun chargingIsPositiveWhateverSignThePhoneUsed() = assertTrue(Probe.watts(-3_000_000L, 4_200, true)!! > 0)
    @Test fun twoReadingsBothPlausibleIsADashNotAGuess() = assertNull(Probe.watts(20_000L, 4_000, true))
    @Test fun neitherReadingPlausibleIsADash() = assertNull(Probe.watts(10_000L, 4_000, false))
    @Test fun noCurrentIsADash() { assertNull(Probe.watts(0L, 4_200, true)); assertNull(Probe.watts(null, 4_200, true)); assertNull(Probe.watts(Long.MIN_VALUE, 4_200, true)) }
    @Test fun noVoltageIsADash() { assertNull(Probe.watts(3_000_000L, 0, true)); assertNull(Probe.watts(3_000_000L, null, true)) }
    @Test fun aFortyFiveWattChargerFits() = assertEquals(45.0, Probe.watts(9_000_000L, 5_000, true)!!, 0.5)
    @Test fun aHundredAmpsIsNotAReading() = assertNull(Probe.watts(100_000_000L, 4_200, true))

    @Test fun theVoltageUnitIsRecognisedNotAssumed() {
        assertEquals(4.2, Probe.volts(4_200)!!, 0.001); assertEquals(4.2, Probe.volts(4_200_000)!!, 0.001)
        assertEquals(4.0, Probe.volts(4)!!, 0.001); assertNull(Probe.volts(100)); assertNull(Probe.volts(0)); assertNull(Probe.volts(null))
    }
    @Test fun theSpokenWatts() {
        assertTrue(Probe.wattsText(23.4).startsWith("charging 23.4"))
        assertTrue(Probe.wattsText(-4.1).startsWith("drawing 4.1"))
        assertTrue(Probe.wattsText(null).contains("no reading"))
    }

    // ---- THE GLYPH: three characters, at every magnitude, or the bar shows a smudge
    @Test fun theBarRateReads() {
        assertEquals("\u2013", Probe.barRate(null)); assertEquals("0.0", Probe.barRate(0))
        assertEquals("0.8", Probe.barRate(812_000)); assertEquals("1.4", Probe.barRate(1_400_000))
        assertEquals("9.9", Probe.barRate(9_900_000)); assertEquals("10", Probe.barRate(9_950_000))
        assertEquals("12", Probe.barRate(12_000_000)); assertEquals("99", Probe.barRate(99_400_000))
        assertEquals("99+", Probe.barRate(120_000_000))
    }
    @Test fun theBarWattsDropTheSignAndKeepTheSize() {
        assertEquals("4.1", Probe.barWatts(-4.1)); assertEquals("23", Probe.barWatts(23.4)); assertEquals("\u2013", Probe.barWatts(null))
    }
    @Test fun noGlyphIsEverLongerThanTheBarAllows() {
        val rates = listOf<Long?>(null, 0, 1, 999, 1_000, 812_000, 1_400_000, 9_949_999, 9_950_000, 12_000_000, 99_400_000, 99_500_000, 1_000_000_000, Long.MAX_VALUE / 2)
        val watts = listOf<Double?>(null, 0.0, 0.05, 4.1, 9.94, 9.95, 23.4, 99.4, 99.5, 149.9)
        var n = 0
        for (d in Dial.entries) for (r in rates) for (w in watts) for (c in listOf<Int?>(null, 0, 37, 100, 250, -5)) {
            val g = Bar.glyph(d, c, r, r, w)
            assertTrue("$d gave '$g'", g.length <= Probe.BAR_GLYPHS && g.isNotEmpty())
            n++
        }
        assertTrue("combinations walked: $n", n >= 1000)
    }

    // ---- WHICH NUMBERS ARE AT THE TOP, and the ones that must be taken down
    @Test fun nothingHiddenIsEveryDial() = assertEquals(Dial.entries.size, Bar.visible(emptySet()).size)
    @Test fun theLeadDialRidesOnTheForegroundNotification() {
        val hidden = setOf("down", "up")
        assertEquals(listOf(Dial.CPU, Dial.WATTS), Bar.visible(hidden))
        assertEquals(listOf(Dial.WATTS), Bar.extras(hidden))
    }
    @Test fun theLeadDialsOwnIdIsCancelledBecauseNothingPostsIt() =
        assertTrue(Bar.idOf(Dial.CPU) in Bar.stale(setOf("down", "up")))
    @Test fun aSwitchedOffDialIsCancelled() = assertTrue(Bar.idOf(Dial.UP) in Bar.stale(setOf("up")))
    @Test fun aShowingDialIsNotCancelled() = assertTrue(Bar.idOf(Dial.UP) !in Bar.stale(emptySet()))
    @Test fun everythingHiddenPostsNothingExtraAndCancelsAll() {
        val all = Dial.entries.map { it.key }.toSet()
        assertTrue(Bar.visible(all).isEmpty()); assertTrue(Bar.extras(all).isEmpty())
        assertEquals(Dial.entries.size, Bar.stale(all).size)
    }
    @Test fun noTwoDialsShareANotificationId() =
        assertEquals(Dial.entries.size, Dial.entries.map { Bar.idOf(it) }.toSet().size)
    @Test fun noDialCollidesWithTheForegroundNotification() =
        assertTrue(Dial.entries.none { Bar.idOf(it) == Bar.FOREGROUND_ID })
    @Test fun aDialIsFoundByTheKeyThatIsStored() {
        for (d in Dial.entries) assertEquals(d, Dial.byKey(d.key))
        assertNull(Dial.byKey("a dial from a later version"))
    }
    @Test fun everyDialKeyIsDistinct() = assertEquals(Dial.entries.size, Dial.entries.map { it.key }.toSet().size)
    @Test fun theLineCarriesTheUnitTheGlyphCannot() {
        assertTrue(Bar.line(Dial.DOWN, null, 1_400_000, 0, null).contains("MB/s"))
        assertTrue(Bar.line(Dial.WATTS, null, null, null, 23.4).contains("charging"))
        assertTrue(Bar.line(Dial.CPU, null, null, null, null).contains("no reading"))
    }
}
