# DEVELOPMENT: COCKPIT_ANDROID

Every bug, every decision and every reason. **Appended, never rewritten.** The finished state is in
[`HANDOFF.md`](HANDOFF.md); if a sentence here is in the present tense it probably belongs there.

---

## PART ONE — THE BUGS

Grouped by shape, not by version, so the same one is not made a fourth time.

### A number the phone reports in units it will not name

**v2, and it is the reason the watts dial can show a dash.** `BATTERY_PROPERTY_CURRENT_NOW` is
documented in microamps. Several makers answer in milliamps under that name. `EXTRA_VOLTAGE` is
documented in millivolts and some answer in microvolts. Multiply the two naively and the answer is
wrong by a thousand, or a million, and it is wrong in a way that looks like a number: 0.012 W into a
battery, or 11,700 W.

**What it cost:** nothing yet, because it was designed around rather than discovered. The shape was
recognised from `secrets.md` §2a — a pattern used one way fails closed and the same pattern used the
other way fails open — and from `four-tests.md`, *prove the degradation is the RIGHT degradation*.

**The fix:** compute every plausible reading of the units, keep only the ones that land in a band a
phone can physically be in, and answer **only when exactly one survives**. Two survivors is a dash.
None is a dash. A wrong wattage said confidently would be read as a fact about a charger, which is
the worst thing this app could do with a number.

**What stops it recurring:** `theVoltageUnitIsRecognisedNotAssumed`,
`twoReadingsBothPlausibleIsADashNotAGuess` and `neitherReadingPlausibleIsADash` in Test 1. The
second was proven red by a mutation that took the first plausible candidate instead of refusing.

### A counter that goes backwards is not a negative rate

Byte totals since boot are monotonic **until a reboot**, and TrafficStats answers -1 on a device
that does not keep them. Subtracting either of those and dividing gives a number in the exabytes
that would be drawn at the top of the phone as `99+`. `Probe.rate` returns null for a counter that
moved backwards, for -1 on either side, and for a non-positive interval. Same shape as
`Probe.busy`'s clamp, which exists for the same reason at the other end.

### A notification nobody cancels

A number switched off in the settings has a notification still posted for it. Nothing removes it, so
it stays at the top of the phone showing a figure that has stopped being refreshed — no error, no
crash, and the person reads a stale number as a live one. `silent-failure.md`, exactly.

**The fix:** `Bar.stale()` names every id that must not be showing, including the lead dial's own
unused id, and `refresh()` cancels all of them on every tick; `cancelExtras()` takes them down when
the service stops and when it is destroyed. Proven red by deleting the cancel line.

---

## PART TWO — THE DECISIONS

**Several numbers at the top, not one that cycles.** Marko chose this over one number the gear picks
between. The rejected option was cheaper — one notification, no channel, no cancelling — and it was
rejected because a number you have to wait for is a number you cannot glance at. The cost accepted:
extra rows in the notification shade, and Android silently dropping icons when the bar is full.

**The first chosen dial rides on the foreground notification.** The alternative was a fixed
foreground notification of its own plus one per dial, which is one permanent icon more than the
person asked for. With every dial switched off the foreground one shows a dash: the service must
keep a notification for as long as it runs, and an empty one would claim there is nothing to read.

**One number covers charging and discharging.** Marko, 13.9.2026, revising the answer he had just
given: *"A battery wattage is also useful. When I'm not charging, same number is showing
discharging power."* So the dash means only *no reading*, never *nothing happening*.

**The sign is not drawn at the top.** Four characters do not fit in a status-bar icon and three do.
The phone's own charging bolt sits one icon away and already says which way the energy goes; the
notification line says it in words. Rejected: a drawn arrow badge, which at bar size is a smudge.

**Megabytes a second, always, for both network dials.** Rejected: switching units by magnitude, so
that 812 kB/s shows `812` and 1.4 MB/s shows `1.4`. Two numbers at the top that cannot be compared
without reading their units are worse than one number with a leading zero.

