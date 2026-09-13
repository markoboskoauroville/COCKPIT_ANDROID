# DELIVERY RECORD, COCKPIT_ANDROID

The gates run in GitHub Actions on every push (`.github/workflows/build-apk.yml`): G1 provenance
(clean tree, the three numbers agree, built by CI), G2 secrets (the history and the artefact),
G3 the structural checks (`scripts/verify.py`) and Lint (blocking), G5 loops counted, Test 1
(JUnit). G4, G6, G7, G8 are not run for this edition and are said so here.

## v2, 13.9.2026 — the line's speed, the watts, and the gear

MEASURED, before the push, on a sandbox with a JDK and no Android SDK:

    Test 1          87 JUnit cases, 0 failed, 0.285 s, run on a desk
                    (50 of them inherited from v1 and re-run, 37 new)
    verify.py       35 structural checks, 0 failed (27 inherited, 8 new)

**Five checks were made to go red on purpose**, each from a mutation confirmed to have changed the
file, because a check that has never been seen to fail is a rumour (`checking-the-checks.md`):

    the clamp removed from Probe.busy                    2 cases red   (the harness itself)
    watts takes the first plausible unit, not the only   1 case red
    the three-glyph ceiling raised out of reach          2 cases red
    the cancel of a switched-off dial deleted            1 check red
    a dial left out of the v1 default-off set            1 check red
    an android import added to Bar.kt                    1 check red

NOT TESTED   **Everything on a phone.** No build of v2 has been installed. Specifically: whether
             this phone reports current in microamps or milliamps (the app will show a dash rather
             than guess, so the first run may show a dash where a wattage was wanted); whether the
             status bar has room for three icons beside the clock, battery and signal; whether
             TrafficStats counts what a person would call downloading; what the extra notifications
             cost over a day; the legibility of "99+" and "1.4" at bar size.
             G4 (an unwired sweep of the screen), G6 (a soak), G7 (a battery budget).
             G8 (the upgrade over v1) is reasoned and structurally checked — every dial added after
             v1 starts switched off, so an upgraded phone keeps the bar it had — but it has not been
             RUN: no phone has installed v2 over v1.

ACCEPTED     The repository is private, so the in-app update check cannot work until it is public.
             The sign of the watts is not drawn at the top: three characters is the budget and the
             phone's own charging bolt is one icon away.

## v1, 13.9.2026

Built and published by run 2 of the workflow on commit 77d6738: G1, G2 (the history and the artefact), verify.py 27 checks, G5, Test 1 (50 JUnit cases), Lint blocking, the signed APK 1-cockpit-v1.apk, release v1. The first run failed on a */ inside a doc comment (recorded in MANTRA_MANIFEST/modules/android-app.md). The APK is on the phone at ~/storage/downloads/1-cockpit-v1.apk, not yet installed.

NOT TESTED   everything on a phone: see HANDOFF.md. G4 (an unwired sweep of the screen), G6 (a
             soak of the service), G7 (a battery budget), G8 (an upgrade over v0: there is none).

ACCEPTED     the repository is private, so the in-app update check cannot work until it is public.
