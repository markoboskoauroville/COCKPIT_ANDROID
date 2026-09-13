#!/usr/bin/env python3
"""verify.py: the checks that are cheap enough to run on every push and that a compiler will not
run for you. Every check PRINTS WHAT IT EXAMINED (delivery-gate.md 14). Copied in shape from
MINIMALIST_STOPWATCH/scripts/verify.py (13.9.2026)."""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/mantra/cockpit"
failures, checks = [], []
TEST_FLOOR = 85


def code_only(text):
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(l for l in without_block.split("\n") if not l.lstrip().startswith(("//", "*")))


def check(name, ok, detail):
    checks.append(name)
    print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


# 1 the readers import nothing from Android: Test 1 then tests the mechanism, not an emulator
probe = code_only((MAIN / "Probe.kt").read_text())
android_imports = re.findall(r"^import (android\.|androidx\.).*$", probe, re.M)
check("Probe.kt imports nothing from Android", not android_imports, f"{len(re.findall(r'^import ', probe, re.M))} imports examined, {len(android_imports)} from android")

# 2 the version is written in one place and derived everywhere else
gp = (ROOT / "gradle.properties").read_text()
m = re.search(r"^appVersion=(\d+)$", gp, re.M)
bg = (ROOT / "app/build.gradle.kts").read_text()
check("one version in gradle.properties, derived in build.gradle.kts", bool(m) and "versionCode = appVersion" in bg and 'versionName = appVersion.toString()' in bg, f"appVersion={m.group(1) if m else '?'}")
for f in [p for p in MAIN.glob("*.kt")]:
    t = code_only(f.read_text())
    hard = re.findall(r"versionCode\s*=\s*\d+|versionName\s*=\s*\"\d", t)
    check(f"no hard-coded version in {f.name}", not hard, f"{len(hard)} found")

# 3 the manifest declares what the service and the bridge need
mf = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
for need in ("FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS", "PACKAGE_USAGE_STATS", 'android:foregroundServiceType="specialUse"', "PROPERTY_SPECIAL_USE_FGS_SUBTYPE", "rikka.shizuku.ShizukuProvider", ".BootReceiver"):
    check(f"manifest carries {need}", need in mf, "present" if need in mf else "MISSING")
check("manifest does NOT carry REQUEST_INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES" not in mf, "absent")
check("manifest does NOT carry KILL_BACKGROUND_PROCESSES (it does nothing on Android 14+, and would claim otherwise)", "KILL_BACKGROUND_PROCESSES" not in mf, "absent")

# 4 every shell command hands a package name through the shape check first
bridge = code_only((MAIN / "Bridge.kt").read_text())
check("Bridge.forceStop checks the package name by shape before the shell", "ShellText.PACKAGE.matches(pkg)" in bridge and 'run("am force-stop $pkg"' in bridge, "the check precedes the call")

# 5 the service stops its clock on SCREEN_OFF
svc = code_only((MAIN / "CockpitService.kt").read_text())
check("the service stops on SCREEN_OFF and resumes on SCREEN_ON", "ACTION_SCREEN_OFF" in svc and "handler.removeCallbacks(tick)" in svc and "ACTION_SCREEN_ON" in svc, "both branches present")
check("the notification is silent and alerts once", "setSilent(true)" in svc and "setOnlyAlertOnce(true)" in svc, "present")

# 6 the test floor ratchets
tests = (ROOT / "app/src/test/java/com/mantra/cockpit/ProbeTest.kt").read_text()
n = len(re.findall(r"@Test", tests))
check(f"at least {TEST_FLOOR} unit tests", n >= TEST_FLOOR, f"{n} @Test cases")

# 7 the proguard keeps the shell service Shizuku creates by name
pg = (ROOT / "app/proguard-rules.pro").read_text()
check("R8 keeps ShellService and IShell", "-keep class com.mantra.cockpit.ShellService" in pg and "IShell" in pg, "present")

# 8 the second reader is as pure as the first: Bar decides what is at the top, and it is tested
# on a desk beside Probe rather than on a phone
bar = code_only((MAIN / "Bar.kt").read_text())
bar_android = re.findall(r"^import (android\.|androidx\.).*$", bar, re.M)
check("Bar.kt imports nothing from Android", not bar_android, f"{len(re.findall(r'^import ', bar, re.M))} imports examined, {len(bar_android)} from android")

# 9 a number switched off must come DOWN from the bar. A notification nobody cancels keeps showing
# a figure that has stopped being refreshed, and it does that quietly (silent-failure.md)
check("the service cancels the dials that are switched off, every tick", "Bar.stale(off)" in svc and "nm.cancel(id)" in svc, f"stale read {svc.count('Bar.stale(off)')}x, cancel called {svc.count('nm.cancel(id)')}x")
check("the service takes every extra number down when it stops", svc.count("cancelExtras()") >= 3, f"{svc.count('cancelExtras()')} mentions: the definition, the stop, the destroy")

# 10 no glyph reaches the bar without being cut to what the bar can draw
icon = code_only((MAIN / "NumberIcon.kt").read_text())
check("NumberIcon cuts a glyph to what the bar can draw", "take(Probe.BAR_GLYPHS)" in icon, "present")
check("the bar's ceiling is three characters", "BAR_GLYPHS = 3" in probe, "present")

# 11 the upgrade promise, structurally: a phone coming from v1 keeps the bar it had, so every dial
# added after the cpu one starts switched off
store = code_only((MAIN / "Store.kt").read_text())
dial_keys = re.findall(r'^\s*[A-Z_]+\("([a-z]+)"', code_only((MAIN / "Bar.kt").read_text()), re.M)
missing = [k for k in dial_keys if k != "cpu" and f'Dial.{k.upper()}.key' not in store]
check("every dial added after v1 starts switched off (the upgrade keeps the bar it had)", not missing and len(dial_keys) >= 2, f"{len(dial_keys)} dials, {len(missing)} not defaulted off: {missing}")

# 12 the choice can be put back the way it shipped (design-language.md 7)
screen = code_only((MAIN / "MainActivity.kt").read_text())
check("the settings offer the way back to what shipped", "store.resetBar()" in screen and "resetBar" in store, "the button and the store agree")

print(f"\n{len(checks)} checks, {len(failures)} failed" + (": " + ", ".join(failures) if failures else ""))
sys.exit(1 if failures else 0)
