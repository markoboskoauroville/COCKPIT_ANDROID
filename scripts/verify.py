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
TEST_FLOOR = 50


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

print(f"\n{len(checks)} checks, {len(failures)} failed" + (": " + ", ".join(failures) if failures else ""))
sys.exit(1 if failures else 0)
