# DELIVERY RECORD, COCKPIT_ANDROID

The gates run in GitHub Actions on every push (`.github/workflows/build-apk.yml`): G1 provenance
(clean tree, the three numbers agree, built by CI), G2 secrets (the history and the artefact),
G3 the structural checks (`scripts/verify.py`) and Lint (blocking), G5 loops counted, Test 1
(JUnit, 50 cases). G4, G6, G7, G8 are not run for this edition and are said so here.

## v1, 13.9.2026

Built and published by run 2 of the workflow on commit 77d6738: G1, G2 (the history and the artefact), verify.py 27 checks, G5, Test 1 (50 JUnit cases), Lint blocking, the signed APK 1-cockpit-v1.apk, release v1. The first run failed on a */ inside a doc comment (recorded in MANTRA_MANIFEST/modules/android-app.md). The APK is on the phone at ~/storage/downloads/1-cockpit-v1.apk, not yet installed.

NOT TESTED   everything on a phone: see HANDOVER.md. G4 (an unwired sweep of the screen), G6 (a
             soak of the service), G7 (a battery budget), G8 (an upgrade over v0: there is none).

ACCEPTED     the repository is private, so the in-app update check cannot work until it is public.
