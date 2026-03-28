# Amazon Fire TV Player — Agent Instructions

## Goal
Build a native Android/Kotlin APK for Fire TV that plays Amazon Prime Video streams,
by porting the existing Kodi plugin logic to Android.

---

## Reference Sources

Two reference codebases are available at `/home/vscode/` for API analysis and porting:

| Path | Contents |
|------|----------|
| `/home/vscode/kodi-plugin/plugin.video.amazon-test/resources/lib/` | Kodi Prime Video plugin (Python) — primary porting reference; `network.py`, `login.py`, `playback.py`, `atv_api.py`, `common.py` |
| `/home/vscode/prime-3.0.412.2947-smali/` | Decompiled Prime Video APK 3.0.412 (smali/baksmali) — use for confirming exact API parameter names, enum string values, and request formats |
| `/home/vscode/prime-3.0.412.2947/` | Same APK decompiled with apktool (resources + smali) |

When implementing a new feature or investigating an API issue, always check `network.py` first, then cross-reference with the smali for exact enum/constant values.

---

## Device Access

Fire TV is connected via ADB. IP is in `.env` as `FIRETV_IP`.
```bash
adb connect $FIRETV_IP:5555
```

Refer `README.md` how to connect the emulator - use the IP of the example
---

## Authentication

A valid device token is at `.device-token` (created before agents start).

### Token Usage
```kotlin
// Read at runtime — never hardcode
val token = Gson().fromJson(File("/home/vscode/amazon-vod-android/.device-token").readText(), TokenData::class.java)

// Structure:
// { "access_token": "...", "refresh_token": "...", "device_id": "...", "expires_in": 3600 }
```

### Token Refresh
On 401: POST to `https://api.{sidomain}/auth/token` with `refresh_token` + device data.
`sidomain` is set per territory (e.g. `amazon.de` for DE, `amazon.com` for US) by `AmazonApiService.detectTerritory()`.
Update `.device-token` with the new token. Implement as an OkHttp interceptor in `AmazonAuthService.kt`.

### Security Rules
- NEVER log, print, or write token values anywhere
- NEVER re-register the device or call registration endpoints
- `.device-token` is chmod 600 — do not change permissions

---

## State Persistence

After EVERY phase write to `/home/vscode/amazon-vod-android/progress.md`:
- Mark complete phases as `Phase N: COMPLETE`
- Note any blockers or decisions

Phase 1 also writes:
- `dev/analysis/api-map.md` — endpoints, auth flow, license URL
- `dev/analysis/decisions.md` — file references, workarounds

## Resume Behavior
On start, ALWAYS:
1. Read `progress.md`
2. For each phase: if `progress.md` contains `Phase N: COMPLETE`, skip that phase entirely — do not re-run, re-create, or re-verify it
3. Find the first phase NOT marked `COMPLETE` and start there
4. Read all files in `analysis/` before writing any code

---
### API guidance
- Reuse existing `GetSearchResults` endpoint; add `contentType` or `primeOnly` params as documented in `api-map.md`
- If a content type has no dedicated endpoint, filter results client-side by the `isPrime`, `isFreeWithAds`, or `channelId` fields in the catalog response

Rebuild and reinstall after changes:
```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
---

## General Rules
- Complete each phase before starting the next
- On 401/403: re-examine Kodi plugin auth logic first
- Never hardcode credentials — read from `.device-token` and `.env`
