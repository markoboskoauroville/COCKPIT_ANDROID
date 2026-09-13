# HANDOVER: COCKPIT_ANDROID (13.9.2026)

## What it is

The status-bar CPU number and the phone's health in a notification, with the apps in memory and a
stop on each; the Android half of the cockpit, next to COCKPIT_TERMUX. Kotlin, Compose, one
foreground service, one Shizuku user service. Built 13.9.2026 in one session on the phone, compiled
by GitHub Actions (there is no Java in Termux).

## Decisions

- **The number is the idle counters** (Probe.kt), the same arithmetic as the Termux app's
  probe.py, on files this phone lets an app read. `/proc/stat` is denied to apps here.
- **No KILL_BACKGROUND_PROCESSES permission, on purpose.** It does nothing on Android 14+ and
  would claim otherwise. A stop is App Info's Force stop, or `am force-stop` through Shizuku.
- **Shizuku is optional.** `Bridge.kt` says at every step what is missing (not installed, not
  running, not allowed, binding). The user service (`ShellService.kt`) runs one bounded `sh -c`.
- **The clock stops with the screen.** A monitor that wakes the phone to measure it is the drain it
  claims to watch.
- **The notification is silent, alerts once, low importance**, so a redraw every two seconds is
  never a sound or a heads-up.
- **Version in one place** (`gradle.properties`), derived everywhere; the tag is `vN`, the file
  `N-cockpit-vN.apk`; the two newest releases are kept (versioning.md).
- **Private repository**; the in-app update check therefore answers "github 404 (a private
  repository)" until it goes public, and says so rather than "up to date".

## NOT TESTED (13.9.2026)

The first build runs in GitHub Actions after this commit; this record is written before it. Until
the APK has been installed on the phone: the icon's legibility at status-bar size, the two-second
redraw's cost over a day, Usage access on Android 16, the App Info intent, Shizuku's binding and
the force-stop through it, the boot receiver. Test 1 (JUnit) and the structural checks are the
tests that exist; Test 2 is the phone.

## What is left

1. Install the first release, read the bar, correct the icon size if two digits are too small.
2. Measure a day of battery with the service on; if it costs, lengthen the period.
3. When Shizuku is on the phone: the process list and force-stop, for real.
4. Package labels and icons in the Shizuku process list (names only now).
