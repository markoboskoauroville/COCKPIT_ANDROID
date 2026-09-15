# HANDOFF: COCKPIT_ANDROID

    version      3
    repository   markoboskoauroville/COCKPIT_ANDROID (private)
    artefact     3-cockpit-v3.apk, release tag v3

**The reasons live in [`DEVELOPMENT.md`](DEVELOPMENT.md).** Every bug, every decision and every
rejected alternative is there. Nothing historical belongs in this file.

## WHAT IT IS

Numbers at the top of the phone, and the phone's health under them. A foreground service reads the
counters every two seconds while the screen is on, draws each chosen number as a notification's
small icon, and writes the detail into the notification itself. The Android half of the cockpit;
COCKPIT_TERMUX is the other half, on 127.0.0.1:8860, reading the same counters so the two agree.

## THE NUMBERS AT THE TOP

Six dials, chosen with the gear on the main screen. **One number is one notification, because a
notification carries one icon.** The first chosen dial rides on the service's own notification; the
rest get their own, silent, on the `cockpit_bar` channel at IMPORTANCE_MIN. A dial switched off is
cancelled on the next tick.

| key | at the top | where it comes from |
|---|---|---|
| `cpu` | 0–100 | the idle counters of every core |
| `down` | MB/s | `TrafficStats.getTotalRxBytes`, differenced |
| `up` | MB/s | `TrafficStats.getTotalTxBytes`, differenced |
| `watts` | W, unsigned | current × voltage, sign from the battery status |
| `draw` | W out, or `–` | the same reading, shown only while the battery is discharging |
| `charge` | W in, or `–` | the same reading, shown only while it is charging |

**Three characters is the whole budget** (`Probe.BAR_GLYPHS`). Every glyph goes through
`Probe.short`, which gives one decimal below 10, a whole number to 99, then `99+`. A reading that
cannot be had is `–`, never a zero: zero is a fact and the dash is the absence of one.

## THE MODEL

    gradle.properties     appVersion=N, the ONLY place the version is written
    Probe.kt              every reader and every piece of arithmetic. Imports nothing from Android
    Bar.kt                which dials are at the top, their ids, their glyphs. Also Android-free
    Store.kt              what is switched OFF, never what is on
    Sampler.kt            one snapshot of the phone, Android's side of every reading
    CockpitService.kt     the notifications, the clock, and the cancelling
    MainActivity.kt       the screen, the gear, the settings
    scripts/verify.py     35 structural checks a compiler will not run

**The rule that protects the irreplaceable thing:** `Probe.kt` and `Bar.kt` import nothing from
Android, and `verify.py` fails the build the day an `import android.` appears in either. That is
what keeps Test 1 runnable on a desk.

## THE DANGEROUS PART, AS IT IS NOW

**The watts reading is the one to distrust.** Phones disagree about the units of both halves: the
current property is documented in microamps and several makers answer in milliamps under that name,
and the voltage extra is documented in millivolts and some answer in microvolts. So `Probe.watts`
computes every plausible reading of the units and **answers only when exactly one of them lands in
a band a phone can be in** (0.05 W to 20 W drawing, to 150 W charging). Two candidates in the band,
or none, is a dash. The sign never comes from the current's own sign — that is the other thing
makers invert — it comes from the battery status.

## THE SCREENS

- **main** — the dials, the recent apps with a stop on each, Shizuku, the permissions. The gear is
  at the far right of the header.
- **settings (the gear)** — a tick box per dial, each row carrying the number it would be showing
  this second, and one button to put it back the way it shipped. The way out is at the right-hand
  end of the top row.

## BUILDING

GitHub Actions builds every push to main. There is no Java on the phone.

```bash
python3 scripts/verify.py                                   # the structural checks, anywhere
./gradlew :app:testReleaseUnitTest                          # Test 1, where there is an Android SDK
```

**Test 1 also runs on any machine with a JDK and no SDK at all**, because the two files it tests
import nothing from Android:

```bash
kotlinc -cp junit-4.13.2.jar \
  app/src/main/java/com/mantra/cockpit/{Probe,Bar,Updates}.kt \
  app/src/test/java/com/mantra/cockpit/ProbeTest.kt -include-runtime -d test1.jar
java -cp test1.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar org.junit.runner.JUnitCore com.mantra.cockpit.ProbeTest
```

94 cases, under a third of a second. Use it before every push; CI is five to eight minutes.

## WHAT HAS NEVER BEEN PROVEN

- **v2 is on the phone; v3 is not.** The two directional dials have never been seen at the top,
  and what the watts reading does on this phone has not yet been written down here.
- **Which units this phone reports.** `Probe.watts` refuses to guess, so the first thing v2 may do
  is show a dash where a wattage was wanted. That is the designed failure, not the desired one, and
  the answer is to read what the phone reports and write it into `DEVELOPMENT.md`.
- **Whether the bar has room for three icons** beside the clock, the battery and the signal. Android
  drops what does not fit without saying so.
- **The cost of the extra notifications** over a day of battery.
- **TrafficStats on this phone**: whether it counts what a person would call downloading.
- Everything v1 never proved: the icon's legibility at bar size, Usage access on Android 16, the App
  Info intent, Shizuku's binding, the boot receiver.
