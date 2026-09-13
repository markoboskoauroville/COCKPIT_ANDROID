# COCKPIT_ANDROID

**The CPU number in the status bar, the phone's health in the notification, the apps in memory
and the hand on them.** The Android half of the cockpit; the Termux half is COCKPIT_TERMUX
(`cockpit`, the page on 127.0.0.1:8860), and the two read the same counters so they agree.

Marko, 13.9.2026: *"an app which is going to show at the top of Android, where I have my clock, a
number from 0 to 100 representing the CPU used at this moment; and in the notification area the
other status of my Android, which apps are in memory and I can kill them, how much RAM I'm using,
and any other statistical data one hacker should have in his phone."*

## What it does

- **The number in the bar**: a foreground service whose notification icon is the CPU percentage,
  drawn from the idle counters of every core (`/sys/devices/system/cpu/cpu*/cpuidle/state*/time`,
  the one CPU figure an app may read on Android 16; `/proc/stat` is denied). Redrawn every two
  seconds while the screen is on; asleep while the screen is off.
- **The notification**: the headline (cpu, GHz, RAM, swap), then RAM in detail, battery with
  temperature, thermal status, uptime, storage, each core, and the apps used most recently. Three
  buttons: the Termux cockpit page, this app's apps screen, refresh.
- **The screen**: the same dials, the recent apps (Usage access) with a **stop** on each, Shizuku
  when it is installed, and the permissions with the button that opens each one.
- **The hand on it**: Android 14 and newer let no app kill another (`killBackgroundProcesses`
  touches only the caller's own package). So a stop opens App Info, where Force stop is one tap;
  with **Shizuku** installed and started, the app runs `am force-stop` as the shell user, lists
  every process with its memory (`dumpsys meminfo`) and reads the real `/proc/stat`.

## Install

The APK is a release of this repository (private for now): download `N-cockpit-vN.apk`, install it,
open it, press *put the number in the status bar*, allow notifications. Then Settings → Apps →
Cockpit → Battery → Unrestricted, or Android puts the number to sleep. For recent apps: *give
Cockpit Usage access*. For force-stop and every process: install Shizuku (github.com/RikkaApps/
Shizuku, the APK), start it, allow Cockpit in it.

## Building

GitHub Actions builds every push to main (`.github/workflows/build-apk.yml`): the gates, Test 1
(50+ JUnit cases), the signed APK, the release `vN` from `appVersion` in `gradle.properties`. The
signing key is a PKCS12 made on the phone; its copy is in `~/.cockpit-signing/` there and in the
two repository secrets. Nothing here builds on the phone: no Java in Termux.

```bash
python3 scripts/verify.py        # the structural checks, on any machine
./gradlew :app:testReleaseUnitTest   # Test 1, where there is a JDK
```