**Store what is switched OFF** (`design-language.md` §7), so a dial added in a later version is live
rather than invisible to anybody whose choice predates it. The consequence, stated plainly because
it is a real cost: a dial added in v3 will appear at the top of the phone without being asked for.

**The documents were split** into `HANDOFF.md` and this file, with a stub left at `HANDOVER.md`
(`repository-documents.md` §7). v1's handover was already doing two jobs.

### A test that pinned the roster instead of the rule

**v3.** `theLeadDialRidesOnTheForegroundNotification` asserted `listOf(Dial.CPU, Dial.WATTS)` from a
hidden set written out by hand. Adding two dials made it red while nothing was wrong: the rule it
meant to test — *the first visible dial rides on the foreground notification, the rest are extras* —
still held. Rewritten to build its hidden set from the roster, so the next dial does not fail it.

**The lesson is not "that test was bad".** It is that a case which enumerates a list is a case that
fails when the list grows, and the failure carries no information. Assert the rule.

---

## PART TWO AND A HALF — v3

**Two dials, one direction each.** Marko, 13.9.2026, with v2 on the phone: *"It needs to show power
drawn all the time and power charging all the time."* The combined `watts` dial stays, because it is
already on his phone and nothing that was there disappears; `draw` and `charge` are added beside it.

**A dash in the wrong direction is not a zero.** While the charger is in, the `draw` dial reads `–`,
not `0.0`. The battery is not drawing nothing, it is charging, and the other dial has that number.
`0.0` would be a measurement nobody made. `aDashInTheWrongDirectionIsNotAZero` holds this.

**The cost of storing what is switched off, paid for the first time.** A phone that has never opened
the gear gets the new default and sees no change. **A phone whose owner has already ticked something
has a stored set that cannot contain the new keys, so `draw` and `charge` arrive switched ON.** That
follows from `design-language.md` §7 and it is the right rule; it is written here because it is a
real cost and it will surprise somebody once. The reset button puts it back.

---

## PART THREE — THE VERSIONS

| v | date | what |
|---|---|---|
| 1 | 13.9.2026 | the CPU number in the bar, the health notification, recent apps with a stop, Shizuku |
| 2 | 13.9.2026 | download and upload speed, watts charging or drawing, the gear that chooses what is at the top |
| 3 | 13.9.2026 | the two directions as their own dials: watts out and watts in, each a dash in the other state |

---

## PART FOUR — WHAT WAS INHERITED

- **The shape** is `MINIMALIST_STOPWATCH`'s: one version in `gradle.properties`, CI builds and
  signs, `scripts/verify.py`, Test 1 as a floor.
- **The arithmetic** of the CPU number is `COCKPIT_TERMUX/probe.py`'s, so the two halves agree.
- **Deliberately not carried across:** `KILL_BACKGROUND_PROCESSES`. It does nothing on Android 14+
  and declaring it would claim otherwise.
- **`/proc/net/dev` was not used.** It is the obvious file and an app may not read it here.
  TrafficStats is the lawful route. Same lesson as `/proc/stat`, which v1 met first.

---

## PART FIVE — WHAT CHANGED ABOUT HOW THIS IS BUILT

**Test 1 no longer needs CI, and that is the floor rising** (`never-back-to-zero.md`).
`android-app.md` §2 says there is no Java on the phone, so every round trip is a five to eight
minute GitHub Actions run. True of the phone; not true of a sandbox with a JDK. `Probe.kt`,
`Bar.kt` and `Updates.kt` import nothing from Android, so `kotlinc` compiles them with the JUnit
jar and the 87 cases run in 0.285 seconds with no Android SDK anywhere.

Proven before it was believed: with the clamp removed from `Probe.busy`, two cases went red; the
command is in `HANDOFF.md`. **A test harness that has never been seen to fail is a rumour.**
