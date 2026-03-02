# Agent Instructions

Full build instructions and phased development plan are in `dev/CLAUDE.md`.

## Quick reference

- App source: `app/src/main/java/com/scriptgod/fireos/avod/`
- Build: `./gradlew assembleRelease`
- Deploy: `adb install -r app/build/outputs/apk/release/app-release.apk`
- Token: `.device-token` -> push to `/data/local/tmp/.device-token` on device
- API analysis: `dev/analysis/`
- Build log: `dev/progress.md`
- Code review guide: `dev/REVIEW.md`
- Emulator: default for testing
- FireTV (real device): 192.168.0.12