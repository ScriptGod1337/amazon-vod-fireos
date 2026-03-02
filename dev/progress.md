# Project Progress

## Phase 1: COMPLETE
- Analyzed all Kodi plugin source files in `/home/vscode/kodi-plugin/plugin.video.amazon-test/resources/lib/`
- Mapped all API endpoints: token refresh, GetPlaybackResources (manifest + license), catalog browsing (Android API), watchlist, search, stream reporting
- Documented auth flow: token-based with Bearer header, refresh via POST to `api.{domain}/auth/token`
- Documented device fingerprint: SHIELD Android TV identity (A43PXU4ZN2AL1), User-Agent, app metadata
- Documented Widevine license flow: custom challenge/response wrapping (widevine2Challenge → widevine2License.license)
- Output written to `analysis/api-map.md` and `analysis/decisions.md`

## Phase 2: COMPLETE
- Created Android project scaffold at `/home/vscode/amazon-vod-android/app/`
- Kotlin, minSdk 25, targetSdk 34
- Dependencies: Media3 ExoPlayer + DASH, OkHttp, Gson, Coroutines, Coil
- Activities: MainActivity (browse/search) → BrowseActivity (detail) → PlayerActivity
- Gradle wrapper (8.6), version catalog (libs.versions.toml)

## Phase 3: COMPLETE
- `AmazonAuthService.kt` — loads token from `.device-token`, OkHttp interceptor (auth + android headers), token refresh on 401
- `AmazonApiService.kt` — catalog browsing (home/search/watchlist/library/detail), GetPlaybackResources manifest fetch, UpdateStream reporting
- `AmazonLicenseService.kt` — custom MediaDrmCallback: wraps challenge as `widevine2Challenge=<base64url>`, parses `widevine2License.license` JSON response

## Phase 4: COMPLETE
- `PlayerActivity.kt` — Media3 ExoPlayer with DASH + DefaultDrmSessionManager (Widevine)
- Custom `AmazonLicenseService` wired as `MediaDrmCallback`
- Stream reporting (START/PLAY/STOP) via `UpdateStream`
- Release keystore generated at `/home/vscode/amazon-vod-android/release.keystore`

## Phase 5: COMPLETE
- `assembleRelease` BUILD SUCCESSFUL, APK signed with release keystore
- `adb install -r app-release.apk` — Success
- Token file pushed to `/data/local/tmp/.device-token` (world-readable, avoids external storage permission)
- App launches, territory detected (DE → atv-ps-eu.amazon.de / A1PA6795UKMFR9)

## Phase 6: COMPLETE
Bugs fixed during debug loop:
1. Catalog 404: changed POST → GET for catalog calls
2. German account: added `detectTerritory()` via GetAppStartupConfig
3. JSON parsing: unwrap `resource` wrapper, iterate `collections` as JsonArray
4. Widevine provisioning — 3-part fix:
   a. `executeProvisionRequest`: use plain OkHttpClient (no Amazon auth headers sent to Google)
   b. Google API requires POST with JSON body `{"signedRequest":"..."}`, not GET
   c. Device reboot needed so `amzn_drmprov` links new Widevine cert to Amazon account
5. License denial (`PRSWidevine2LicenseDeniedException`): added missing params to `buildLicenseUrl`
   (`deviceVideoQualityOverride=HD`, `deviceVideoCodecOverride=H264`, `deviceHdrFormatsOverride=None`)

Result: Video plays with Widevine L1 HW secure decode
- `secure.HW.video.avc` decoder active at up to 5830 kbps
- `secureSW.SW.audio.raw` decoder active at 640 kbps
- Adaptive bitrate streaming working
- Screenshot shows black (expected: `FLAG_SECURE` blocks captures of DRM content)

## Phase 7: COMPLETE
Extended search and browse to cover all available content categories:

### Content metadata added to ContentItem
- `isPrime` — parsed from model.isPrime / primeOnly / badgeInfo
- `isFreeWithAds` — parsed from model.isFreeWithAds / freeWithAds / badgeInfo
- `isLive` — parsed from contentType=="live" or liveInfo/liveState metadata fields
- `channelId` — parsed from playbackAction.channelId / station.id

### New AmazonApiService methods
- `ContentCategory` enum: ALL, PRIME, FREEVEE, CHANNELS, LIVE
- `getCategoryContent(category, query)` — unified entry point; routes to correct API + client-side filter
- `getChannelsPage()` — browse with channels pageId; falls back to find page filtered by channelId
- `getSearchSuggestions(query)` — debounced; returns first 8 titles from search (no dedicated Amazon suggestions endpoint found in Kodi plugin)
- `parseContentItems()` updated to extract all new metadata fields

### UI changes (MainActivity.kt / activity_main.xml)
- `EditText` replaced with `AutoCompleteTextView` — shows suggestion dropdown as user types
- 300ms debounce on keystroke calls `getSearchSuggestions()`; results populate dropdown
- Category filter row added: **All | Prime | Freevee | Channels | Live**
- Active category highlighted in blue (#00A8E0); inactive grey (#555)
- Category selection calls `getCategoryContent()` with current search query
- Nav buttons (Home/Watchlist/Library) reset category to ALL

## Phase 8: COMPLETE — Post-launch bug fixes

### Bug 1: Fire TV search keyboard unusable
**Symptom**: Search bar visible but user cannot type — keyboard never appeared or couldn't be dismissed.
**Root causes & fixes**:
1. `AutoCompleteTextView` replaced with custom `DpadEditText` (extends `AppCompatEditText`) — suggestion dropdown interfered with Fire TV IME
2. `DpadEditText.onKeyPreIme()` intercepts BACK key *before* the IME consumes it — `SHOW_FORCED` keyboard now dismisses properly on back press
3. `stateHidden` added to manifest `windowSoftInputMode` — prevents keyboard auto-showing on activity start
4. Keyboard shows only on explicit DPAD_CENTER click via `setOnClickListener` + `showSoftInput(SHOW_FORCED)`
5. `dismissKeyboardAndSearch()` hides keyboard, clears focus to RecyclerView, then triggers search

**Files changed**: `DpadEditText.kt` (new), `MainActivity.kt`, `activity_main.xml`, `AndroidManifest.xml`

### Bug 2: Search returned no results
**Symptom**: Search API called successfully but parser returned 0 items.
**Root causes & fixes**:
1. Search response uses `titles[0].collectionItemList` — parser only handled `collections[].collectionItemList` (home/browse format). Added `titles` array parsing branch.
2. `getAsJsonPrimitive()` throws `ClassCastException` on JSON null values. Added `safeString()` / `safeBoolean()` extension methods on `JsonObject` that return null for both missing and null fields.
3. Search model uses `id` field (not `titleId`) for ASIN. Added `id` as fallback in ASIN extraction chain.

**Files changed**: `AmazonApiService.kt`

### Bug 3: Search results missing poster images
**Symptom**: Only first row (from `collections` format) showed images; search results had blank posters.
**Root cause**: Search model stores images in `titleImageUrls` object (`BOX_ART`, `COVER`, `POSTER` keys) and `heroImageUrl`, not `image.url`.
**Fix**: Added image extraction chain: `image.url` → `imageUrl` → `titleImageUrls.{BOX_ART,COVER,POSTER,LEGACY,WIDE}` → `heroImageUrl` → `titleImageUrl` → `imagePack`
Also fixed `ContentAdapter` to clear images on recycled ViewHolders.

**Files changed**: `AmazonApiService.kt`, `ContentAdapter.kt`

### Bug 4: Player not fullscreen — title bar visible
**Symptom**: "Prime Video" app title bar visible at top during video playback.
**Root causes & fixes**:
1. PlayerActivity used default `Theme.FireTV` which inherits `Theme.AppCompat` (includes ActionBar). Switched to `Theme.FireTV.Player` with parent `Theme.AppCompat.NoActionBar`.
2. Added immersive sticky mode in `onCreate` and `onResume`: `SYSTEM_UI_FLAG_IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION`
3. Explicit `supportActionBar?.hide()` call.

**Files changed**: `PlayerActivity.kt`, `AndroidManifest.xml`, `themes.xml`

## Phase 9: COMPLETE — Watchlist functionality

### Features
- **Long-press to toggle watchlist**: Any content item can be added/removed from watchlist via long-press on the grid
- **Visual watchlist indicator**: Star icon overlay on each content card (filled = in watchlist, outline = not in watchlist)
- **Startup watchlist sync**: App fetches all watchlist ASINs on launch to mark items correctly
- **Optimistic UI**: Watchlist state updates immediately in the adapter after API confirms success
- **Toast feedback**: User sees "Adding to / Removing from / Added to / Removed from watchlist" messages

### API changes (`AmazonApiService.kt`)
- `addToWatchlist(asin)` — calls `AddTitleToList` endpoint
- `removeFromWatchlist(asin)` — calls `RemoveTitleFromList` endpoint
- `getWatchlistAsins()` — fetches watchlist page, extracts ASIN set

### Model changes (`ContentItem.kt`)
- Added `isInWatchlist: Boolean = false` field

### UI changes
- `item_content.xml` — poster wrapped in FrameLayout; added star icon overlay (`iv_watchlist`) at top-right
- `ContentAdapter.kt` — added `onItemLongClick` callback, `watchlistIcon` binding, star on/off based on `isInWatchlist`
- `MainActivity.kt` — added `watchlistAsins` cache, `toggleWatchlist()` method, startup fetch, `showItems()` marks items
- `BrowseActivity.kt` — updated `ContentAdapter` constructor call for new named parameter

### Verified
- 20 watchlist items loaded on startup
- 74 home content items displayed with correct watchlist indicators

## Phase 10: COMPLETE — Library functionality

### Features
- **Library sub-filters**: All / Movies / TV Shows filter chips shown when Library nav is active
- **Sort toggle**: Cycles through Recent / A→Z / Z→A on each click
- **Pagination**: Infinite scroll — loads next page via `libraryNext/v2.js` with `startIndex` when user scrolls near bottom
- **Nav button highlight**: Active nav button (Home/Watchlist/Library) highlighted in blue (#00A8E0)
- **Library-specific UI**: Library filter row shown only on Library page; category filter chips hidden
- **Empty library message**: Shows "Your library is empty. Rent or buy titles to see them here." when no purchased/rented content

### API changes (`AmazonApiService.kt`)
- Added `LibraryFilter` enum: ALL, MOVIES, TV_SHOWS
- Added `LibrarySort` enum: DATE_ADDED, TITLE_AZ, TITLE_ZA
- Added `getLibraryPage(startIndex, filter, sort)` — paginated, filtered, sorted library fetch
- Initial page: `libraryInitial/v2.js`; subsequent pages: `libraryNext/v2.js` with `startIndex` param
- Client-side filtering by contentType (Feature/Movie for Movies, Episode/Season/Series for TV Shows)
- Client-side sorting for title A-Z / Z-A; API default for date added

### UI changes
- `activity_main.xml`:
  - Added `library_filter_row` with `btn_lib_all`, `btn_lib_movies`, `btn_lib_shows`, `btn_lib_sort`
  - Added `id` to `category_filter_row` for visibility toggling
  - Added `nextFocusDown` on search field → `btn_home`
- `MainActivity.kt`:
  - Added `currentNavPage` tracking + `updateNavButtonHighlight()` + `updateFilterRowVisibility()`
  - Added library state: `libraryFilter`, `librarySort`, `libraryNextIndex`, `libraryLoading`
  - Added `setLibraryFilter()`, `cycleLibrarySort()`, `loadLibraryInitial()`, `loadLibraryNextPage()`
  - Added RecyclerView scroll listener for infinite scroll pagination
  - `loadNav("library")` resets filter/sort and calls `loadLibraryInitial()`

### Library API response format (documented)
- Response: `{"resource":{"pageTitle":"Video Library", "refineModel":{"filters":[...],"sorts":[...]}, "titles":[...], "dataWidgetModels":[...]}}`
- Filters from API: TV Shows / Movies / Pay-Per-View
- Sorts from API: Most Recent Addition / Title A-Z / Title Z-A
- Empty library returns `"titles":[]` and `dataWidgetModels` with `"textType":"EMPTY_CUSTOMER_LIST"`

### Verified
- Library endpoint called successfully
- Library sub-filter row (All/Movies/TV Shows/Sort) visible when Library active
- Category filter row hidden when Library active
- Empty library message displayed correctly (account has no purchased content)
- Nav button highlight working (Library button highlighted in blue)

## Phase 11: COMPLETE — Freevee nav + Channels/Live removal

### Changes
- **Freevee nav button added**: New "Freevee" button between Home and Watchlist in the nav row
- **Channels & Live removed**: Removed `btn_cat_channels`, `btn_cat_live`, `btn_cat_freevee` category chips from layout; `ContentCategory` enum simplified from `{ALL, PRIME, FREEVEE, CHANNELS, LIVE}` to `{ALL, PRIME}`; removed `getChannelsPage()` method

### Freevee API (`AmazonApiService.kt`)
- `getFreeveePage()` — tries catalog Browse endpoint with `OfferGroups=B0043YVHMY` (Kodi: common.py:186) for server-side Freevee filtering
- Catalog Browse uses Kodi device TypeIDs (`A3SSWQ04XYPXBH`, `A1S15DUFSI8AUG`, `A1FYY15VCM5WG1`) with `message.body.titles[]` response format
- Falls back to home page content when catalog Browse is unavailable (404 on global US endpoint)
- `parseCatalogBrowseItems()` — parses Kodi-style catalog response format

### Territory detection note
- Territory detection now fully functional — see "Territory Detection Fix" section below
- Freevee (Amazon's free ad-supported service) is not available in all territories (e.g. DE)

### UI changes
- `activity_main.xml`: Added `btn_freevee` nav button; removed Channels/Live/Freevee category chips; category row now only has All + Prime
- `MainActivity.kt`: Added `btnFreevee` binding and click handler; updated `updateNavButtonHighlight()` and `updateFilterRowVisibility()` for freevee page; both filter rows hidden for freevee

### Verified
- Build: `assembleRelease` SUCCESS
- Deploy: APK installed on Fire TV
- Home: 74 items displayed
- Freevee: Falls back to 74 home items (catalog Browse returns 404 in DE territory)
- Watchlist: 20 items displayed
- Library: 0 items (no purchases on account)
- All nav buttons functional with correct highlight

## Phase 12: COMPLETE — Movies/Series filter + Series drill-down

### Content type filtering
- Added `MOVIES` and `SERIES` to `ContentCategory` enum
- Added companion object helpers: `isMovieContentType()`, `isSeriesContentType()`, `isEpisodeContentType()`, `isPlayableType()`
- `getCategoryContent()` updated to filter by content type (MOVIE/Feature for Movies; Season/Series/Show for Series)
- Home page content types: ~49 MOVIE + ~25 SEASON items

### Series drill-down (BrowseActivity)
- Selecting a SEASON item from any page → opens `BrowseActivity` with series detail
- Detail page API: `android/atf/v3.jstl` with `itemId=` param → returns `{show, seasons, episodes, selectedSeason}`
- Parser updated to extract items from `seasons[]` and `episodes[]` arrays in detail response
- Season items: `[titleId, title, seasonNumber, badges, aliases]` — formatted as "Season N"
- Episode items: `[id, linkAction, title, episodeNumber, contentType, ...]` — formatted as "EN: Title"
- Multi-season shows: seasons list → select season → episodes list → select episode → play
- Single-season shows: episodes shown directly (skip season selection)

### ASIN extraction fix
- Episode items from detail page have `titleId` only inside `linkAction` object (not at top level)
- Added `linkAction.titleId` to ASIN extraction chain: `catalogId → compactGti → titleId → linkAction.titleId → id → asin`

### Episode playback fix
- GTI-format ASINs (`amzn1.dv.gti.*`) reject `videoMaterialType=Episode` with `PRSInvalidRequestException`
- `videoMaterialType=Feature` works for both movies AND episodes with GTI ASINs
- Changed PlayerActivity to always use `Feature` materialType

### UI changes
- `activity_main.xml`: Added Movies/Series filter buttons to category row with proper D-pad navigation (`nextFocusDown`)
- `activity_browse.xml`: Added `descendantFocusability="afterDescendants"` for D-pad focus
- `MainActivity.kt`: Added Movies/Series button bindings, series routing to BrowseActivity, grid focus management
- `BrowseActivity.kt`: Complete series drill-down with filter logic, grid focus after item load

### Verified
- Build: `assembleRelease` SUCCESS
- Movies filter: Shows ~49 MOVIE type items from home page
- Series filter: Shows ~25 SEASON type items from home page
- Series drill-down: "Wake Season 1" → detail page → 7 items (seasons + episodes)
- Episode playback: "E1: Wide Awake" plays with 1920x1080 HW secure decode, 2.5-5 Mbps video
- D-pad navigation: Grid items focusable, first child auto-focused after load

## Phase 13: COMPLETE — Watch Progress Tracking

### Implementation
Implemented full watch progress tracking per `dev/analysis/watch-progress-api.md`:

#### AmazonApiService.kt — dual API support
- **UpdateStream** (legacy GET) — enhanced with `titleId`, `timecodeChangeTime` (ISO 8601), `userWatchSessionId`; parses `callbackIntervalInSeconds` from response
- **PES V2** (modern POST) — `pesStartSession()`, `pesUpdateSession()`, `pesStopSession()` at `/cdp/playback/pes/`; ISO 8601 duration format (`PT1H23M45S`); session token management
- `secondsToIsoDuration()` helper for PES V2 timecode format

#### PlayerActivity.kt — stream reporting lifecycle
- `startStreamReporting()` — sends UpdateStream START + PES StartSession on first STATE_READY
- `startHeartbeat()` — periodic PLAY events at server-directed interval (min of UpdateStream and PES intervals)
- `sendProgressEvent()` — dual-API calls (UpdateStream + PES UpdateSession) with interval refresh
- `stopStreamReporting()` — sends STOP to both APIs on STATE_ENDED, onStop(), or player error
- PAUSE support via `onIsPlayingChanged` listener — pauses heartbeat, sends PAUSE event
- Server-directed heartbeat interval via `callbackIntervalInSeconds` response field

### PES V2 note
- PES V2 StartSession returns HTTP 400 — requires `playbackEnvelope` (encrypted playback authorization from Amazon's playback infrastructure) which is not available via GetPlaybackResources
- PES V2 methods are implemented but non-functional without the envelope; falls back gracefully
- **UpdateStream alone is sufficient** for "Continue Watching" / resume position syncing

### Verified on Fire TV (physical device)
- Build: `assembleRelease` SUCCESS
- Deploy: APK installed on Fire TV Stick 4K
- Widevine L1 HW secure playback active (secureSW.SW.audio.raw ~640kbps)
- **UpdateStream START**: `SUCCESS`, `canStream: true`, `statusCallbackIntervalSeconds: 180`
- **UpdateStream PLAY heartbeat**: fires every 60s, `SUCCESS`, position tracks correctly (t=59s)
- **UpdateStream PAUSE**: fires on HOME key press, `SUCCESS` (t=16s)
- **UpdateStream STOP**: fires from `onStop()` lifecycle, `SUCCESS` (t=16s)
- Server-directed interval (`statusCallbackIntervalSeconds: 180`) parsed and applied

## Phase 14: COMPLETE — Audio & Subtitle Track Selection

### Implementation

#### API changes (`AmazonApiService.kt`)
- `extractSubtitleTracks()` — parses `subtitleUrls[]` and `forcedNarratives[]` from GetPlaybackResources response
- Returns list of `SubtitleTrack(url, languageCode, type)` where type is "regular", "sdh", or "forced"
- Already requesting `desiredResources=PlaybackUrls,SubtitleUrls,ForcedNarratives` and `audioTrackId=all`

#### Model changes (`ContentItem.kt`)
- Added `SubtitleTrack` data class (url, languageCode, type)
- Added `subtitleTracks` field to `PlaybackInfo`

#### Player changes (`PlayerActivity.kt`)
- `DefaultTrackSelector` — replaces ExoPlayer's implicit selector; enables programmatic track overrides
- External subtitle tracks loaded via `SingleSampleMediaSource` + `MergingMediaSource` (TTML format) — `SubtitleConfiguration` on `MediaItem` is ignored by `DashMediaSource`
- Audio tracks automatically available from DASH manifest (ExoPlayer parses MPD Adaptation Sets)
- `showTrackSelectionDialog(trackType)` — builds AlertDialog listing available audio or text tracks
  - Audio: shows language + channel layout (5.1, Stereo, etc.)
  - Subtitles: shows language + type label (SDH, Forced); includes "Off" option
  - Applies selection via `TrackSelectionOverride` on `trackSelectionParameters`
- Audio/Subtitle buttons shown at top-right when playback starts (STATE_READY)

#### Layout changes (`activity_player.xml`)
- Added `track_buttons` LinearLayout at top-right with `btn_audio` and `btn_subtitle` buttons
- Semi-transparent black background, white text, D-pad focusable

### Verified
- Build: `assembleRelease` SUCCESS
- Deploy: APK installed on Fire TV Stick 4K
- Video plays with Widevine L1 (audio codec active ~640kbps)
- Track buttons visible during playback, D-pad navigable
- No crashes on button press or track selection

## Phase 15: COMPLETE — In-App Login

### Implementation

#### LoginActivity.kt (new)
- Full Amazon OAuth login flow: email + password → MFA (optional) → device registration → token save
- **PKCE challenge**: SHA-256 code verifier/challenge for OAuth security
- **OAuth sign-in**: POST to `api.amazon.com/ap/signin` with OpenID 2.0 + OAuth 2.0 extension params
- **MFA support**: Detects when Amazon returns MFA challenge; shows OTP input field; resubmits with `otpCode`/`mfaResponse`
- **Device registration**: POST to `api.amazon.com/auth/register` with authorization_code + code_verifier + device fingerprint
- **Token persistence**: Saves TokenData (access_token, refresh_token, device_id, expires_at) to `/data/local/tmp/.device-token`
- **Auto-skip**: If valid token file already exists, skips login and launches MainActivity directly
- **Skip button**: "Use Device Token" fallback for development/debugging when .device-token is pre-pushed via ADB

#### Layout (`activity_login.xml`)
- Email field (textEmailAddress input)
- Password field (textPassword input)
- MFA container (hidden initially, shown when 2FA required)
- Sign In button (blue #00A8E0)
- Status text (errors in red, info in blue, success in green)
- Progress spinner during network calls
- "Use Device Token" skip button (visible only when token file exists)

#### AndroidManifest changes
- `LoginActivity` is now the LAUNCHER activity (entry point)
- `MainActivity` changed to `exported="false"` (launched from LoginActivity after auth)
- Login flow: LoginActivity → (check token / perform login) → MainActivity

#### Compatibility
- `.device-token` file continues to work exactly as before for debugging
- Existing devices with pre-pushed tokens skip login automatically
- New devices show login screen, register via OAuth, then proceed to browse

## Phase 16: COMPLETE — GitHub Actions CI/CD

### Implementation

#### GitHub Actions workflow (`.github/workflows/build.yml`)
- **Triggers**: push to main, pull requests to main, manual workflow_dispatch
- **Build environment**: Ubuntu latest, JDK 17 (Temurin), Android SDK via android-actions/setup-android
- **Date-based versioning**: `YYYY.MM.DD_N` format (e.g., `2026.02.26_1`), auto-increments N per day based on existing git tags
- **Signing**: Decodes `release.keystore` from GitHub Secret (base64), configures signing via environment variables
- **APK output**: Renamed to `FireOS-AVOD-{version}.apk` and uploaded as artifact (90-day retention)
- **Auto-release**: On push to main, creates a GitHub Release with tag `v{version}` and attaches signed APK

#### Build config changes (`app/build.gradle.kts`)
- `versionName` reads from `-PversionNameOverride` Gradle property (default: `1.0-dev`)
- `versionCode` reads from `-PversionCodeOverride` Gradle property (default: `1`)
- Signing config reads keystore path/passwords from environment variables with local fallbacks
- Local development continues to work unchanged (env vars fall back to hardcoded dev values)

#### Required GitHub Secrets
- `RELEASE_KEYSTORE_BASE64` — base64-encoded release.keystore
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — key alias
- `RELEASE_KEY_PASSWORD` — key password

### README updated
- Added all new features (login, track selection, watch progress, resume, CI/CD)
- Updated architecture diagram with LoginActivity
- Added CI/CD section with secrets table and versioning explanation
- Updated deploy instructions with new LoginActivity entry point
- Added authentication section explaining both in-app login and dev token workflows

## Territory Detection Fix (post-Phase 16)

### Root causes found and fixed
1. **`deviceTypeID` mismatch**: `GetAppStartupConfig` was using Kodi's default `A28RQHJKHM2A2W` while our device was registered with `A43PXU4ZN2AL1`. API returned `CDP.Authorization: Device type id in request does not match.`
2. **`supportedLocales=en_US`** — only sent one locale; Kodi sends 18 locales. Amazon requires the user's locale to be listed to return territory info
3. **No `sidomain`** — token refresh was hardcoded to `api.amazon.com`. DE accounts need `api.amazon.de`
4. **`homeRegion` parsed from wrong parent** — was looking in `territoryConfig`, actually under `customerConfig`
5. **Invalid `uxLocale`** — API can return error strings like `LDS_ILLEGAL_ARGUMENT` instead of a valid locale

### Changes
- `AmazonApiService.kt`:
  - `TerritoryInfo` data class replaces `Pair<String, String>` — adds `sidomain` and `lang`
  - `TERRITORY_MAP` expanded with 8 entries including `A2MFUE2XK8ZSSY` (PV EU Alt)
  - `detectTerritory()` rewritten with 3-layer detection (Kodi `login.py:55-78`)
  - `buildDynamicTerritory()` helper for unknown marketplaces (constructs URL from `defaultVideoWebsite` + `homeRegion`)
  - `supportedLocales` sends 18 locales
  - `uxLocale` validated with regex `[a-z]{2}_[A-Z]{2}`
  - `deviceTypeID` uses `AmazonAuthService.DEVICE_TYPE_ID` instead of hardcoded Kodi value
- `AmazonAuthService.kt`:
  - Removed hardcoded `REFRESH_ENDPOINT` constant
  - Added `siDomain` field + `setSiDomain()` setter
  - Token refresh uses `https://api.$siDomain/auth/token`

### Verified on Fire TV
- Territory: `atvUrl=https://atv-ps-eu.amazon.de marketplace=A1PA6795UKMFR9 sidomain=amazon.de lang=de_DE`
- Catalog: 20 watchlist + 74 home items loaded from DE endpoint with `de_DE` locale
- No errors in logcat

## Subtitle, Watchlist & Sorting Fixes (post-Phase 16)

### Subtitle fix
- **Problem**: `DashMediaSource` ignores `MediaItem.SubtitleConfiguration` — external subtitles were added to the `MediaItem` but never loaded by the player
- **Fix**: Use `SingleSampleMediaSource` for each subtitle track, merged with DASH source via `MergingMediaSource`
- **Verified**: 2 TTML subtitle tracks extracted and loaded, `TtmlParser` confirming parsing in logcat

### Watchlist pagination
- **Problem**: `getWatchlistPage()` only loaded `watchlistInitial` (first ~20 items)
- **Root cause**: Used `getDataByTransform` (JS transforms) which only supports initial page; `watchlistNext/v3.js` returns HTTP 500; `watchlistInitial` ignores `startIndex` param causing infinite loop
- **Fix**: Switched to `getDataByJvmTransform` (Kotlin switchblade transforms) matching Prime Video 3.0.438 decompilation:
  - Initial: `dv-android/watchlist/initial/v1.kt` with `pageType=watchlist&pageId=Watchlist`
  - Next: `dv-android/watchlist/next/v1.kt` with `serviceToken` + `pageSize=20` from `paginationModel.parameters`
- Added `extractPaginationParams()` helper that replays all pagination keys from previous response
- Added root-level `collectionItemList` parsing in `parseContentItems()` (next-page response format)
- Added `loadWatchlistInitial()` / `loadWatchlistNextPage()` in `MainActivity` with infinite scroll
- `getWatchlistAsins()` now loads all pages with stall detection and max-page safety limit
- **Verified**: 113 watchlist items loaded across 6 pages (20+20+20+20+20+13), no duplicates

### Duplicate content items
- **Problem**: Home page showed duplicate movies from overlapping collections
- **Fix**: Added `distinctBy { it.asin }` in `parseContentItems()` — reduced home from 74 to 57 items

### Title sorting
- **Problem**: All content pages displayed in API return order (unsorted)
- **Fix**: `showItems()` now sorts all items by `title.lowercase()` before submitting to adapter
- Applies to home, search, watchlist, and freevee pages

## Phase 19: COMPLETE — Home Page Rails UI (Horizontal Carousels)

Replaced the flat alphabetical content grid on the Home tab with categorized horizontal carousels
matching the structure returned by the Amazon v2 landing API.

### New files
- `model/ContentRail.kt` — `data class ContentRail(headerText, items, collectionId, paginationParams)`
- `ui/RailsAdapter.kt` — outer vertical `ListAdapter<ContentRail>` with shared `RecycledViewPool`; each row inflates `item_rail.xml` and wires an inner `ContentAdapter` with `LinearLayoutManager(HORIZONTAL)`
- `res/layout/item_rail.xml` — `LinearLayout(vertical)` containing `TextView` header + horizontal `RecyclerView`

### API changes (`AmazonApiService.kt`)
- **`PRIME_SERVICE_TOKEN`** constant — `eyJ0eXBlIjoibGliIiwibmF2IjpmYWxzZSwiZmlsdGVyIjp7Ik9GRkVSX0ZJTFRFUiI6WyJQUklNRSJdfX0=`
- **`getHomePageRails(paginationParams)`** — hits `dv-android/landing/initial/v2.kt` (first call) or `dv-android/landing/next/v2.kt` (pagination); returns `Pair<List<ContentRail>, String>` (rails + nextPageParams); falls back to v1 flat list if v2 fails
- **`parseRails(json)`** — iterates `collections[]`, extracts `headerText` per collection, calls `parseItemsFromArray()` per `collectionItemList`; extracts `paginationModel` for page-level pagination
- **`parseItemsFromArray(JsonArray)`** — extracted from `parseContentItems()` as shared helper; used by both the flat list and rails parsers
- **`getWatchlistData()`** — returns `Pair<Set<String>, Map<String, Pair<Long,Long>>>` (ASINs + progress map); used to merge watch progress from watchlist data (which has `remainingTimeInSeconds`) into rail items (which do not)
- **Watch progress parsing** — `remainingTimeInSeconds` semantics:
  - `remainSec > 0 && remainSec < runtimeSec` → PARTIAL → `watchProgressMs = runtimeMs - remainSec*1000`
  - `remainSec >= runtimeSec` → not started → `watchProgressMs = 0`
  - `remainSec == 0` → ambiguous ("no data") → `watchProgressMs = 0`
  - timecode fallback: `timecodeSeconds` used if available (detail API format)

### UI changes (`MainActivity.kt`)
- **`isRailsMode`** flag + **`switchToRailsMode()` / `switchToGridMode()`** — swaps `layoutManager` between `LinearLayoutManager(VERTICAL)` and `GridLayoutManager(5)` and swaps adapter
- **`loadHomeRails()`** — fetches v2 rails on IO dispatcher, then calls `showRails()`
- **`loadHomeRailsNextPage()`** — infinite scroll: triggers when last visible rail is within 3 of total, appends new rails; `homePageLoading` guard prevents duplicate requests
- **`showRails(rails)`** — merges watchlist membership, `watchlistProgress` (server-side progress from watchlist data), and local resume positions into each rail's items before submitting to `railsAdapter`
- **`loadNav("home")`** — now calls `loadHomeRails()` instead of `loadFilteredContent()`; all other tabs call `switchToGridMode()` first
- **Filter row** — hidden on Home (server curates carousels); visible on Watchlist; Movies/Series filter chips apply to rails via `applyTypeFilterToRails()`
- **Search** — switching to search on Home calls `switchToGridMode()`; clearing search returns to rails
- **`onResume()`** — refreshes watch progress within rails when returning from player (rails mode)
- **`watchlistProgress`** field — `Map<String, Pair<Long, Long>>` loaded alongside `watchlistAsins` at startup via `getWatchlistData()`

### ContentAdapter changes
- **Progress bar rendering** — switched from custom `@drawable/watch_progress_bar` (didn't render in nested RecyclerView) to `@android:style/Widget.ProgressBar.Horizontal` with `progressTintList`/`progressBackgroundTintList` set programmatically
- Progress bar height increased from 5dp to 8dp for visibility

### Key technical findings
- v2 rails API does **not** include `remainingTimeInSeconds` in its item data — watch progress only available from watchlist API
- v1 home landing returns `remainingTimeInSeconds=0` for items that have real progress in the watchlist API (ambiguous — treat as "no data")
- `remainingTimeInSeconds` reflects time **remaining**, not time **watched** — `watchProgressMs = runtimeMs - remainingSec*1000`
- Custom XML `progressDrawable` does not render in nested RecyclerView-in-RecyclerView context; must use default style + `progressTintList`

### Verified
- Build: `assembleRelease` SUCCESS
- Home tab: vertically stacked horizontal-scrolling carousels with section headers (Featured, Derzeit beliebt, Täglich neue Filme, etc.)
- D-pad: left/right scrolls within a rail, up/down moves between rails
- Scroll down: more rails load via page-level pagination (4 initial + 19 + 6 = 29 total rails)
- Amber progress bars on "Der Tiger" (~27%) and "The Life of Chuck" (~26%) confirmed visible in "Täglich neue Filme" rail
- Movies/Series filter chips apply to rails (filter items by contentType within each rail)
- Watchlist/Library/Freevee/Search tabs use flat grid (unchanged)

## Watch Progress Bars in Grid Views Fix (post-Phase 19)

### Bug
`showItems()` — used by search results, Freevee, Library, and all flat grid views — only merged
local SharedPreferences resume positions into items (`resumeMap[it.asin] ?: it.watchProgressMs`).
It never consulted `watchlistProgress` (the server-side `remainingTimeInSeconds` map built from the
watchlist API at startup). Result: items like "The Tank" correctly showed their watchlist star but
displayed no amber progress bar in search results, even though they had partial watch history.

`showRails()` (home carousels) already had the correct three-way merge; only `showItems()` was missing it.

### Fix (`MainActivity.kt` — `showItems()`)
Applied the same three-way merge that `showRails()` uses:
```
progressMs = localResumePos ?: watchlistProgress[asin]?.first ?: item.watchProgressMs
runtimeMs  = if (watchlistProgress[asin] != null && item.runtimeMs == 0L)
                 watchlistProgress[asin]!!.second
             else item.runtimeMs
```

### Debug finding
`watchlistProgress` contains only items where **both** `watchProgressMs > 0` and `runtimeMs > 0`
after parsing the watchlist API response. Items with a star but no progress bar in the grid are
legitimately unwatched watchlist bookmarks — this is correct behaviour.

### Verified
- `showItems()` now shows amber progress bars for partially-watched items in search results
- `showRails()` was already correct; home rail items with progress continue to show bars
- Watchlist screenshot updated: `screenshots/04_watchlist.png` shows The Tank with amber bar

---

## Phase 20: COMPLETE — About / App Info + Logout

### Features
- ⚙ gear button pushed to the far right of the nav bar via a weighted spacer View
- **About screen** shows: app version (from `PackageManager`), package name, masked device ID (first 8 + last 4 chars), token file location (internal storage vs legacy `/data/local/tmp`)
- **Sign Out button** (red, with confirmation dialog): deletes internal token, sets `logged_out_at` timestamp in `auth` SharedPreferences, clears `resume_positions`, starts LoginActivity with `FLAG_ACTIVITY_CLEAR_TASK`

### Files
- `ui/AboutActivity.kt` — new; reads token via `LoginActivity.findTokenFile()`, shows info, performs logout
- `res/layout/activity_about.xml` — ScrollView with APP and ACCOUNT sections, red Sign Out button
- `activity_main.xml` — added weighted spacer + `btn_about` gear button at right end of nav row
- `MainActivity.kt` — added `btnAbout` field, click → `startActivity(AboutActivity)`
- `AndroidManifest.xml` — registered `AboutActivity` (not exported)

### Logout mechanism (multi-commit)

#### Problem: legacy token cannot be deleted
`File("/data/local/tmp/.device-token").delete()` silently returns `false` — the app process lacks
write permission on `/data/local/tmp` (directory owned by `shell`). Without a guard, LoginActivity's
`findTokenFile()` finds the surviving legacy file and immediately bounces back to MainActivity.

#### Solution: `logged_out_at` timestamp
- `performLogout()` stores `logged_out_at = System.currentTimeMillis()` in `auth` SharedPreferences
- `findTokenFile()` compares legacy file's `lastModified()` against `logged_out_at`:
  - File older than logout → skip (stale token) → return null → login screen
  - File newer than logout → accept (fresh debug token pushed after logout) → clear flag → auto-login
- `launchMain()` clears `logged_out_at` on real login

#### `adb push` mtime pitfall
`adb push` preserves the host file's mtime, so a bare push of a 2026-02-26 file appears older than
a 2026-02-28 logout. Developer must run `adb shell touch` after push:
```bash
adb push .device-token /data/local/tmp/.device-token
adb shell touch /data/local/tmp/.device-token
```

### Verified
- ⚙ button visible top-right on all nav pages
- About screen shows correct version (2026.02.28.x), masked device ID, token location
- Sign Out → confirmation dialog → LoginActivity shown, does NOT bounce back
- Push + touch fresh token after logout → cold restart auto-logins
- Tags: v2026.02.28.2 (initial) … v2026.02.28.4 (timestamp fix)

---

## In-App Login Fix (post-Phase 20)

### Bug
After signing out and trying to log back in via the login form, Amazon returned "Please Enable Cookies
to Continue" (2950-byte response) on the credential POST — every time, not intermittently.

### Root cause
Amazon serves two different login page modes depending on request headers:
- **Browser mode** (default): requires JavaScript-set cookies for CSRF validation → fails in plain HTTP client
- **App mode** (`X-Requested-With: com.amazon.avod.thirdpartyclient` + `x-gasc-enabled: true`): accepts
  the credential POST without JS cookies

The `register_device.py` reference script sets these headers globally on its `requests.Session`.
The `LoginActivity` OkHttp client was only setting `User-Agent` and was not sending `X-Requested-With`
or `x-gasc-enabled`, so Amazon served the browser-mode page whose form submission requires cookies
that only JavaScript can set.

Additional fixes in the same commit:
- Added `Origin: https://www.amazon.com` to the credential POST (browsers send this on form submit)
- Aligned `Accept-Language` value (`en-US,en;q=0.9`) across all login requests

### Fix (`LoginActivity.kt`)
Added an OkHttp application interceptor to the login `httpClient` that appends the two headers to
**every** request in the login flow (homepage, sign-in link, OAuth URL, credential POST):
```kotlin
.addInterceptor { chain ->
    val req = chain.request().newBuilder()
        .header("X-Requested-With", AmazonAuthService.APP_NAME)
        .header("x-gasc-enabled", "true")
        .build()
    chain.proceed(req)
}
```

### Verified
- In-app login now works: email + password → CVF/MFA (if required) → MainActivity
- Sign Out → re-login via form → success
- Tag: v2026.02.28.5

---

## Phase 21: COMPLETE — AI Code Review + Fixes

Full codebase review performed by an AI agent using `dev/REVIEW.md` as the checklist. Findings documented in `dev/review-findings.md`. All actionable warnings fixed.

### Review results
- **0 Critical**, **10 Warnings**, **0 Info**, **47 OK** across 53 checklist items
- Commit: `d1bcc07` — "fix: apply code review findings F-002 through F-010"

### Findings resolved
| Finding | Description | Result |
|---------|-------------|--------|
| F-001 | Catalog GET vs POST | FALSE POSITIVE — POST returns 404; GET is correct |
| F-002 | Password not cleared after login | Fixed — `etPassword.setText("")` before `launchMain()` |
| F-003 | `x-gasc-enabled` in API client | Fixed — removed from `AndroidHeadersInterceptor` |
| F-004 | PlayerActivity scope not cancelled | Fixed — named `scopeJob`, cancel in `onDestroy()`, guard in `setupPlayer()` |
| F-005 | CI keystore not deleted | Fixed — `rm -f release.keystore` with `if: always()` |
| F-006 | versionCode not monotonic within a day | Fixed — derived from full `YYYY.MM.DD.N` version string |
| F-007 | Password trimmed before submission | Fixed — removed `.trim()` from password field |
| F-008 | LoginActivity scope not cancelled | Fixed — named `scopeJob`, added `onDestroy()` |
| F-009 | `showItems()` forced A-Z sort | Fixed — removed `.sortedBy { it.title.lowercase() }` |
| F-010 | `onStop()` calls `player?.stop()` | Fixed — changed to `player?.pause()` |

## Player Overlay Fixes (post-Phase 21)

### Bug 1: AUDIO/SUBTITLES always visible during playback
**Symptom**: The AUDIO and SUBTITLES buttons at top-right were permanently visible once playback started (`STATE_READY` set `View.VISIBLE` and nothing ever hid them again).

**Fix** (`PlayerActivity.kt`):
- Removed `trackButtons.visibility = View.VISIBLE` from the `STATE_READY` handler
- Added `showOverlay(autoHide)` / `hideOverlay()` helpers using `View.postDelayed` + `removeCallbacks`
- `onIsPlayingChanged(true)` → `hideOverlay()` (clean screen during playback)
- `onIsPlayingChanged(false)` + STATE_READY → `showOverlay(autoHide = false)` (stay visible while paused)
- `onKeyDown(KEYCODE_MENU)` → toggles overlay; auto-hides after 3 s if currently playing
- `onDestroy` → `removeCallbacks(hideOverlayRunnable)` to prevent leaks

**Verified on Fire TV**: Overlay hidden during L1 playback (clean black screen); visible after pause (AUDIO + SUBTITLES at top-right, seekbar + play button); hidden again on resume.
Commit: `9b12f75`

---

### Bug 2: AUDIO/SUBTITLES buttons visible but not focusable/selectable during playback
**Symptom**: When the overlay appeared (on MENU press during play), the AUDIO/SUBTITLES buttons showed but could not receive D-pad focus because the `PlayerView` controller was hidden — the two overlay regions were out of sync.

**Root cause**: `trackButtons` was managed independently of `PlayerView`'s built-in controller. When `showOverlay()` made `trackButtons` visible during playback, the player controller remained hidden; without a visible focusable anchor in the controller area, D-pad navigation could not reach the track buttons either.

**Fix** (`PlayerActivity.kt`):
- Added `playerView.setControllerVisibilityListener` → `trackButtons.visibility = visibility` to keep both in sync at all times
- MENU key now calls `playerView.showController()` / `playerView.hideController()` instead of managing `trackButtons` directly — the listener propagates the change automatically
- Removed manual `showOverlay` / `hideOverlay` methods and the `hideOverlayRunnable` / `OVERLAY_TIMEOUT_MS` constants — `PlayerView` owns the auto-hide timing
- `onIsPlayingChanged` stream-reporting logic unchanged; overlay management removed from it entirely

**Behaviour after fix**:
- Pause → `PlayerView` keeps controller visible → listener sets `trackButtons` VISIBLE → both fully focusable via D-pad
- Resume → `PlayerView` auto-hides controller after its timeout → listener sets `trackButtons` GONE
- MENU (during play or pause) → toggles controller → listener toggles track buttons

Verified on Fire TV. Build: 2026.02.28.8

---

## Player UX Fixes (post-Phase 21)

### Audio track menu duplicates
**Symptom**: The Audio dialog listed the same language (e.g. "German (Stereo)") 3-5 times.

**Root cause**: Amazon's DASH manifests use one `AdaptationSet` per bitrate variant (each as a separate ExoPlayer `TrackGroup`), rather than multiple `Representation` elements within one set. The old dialog iterated every track within every group, producing one entry per bitrate per language.

**Fix** (`PlayerActivity.kt` — `showTrackSelectionDialog`):
- One entry per group (bitrate variants within a group are ExoPlayer's ABR responsibility)
- Representative format: currently-playing track, else highest-bitrate track in the group
- Codec qualifier (`· Dolby` / `· AAC`) added only when two groups share the same base label (same language + channel count but different codecs)
- Final label deduplication: when multiple per-bitrate groups produce the same final label, keep the highest-bitrate group (or currently-selected group if any)
- Selection uses `TrackSelectionOverride(group, emptyList())` — adaptive within the chosen group

### Seekbar D-pad seek step
**Symptom**: Left/right D-pad on the seekbar jumped ~6 minutes per press on a 2-hour film.

**Root cause**: `DefaultTimeBar` default key increment = `duration ÷ 20`. For a 7200 s film that is 360 s (6 min).

**Fix** (`PlayerActivity.kt` — `onCreate`):
```kotlin
playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)
    ?.setKeyTimeIncrement(10_000L)  // 10 s per D-pad press
```

**Verified on Fire TV Stick 4K**: both fixes confirmed working (2026.02.28.12).

---

## Watchlist MENU Key Context Menu (post-Phase 21)

### Motivation
Long-press to toggle watchlist is awkward on a TV remote. Replaced with a MENU key (hamburger button) context menu that appears on any focused content card.

### Implementation

#### ContentAdapter.kt
- Added `holder.itemView.tag = item` in `onBindViewHolder` so Activity-level code can retrieve the `ContentItem` from any focused view
- Replaced `onItemLongClick` callback with `onMenuKey` (used both for the item-level key listener and the Activity-level handler)
- `setOnKeyListener` kept as a secondary path (works in some RecyclerView configurations)

#### RailsAdapter.kt
- Renamed `onItemLongClick` → `onMenuKey`, threads through to inner `ContentAdapter`

#### MainActivity.kt
- `showItemMenu(item)`: shows `AlertDialog` titled with the item name; single list item "Add to Watchlist" / "Remove from Watchlist"
- `toggleWatchlist(item)`: now updates **both** the flat adapter AND `unfilteredRails` + `railsAdapter` (previously only updated the flat adapter, leaving home carousels stale after a toggle)
- `onKeyDown(KEYCODE_MENU)`: calls `focusedContentItem()` and shows menu — handles cases where `KEYCODE_MENU` is consumed by the Activity before reaching the view's `setOnKeyListener`
- `focusedContentItem()`: calls `recyclerView.findFocus()` then walks UP the parent chain looking for a view tagged with `ContentItem` — works for both flat grid (direct child) and nested rails (inner RecyclerView child at arbitrary depth)
- `onItemSelected()`: passes `watchlistAsins` to `BrowseActivity` via `EXTRA_WATCHLIST_ASINS` StringArrayList extra

#### BrowseActivity.kt
- Added `EXTRA_WATCHLIST_ASINS` constant + `watchlistAsins: MutableSet<String>` field
- Extracts `watchlistAsins` from Intent in `onCreate`; wires `onMenuKey` callback on the adapter
- `loadDetails()` applies `isInWatchlist` flag to all displayed items after load
- `showItemMenu()` / `toggleWatchlist()`: same AlertDialog + API call pattern as MainActivity; updates local adapter and `watchlistAsins`
- `onItemSelected()` (child BrowseActivity for season → episodes drill-down): passes updated `watchlistAsins` forward via the same extra

### Key technical finding: KEYCODE_MENU dispatch
`View.setOnKeyListener` for `KEYCODE_MENU` is unreliable — Android's `PhoneWindow` intercepts `KEYCODE_MENU` at the window level before key events reach the focused view in some RecyclerView configurations. The robust fix is `Activity.onKeyDown(KEYCODE_MENU)` combined with `recyclerView.findFocus()` + tag-based item lookup, which always fires regardless of the focused view type.

### Fire TV remote key mapping fix (2026.02.28.14)
The Alexa Voice Remote shipped with Fire TV Stick 4K (AFTR/raven) has **no physical Menu button** — `KEYCODE_MENU` (82) is never generated. The MENU-only trigger was therefore silently inert on this device.

**Fix**: restored `setOnLongClickListener` on item views in `ContentAdapter` as the primary trigger (hold D-pad SELECT ~500 ms → `AlertDialog`). `KEYCODE_MENU` retained as secondary for older/3rd-party remotes that do have the button.

### Verified
- Emulator: MENU key → AlertDialog on all three contexts (home rails, flat grid, BrowseActivity)
- Fire TV Stick 4K: long press SELECT → AlertDialog; "Add/Remove from Watchlist" works end-to-end

---

## Phase 22: COMPLETE — UI Redesign

Full visual overhaul of every screen — from functional prototype to a polished, TV-first streaming
experience. Commit: `26eeec6` — "Redesign TV UI" (84 files, +4885 / -1042 lines).

### Delivered

#### Cards & content grid
- **Rounded card surfaces** with elevation shadow and gradient overlay; title always readable
- **Animated focus ring + glow**: `card_focus_glow.xml` + `card_selector.xml`; scale animation on focus
- **Four card variants**: portrait (`item_content.xml`), landscape (`item_content_landscape.xml`),
  episode (`item_content_episode.xml`), season (`item_content_season.xml`)
- **Watch-progress card** (`item_content_progress.xml`) — amber progress bar overlaid on card

#### Navigation & layout
- **Persistent top nav bar** with icon buttons (Home, Search, Watchlist, Library, Freevee, About);
  active tab highlighted with `nav_active_indicator.xml`; D-pad focus chain wired between nav and content
- **Page transition animations** — fade+slide in/out (`page_enter/exit/pop_enter/pop_exit.xml`)
- **Filter chip row** redesigned with pill-shaped `filter_chip_background.xml`

#### Loading states
- **Shimmer skeleton** (`ShimmerAdapter.kt` + `item_shimmer_card.xml`) — shown while API calls
  are in flight; replaced on completion without flicker

#### Detail & browse screens
- **Hero backdrop** redesigned with gradient scrim; metadata badges (4K, HDR, 5.1) via
  `UiMetadataFormatter.kt`; consistent typography hierarchy
- **Browse screen** (`activity_browse.xml`) — full-bleed header background, panel-style grid

#### Player overlay
- **Semi-transparent gradient overlay** (`player_overlay_bg.xml`, `player_overlay_panel.xml`);
  pill-shaped track buttons (`player_track_button.xml`, `player_track_label.xml`); error panel

#### Watchlist
- **Action overlay dialog** (`WatchlistActionOverlay.kt` + `dialog_watchlist_action.xml`) —
  replaces plain AlertDialog with a styled bottom-sheet-style confirmation

#### Infrastructure
- **`ContentItemParser.kt`** — extracted from `AmazonApiService.kt`; testable in isolation;
  covered by `ContentItemParserTest.kt`
- **`UiMetadataFormatter.kt`** — centralises badge/chip label formatting; covered by
  `UiMetadataFormatterTest.kt`
- **`UiMotion.kt`** — `revealFresh()` entry animation helper used on all screens
- **`UiTransitions.kt`** — shared page transition helpers
- **Color palette** (`colors.xml`): unified token set; consistent surface/on-surface/accent colours
- **Dimension tokens** (`dimens.xml`): card sizes, rail spacing, badge padding
- **`integers.xml`**: animation duration constants

---

## Phase 23: COMPLETE — Content Overview / Detail Page

A dedicated overview screen (`DetailActivity`) inserted before playback for every content item.

### API Analysis (conducted 2026-02-28)

**Endpoint used**: `android/atf/v3.jstl` via `getDataByTransform/v1` — the same endpoint already used by `getDetailPage()`. No new endpoint needed.

**Key fields discovered**:
- `resource.synopsis` — full description text ✓
- `resource.detailPageHeroImageUrl` — 16:9 backdrop image ✓
- `resource.imdbRating` (float) + `resource.imdbRatingCount` (int) ✓
- `resource.genres[]` — array (filter entries containing `>` which are sub-genres) ✓
- `resource.releaseDate` — Unix ms timestamp → extract year ✓
- `resource.runtimeSeconds` ✓
- `resource.amazonMaturityRating` (e.g. "13+") ✓
- `resource.directors[]` ✓
- `resource.isTrailerAvailable` (boolean) ✓
- `resource.badges.{uhd, hdr, dolby51, prime}` ✓
- `resource.isInWatchlist` ✓
- For SEASON ASINs: data lives in `resource.selectedSeason.*`, not `resource.*` directly

**Trailer**: `GetPlaybackResources?asin={GTI_ASIN}&videoMaterialType=Trailer` works with the same content ASIN — confirmed returning a valid DASH manifest.

**`dv-android/detail/v2/user/v2.5.js`**: returns HTTP 500 for our account/territory — not used.

**Cast**: Not available in `android/atf/v3.jstl`. Future work if needed.

See `dev/analysis/detail-page-api.md` for full documentation.

### What was implemented

#### New files
- `ui/DetailActivity.kt` — overview screen with: hero image, poster, title, year/runtime/age rating, IMDb rating, genres, synopsis, directors, action buttons
- `res/layout/activity_detail.xml` — FrameLayout root with hero (220dp) + info row (poster + text panel)
- `res/drawable/hero_gradient.xml` — bottom-to-top gradient overlay for hero section
- `model/DetailInfo.kt` — data class for all detail fields
- `dev/analysis/detail-page-api.md` — full API documentation

#### Modified files
- `api/AmazonApiService.kt` — added `getDetailInfo(asin)` + `parseDetailInfo()` using `android/atf/v3.jstl`
- `ui/PlayerActivity.kt` — added `EXTRA_MATERIAL_TYPE` (default `"Feature"`); caller can pass `"Trailer"` for trailer playback
- `ui/MainActivity.kt` — `onItemSelected()` now routes ALL items (movies + series) to `DetailActivity`
- `ui/BrowseActivity.kt` — season selection now routes to `DetailActivity` instead of nested `BrowseActivity`
- `AndroidManifest.xml` — `DetailActivity` registered

### Navigation flow (implemented)
```
Home / Watchlist card (movie)  → DetailActivity → [▶ Play] → PlayerActivity
                                                → [▶ Trailer] → PlayerActivity (Trailer)
Home / Watchlist card (series) → DetailActivity → [Browse Seasons] → BrowseActivity → episodes → play
BrowseActivity (seasons list)  → season card → DetailActivity → [Browse Episodes] → BrowseActivity (episodes)
BrowseActivity (episodes list) → episode card → PlayerActivity (unchanged, direct play)
```

### Key technical findings
- `android/atf/v3.jstl` for MOVIE: all metadata directly in `resource.*`
- `android/atf/v3.jstl` for SEASON: metadata in `resource.selectedSeason.*`; `resource.show` has series titleId/title
- GTI-format ASINs work with `videoMaterialType=Feature` AND `videoMaterialType=Trailer`
- `isTrailerAvailable: true` is reliable — confirmed by actual trailer manifest fetch
- Sub-genre strings like `"Thriller > Mystery"` must be filtered from `genres[]`

### Post-Phase 23 fixes

#### Detail page layout — buttons always visible (v2026.02.28.16)
**Bug**: Action buttons (Play, Trailer, Browse, Watchlist) could be cut off when synopsis text was long — they were in a plain `wrap_content` LinearLayout below the scrollable metadata.
**Fix**: Restructured `activity_detail.xml` — hero image (200dp) at top, info row (poster | NestedScrollView) in the middle with `layout_weight=1`, and a **fixed bottom bar** (`wrap_content`) containing all action buttons. The bottom bar is always rendered regardless of how much text appears above it.

#### "All Seasons" button on season detail pages (v2026.02.28.17)
**Feature**: Season detail pages now show two action buttons: "Browse Episodes" (opens episode list for the current season) and "All Seasons" (opens the season picker for the parent show). Uses `resource.show.titleId` (stored as `DetailInfo.showAsin`) which the API already returns for season ASINs. The button only appears when `showAsin` is non-empty.

### Post-Phase 23 H265/quality + trailer fixes (v2026.02.28.19–29)

#### H265 CDN fallback (v2026.02.28.19)
**Problem**: Some titles return HTTP 400 on H265 init segments (CDN rejects the request for that codec).
**Fix**: `onPlayerError` in `PlayerActivity` detects `ERROR_CODE_IO_BAD_HTTP_STATUS` when `currentQuality.codecOverride` contains `"H265"` and `!h265FallbackAttempted`. Saves resume position, releases player, re-fetches with `PlaybackQuality.HD` (H264 manifest). `h265FallbackAttempted` flag prevents infinite retry.

#### `detectTerritory()` caching (v2026.02.28.20)
**Problem**: H265 fallback was calling `GetAppStartupConfig` again on retry because `territoryDetected` was set but never read.
**Fix**: Added `if (territoryDetected) return` at top of `detectTerritory()`. Eliminates redundant network call on fallback.

#### Video format label in player overlay (v2026.02.28.20)
**Feature**: Added `tv_video_format` TextView to the top-right overlay in `activity_player.xml`. Label shows `"720p · H265 · SDR"` or `"4K · H265 · HDR10"` etc., updated via `updateVideoFormatLabel()`.
**Implementation**: Reads `player.videoFormat` (live decoder format, not the track group seed). Called from `onVideoSizeChanged`, `onTracksChanged`, and `STATE_READY`. Codec from `sampleMimeType` (hevc → H265, avc → H264). HDR from `colorInfo.colorTransfer` or codec string prefix (`hvc1.2.*` = HDR10).

#### Instant H265 fallback via track selector (v2026.02.28.21)
**Attempt**: Tried `setPreferredVideoMimeType(VIDEO_H264)` + re-prepare for faster switching without full manifest re-fetch.
**Reverted**: UHD manifest only contains H264 up to 720p, so track selector fallback yielded 720p H264 from the UHD manifest. Re-fetch with `PlaybackQuality.HD` is correct — it fetches the HD-specific manifest that provides full-tier H264.

#### Quality investigation — Amazon HD tier = 720p cap (v2026.02.28.22–26)
**Finding**: Amazon's `deviceVideoQualityOverride=HD` caps at 720p SDR for both H264 and H265. There is no 1080p SDR tier. 1080p+ requires `UHD` quality + HDR format override.
- HEVC Main profile (`hvc1.1.*`) = SDR H265, available at HD tier, observed at 720p (or occasionally 800p per CDN encoding)
- HEVC Main 10 (`hvc1.2.*`) = HDR10, requires `UHD + Hdr10` quality params

**Final quality presets** (`PlaybackQuality.kt`):
| Preset | quality | codecOverride | hdrOverride | Result |
|--------|---------|---------------|-------------|--------|
| HD (H264) | HD | H264 | None | 720p H264 SDR |
| H265 | HD | H264,H265 | None | 720p H265 SDR (no HDR needed) |
| 4K / DV HDR | UHD | H264,H265 | Hdr10,DolbyVision | 4K H265 HDR (requires HDR display) |

**Documented** in `dev/analysis/decisions.md` Decision 16.

#### Display HDR capability detection (v2026.02.28.25)
**Problem**: Selecting 4K/DV HDR on an SDR TV causes a blank screen (device requests HDR stream, display can't render it).
**Fix**: `displaySupportsHdr()` checks `windowManager.defaultDisplay.hdrCapabilities.supportedHdrTypes`. If empty, `resolveQuality()` falls back UHD_HDR → HD (H264). `AboutActivity.setupQualitySection()` disables 4K/DV button when display has no HDR and shows an explanatory note. Combined capability line: `"Device H265/HEVC: Yes  ·  Display HDR: Yes (HDR10)"`.

#### Clear stale format label on new playback (v2026.02.28.27)
**Problem**: After H265 fallback, the format label retained the H265 value from the previous attempt.
**Fix**: `tvVideoFormat.text = ""` at the top of `loadAndPlay()`.

#### Trailers don't write watch progress (v2026.02.28.28)
**Problem**: Trailer ASIN = movie ASIN; playing a trailer to completion wrote `resumePrefs[asin] = -1` (watched marker), marking the movie as fully watched.
**Fix**: `saveResumePosition()` returns early if `currentMaterialType == "Trailer"`. `STATE_ENDED` handler skips `resumePrefs` write for trailers. `currentMaterialType` field set in `loadAndPlay()`.

#### Trailers start from position 0 (v2026.02.28.29)
**Problem**: `setupPlayer()` read `resumePrefs.getLong(currentAsin, 0L)` — trailers share the movie ASIN and inherited the movie's resume position.
**Fix**: `val resumeMs = if (currentMaterialType == "Trailer") 0L else resumePrefs.getLong(currentAsin, 0L)`.

---

## Phase 24: COMPLETE — Home Rail Source Filter

### Problem

The home tab shows horizontal content rails. The existing Prime / All source filter chips
(`btn_cat_all`, `btn_cat_prime`) are wired only to the **flat-grid** view (Watchlist, Search,
Library). When the Home tab is active the `category_filter_row` is hidden and the filter has no
effect on rails content.

Users want to be able to filter home rails to Prime-only content, matching the behaviour they
already get on the Watchlist tab.

### Scope

| Screen | Filter behaviour today | Target behaviour |
|--------|----------------------|-----------------|
| Home (rails) | No filter visible | Prime / All chips visible, filter items within each rail |
| Watchlist / Library / Freevee / Search (flat grid) | Prime / All chips work | Unchanged |

### Implementation notes

**Show filter row on Home tab**: In `MainActivity.kt`, unhide `category_filter_row` when the
Home tab is selected (currently it is only shown on non-Home tabs).

**Filter rail items client-side**: After `loadHomeRails()` fills the `RailsAdapter`, re-apply
the current source filter (`activeSourceFilter` = `"all"` or `"prime"`) to each rail's item list.
Keep the full unfiltered list in a backing field (`allRailsData`) so toggling the chip does not
require a network re-fetch.

A rail whose items are all filtered out should be hidden entirely (set rail visibility to GONE)
rather than showing an empty horizontal strip.

**Type filter (Movies / Series)**: Same approach — apply the `activeTypeFilter` to rail items.
This is already supported on flat-grid via `ContentItem.contentType`; the same field is available
on rail items.

**Filter field on `ContentItem`**: The `isPrime` flag is already parsed from the catalog response
(check `ContentItem.kt` and the rail parser in `AmazonApiService.kt`). If the field is absent or
not yet parsed, add it during this phase.

**State to preserve**: The filter selection must survive tab switches. `activeSourceFilter` and
`activeTypeFilter` are already tracked in `MainActivity` — no new state needed.

### Files to change

| File | Change |
|------|--------|
| `ui/MainActivity.kt` | Show `category_filter_row` on Home tab; apply filter to `allRailsData` on chip click |
| `ui/RailsAdapter.kt` | Expose a `submitFilteredData(rails)` method; hide empty rails |
| `model/ContentItem.kt` | Confirm `isPrime: Boolean` field exists; add if missing |
| `api/AmazonApiService.kt` | Confirm rail item parser populates `isPrime`; add if missing |

### What was done

- `MainActivity.kt`: `category_filter_row` is now shown on the Home tab as well as flat-grid
  tabs. The full unfiltered rail data is stored in `allRailsData`; applying a filter calls
  `applyRailFilters()` which rebuilds each rail's item list client-side, hiding empty rails.
- `RailsAdapter.kt`: added `submitFilteredData(rails)` that sets visibility `GONE` on rails
  with no items after filtering; outer rail header is also hidden.
- `ContentItem.kt` / `AmazonApiService.kt`: `isPrime` and `isFreeWithAds` already populated —
  no change needed.

### Prime filter false negatives (fix, same commit)

Hero / Featured carousel items use a different JSON schema — they carry Prime entitlement via
`messagePresentationModel.entitlementMessageSlotCompact[].imageId == "ENTITLED_ICON"` with
slot text containing `"prime"`. The `ENTITLED_ICON` alone is not sufficient: it also appears for
channel subscription content (e.g., Paramount+). The `text` field must contain `"prime"` to
distinguish genuine Prime inclusion.

Fix in `ContentItemParser.kt`: `hasEntitledIcon` now requires **both** `imageId ==
"ENTITLED_ICON"` **and** `text.contains("prime", ignoreCase = true)`. This allowed Featured
rail items like *F-Valentine's Day* and *Wilhelm Tell* to pass the Prime filter correctly.

---

## Phase 25: COMPLETE — Player Controls Streamline

### What was done

#### Overlay visibility fix

The custom `track_buttons` overlay originally used `ControllerVisibilityListener` with its own
animation + timeout logic, which drifted out of sync with the Media3 controller — especially when
overlay buttons had focus and the controller auto-hide timer fired, causing flicker and the overlay
lingering after controls hid.

Fix: the overlay now follows the **actual controller visibility** rather than managing its own
lifetime.

- `controllerView` is resolved once via `playerView.findViewById(exo_controller)`.
- `syncTrackButtonsRunnable` polls every 120 ms while the controller is shown, keeping
  `trackButtons` visible only while the real controller view is visible.
- `hideTrackButtonsRunnable` runs immediately when the controller hides — no animation delay,
  no alpha-reset flash.
- Forced `controllerShowTimeoutMs = 0` / focus-listener approach was removed in favour of this
  simpler poll-based sync, which handles all edge cases (focus, dialog dismiss, back-press).
- MENU key still explicitly toggles controls; auto-focus on `btnAudio` was changed to a
  `postDelayed(120 ms)` guard so it only fires once the overlay is confirmed visible.
- Native `exo_subtitle` and `exo_settings` buttons are hidden on startup and re-hidden after every
  `onTracksChanged` (they re-appear on track change).

#### Audio track selection and labelling fix

The original code built the audio menu entirely from live ExoPlayer track metadata, which on
Fire TV is unreliable: blank labels, repeated language groups, repeated bitrate blocks, no stable
index. This caused:
- Audio Description (AD) tracks selected by default on some titles.
- AD tracks disappearing from the menu after an auto-switch attempt.
- Track labels showing language codes instead of human-readable names.

Fix: audio metadata is now sourced primarily from **Amazon's own APIs**, merged with live player
data for selection.

- `AmazonApiService` parses audio track metadata from `GetPlaybackResources` (playback audio
  tracks) and the detail API, including `displayName`, `languageCode`, `type` (dialog /
  descriptive / dialogue-boost), and `index`.
- `PlayerActivity` merges both sources and logs them (`Merged audio metadata: ...`).
- Audio families are normalised as: `main`, `ad`, `boost-medium`, `boost-high`.
- The menu is built by mapping live ExoPlayer groups onto metadata families, keeping the best
  candidate (by selection status, then bitrate) per family.
- AD labelling is metadata-first: if Amazon says `type=descriptive`, the track is labelled AD
  regardless of weak ExoPlayer role flags.
- `normalizeInitialAudioSelection()` replaces the old `autoTrackSelectionDone` auto-switch —
  it runs on first `onTracksChanged`, switches away from AD if selected, and never fires again.
- Language base-code matching (`de` vs `de-de`) ensures live Fire TV tracks can be matched to
  API metadata correctly.
- Channel layout suffixes (`2.0`, `5.1`, `7.1`) are appended when ExoPlayer exposes
  `channelCount`.
- Dialogue Boost entries are filtered out of the menu by default.
- `DefaultTrackSelector` is configured with `setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)` to
  bias initial selection toward main-dialogue tracks.

#### Speed control — not implemented

`player.setPlaybackSpeed()` has no effect on Fire TV: Amazon's EMP (Extras Media Player) system
service intercepts it via a hidden MediaSession proxy and resets speed to 1.0× every ~80 ms for
DRM content. Feature omitted.

### Files changed

| File | Change |
|------|--------|
| `ui/PlayerActivity.kt` | Overlay sync via `syncTrackButtonsRunnable`; audio metadata merge; `buildAudioTrackOptions` using Amazon metadata families; `normalizeInitialAudioSelection`; channel-layout suffix; native button suppression |
| `api/AmazonApiService.kt` | Parse and expose audio track metadata from playback + detail APIs |
| `res/layout/activity_player.xml` | `setShowSubtitleButton(false)` enforced in code |

### Debug logs kept

- `Playback audio tracks asin=...`
- `Detail audio tracks asin=...`
- `Merged audio metadata: ...`
- `Live audio tracks: ...`
- `Audio menu options: ...`

---

## Fix: Prime badge on detail page + accurate Prime detection

### Problem

Two separate issues discovered after Phase 24:

1. **`showPrimeEmblem` is unreliable** — Amazon sets this field to `true` on all catalog season
   items, including seasons available only via channel subscriptions (e.g., The Handmaid's Tale
   Season 6 via Paramount+). Using it as the Prime indicator for the detail page produced false
   positives.

2. **No Prime status shown on detail page** — There was no way to quickly verify whether a title
   is included with Prime from the detail screen; users had to infer it from the home filter.

### Solution

- **`DetailInfo.isPrime: Boolean = false`** — new field added to the model.
- **`AmazonApiService.parseDetailInfo()`** — parses `badges.prime` from the ATF v3 detail
  endpoint response. This field is authoritative: it reflects actual Prime inclusion for the
  requesting account and territory, not just content tagging. Example: *The Handmaid's Tale
  Season 5* returns `badges.prime = true` in Germany (it IS included with Prime); Season 6
  returns `false` (it requires a Paramount+ subscription).
- **`DetailActivity.bindDetail()`** — uses `info.isPrime` (from the detail API) rather than
  the catalog-level `isItemPrime` intent extra that came from `showPrimeEmblem`.
- **`activity_detail.xml`** — added `tv_prime_badge` TextView in the right content panel
  (after IMDb rating). Shows `"✓ Included with Prime"` in teal or `"✗ Not included with
  Prime"` in grey; always visible when the detail page loads.

### Files changed

| File | Change |
|------|--------|
| `model/DetailInfo.kt` | Added `isPrime: Boolean = false` |
| `api/AmazonApiService.kt` | Parse `badges.prime` in `parseDetailInfo()`; pass to `DetailInfo` |
| `res/layout/activity_detail.xml` | Added `tv_prime_badge` TextView |
| `ui/DetailActivity.kt` | Added `tvPrimeBadge` binding; `bindDetail()` uses `info.isPrime` |

---

## Fix: watchlist star state lost on BrowseActivity resume

### Problem

After toggling a watchlist star on a season card in the All Seasons browse grid, the star
updated immediately (API call succeeded, `adapter.submitList` called). However, navigating away
(e.g., into a season detail page) and returning to the All Seasons grid caused all stars to
revert to their pre-toggle state.

### Root cause

`BrowseActivity.onResume()` rebuilt the adapter list with:
```kotlin
currentList.map { it.copy(watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs) }
```
This `.copy()` call preserved `isInWatchlist` from the stale snapshot, effectively overwriting
the in-memory `watchlistAsins` set that `toggleWatchlist()` had already updated.

### Fix

`onResume()` now also syncs `isInWatchlist` from `watchlistAsins`:
```kotlin
val updated = currentList.map { it.copy(
    watchProgressMs = resumeMap[it.asin] ?: it.watchProgressMs,
    isInWatchlist = watchlistAsins.contains(it.asin)
) }
```
The `watchlistAsins` set is always kept current by `toggleWatchlist()`, so this one-liner is
sufficient — no extra API call needed.

### Files changed

| File | Change |
|------|--------|
| `ui/BrowseActivity.kt` | `onResume()` refreshes `isInWatchlist` from `watchlistAsins` |

---

## Phase 26: COMPLETE — Audio Passthrough (Configurable)

### What was built

A user-facing **Audio passthrough** toggle added to the About / Settings screen (PLAYBACK panel,
below the existing video quality section). Persisted as `"audio_passthrough"` (Boolean) in
`SharedPreferences("settings")`. Default: `false` (PCM decode, unchanged behaviour).

#### Root cause addressed

`DefaultRenderersFactory` uses `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` — a stub that
always reports no passthrough support. All AC3/EAC3 audio was decoded to PCM before the Android
audio mixer, so AV receivers never saw a Dolby bitstream even on capable hardware.

Enabling passthrough overrides `buildAudioSink()` to inject a `DefaultAudioSink` built with
`AudioCapabilities.getCapabilities(context)`, which queries real HDMI output capabilities at
player-creation time.

#### Files changed

| File | Change |
|------|--------|
| `res/layout/activity_about.xml` | Added audio passthrough sub-section inside PLAYBACK panel: divider, title, `tv_passthrough_badge`, `tv_passthrough_support`, Off/On `AppCompatButton` row, `tv_passthrough_note` |
| `ui/AboutActivity.kt` | `setupAudioPassthroughSection()` — queries live HDMI caps (`AudioCapabilities.getCapabilities`), sets badge (`AC3 + EAC3 capable` / `Passthrough unavailable`), disables/dims On button when output has no support, reads/saves pref, Toast on change |
| `ui/PlayerActivity.kt` | Constants `PREF_AUDIO_PASSTHROUGH`, `PREF_AUDIO_PASSTHROUGH_WARNED` in companion object; `setupPlayer()` reads pref and builds passthrough `DefaultRenderersFactory` subclass overriding `buildAudioSink()`; one-time Toast warning gated by `PREF_AUDIO_PASSTHROUGH_WARNED` |

#### Key implementation detail

`DefaultRenderersFactory` subclass (anonymous object) overrides `buildAudioSink()`:

```kotlin
object : DefaultRenderersFactory(this) {
    init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF) }
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
        .build()
}
```

#### System constraint

Fire TV has a system-level "Dolby Digital Plus" toggle (Settings → Display & Sounds → Audio).
If disabled there, `AudioCapabilities.getCapabilities()` reports no support and the On button
is greyed out — the app setting has no effect beyond what the OS permits.

#### API used (Media3 1.3.1 — no new dependency)

- `DefaultRenderersFactory.buildAudioSink(Context, Boolean, Boolean)` — protected, overridden
- `AudioCapabilities.getCapabilities(Context)` — live HDMI query
- `AudioCapabilities.supportsEncoding(Int)` — badge check (`C.ENCODING_AC3` / `C.ENCODING_E_AC3`)
- `DefaultAudioSink.Builder(Context).setAudioCapabilities(…).build()`

---

## Phase 26 post-fixes — Settings UX Polish

### Search icon clipping fix

**Problem:** The header search button in `activity_main.xml` used the literal emoji `🔍` as
button text. On Fire TV / Android TV launchers and TV fonts, emoji glyph metrics rendered
clipped or vertically off-center.

**Fix:**
- Replaced `<Button>` with `<ImageButton>` using `src=@drawable/ic_search`
- Added `ic_search.xml` vector drawable (standard Material magnifier path)
- `scaleType=centerInside`, `padding=10dp`, same `header_icon_button_background` and tint
- `MainActivity.kt`: `btnSearchIcon` changed from `Button` to `ImageButton`
- `strings.xml`: added `search_button_icon = "Open search"` for accessibility

Icon now renders via vector paths — no dependency on font glyph metrics or emoji rendering.

### Settings button state rendering fix (MaterialButton → AppCompatButton)

**Problem:** The About screen PLAYBACK and ACTIONS buttons (`btn_quality_*`, `btn_passthrough_*`,
`btn_sign_out`) showed identical appearance for focused and selected states despite different
drawables being defined.

**Root cause:** The app theme (`Theme.MaterialComponents.NoActionBar`) maps `<Button>` to
`MaterialButton`, which applies its own `colorPrimary` (`#00A8E0`) tint via the Material tint
system on top of the background drawable. Setting `android:backgroundTint="@null"` alone does
not disable the Material-namespace `app:backgroundTint`, so the drawable state-list was being
overridden for all interactive states.

**Fix:** Changed all six option buttons in `activity_about.xml` to
`<androidx.appcompat.widget.AppCompatButton>`. `AppCompatButton` does not participate in the
Material tint system and uses `android:background` as the sole styling authority, allowing the
state-list drawable (`settings_quality_option_background.xml`) to fully control appearance.

**State-list redesign (same file):**

| State | Fill | Border | Text | Meaning |
|---|---|---|---|---|
| Rest | `#0E1820` near-black | 1dp `#1D2D36` | `#6E8590` muted | inactive |
| Selected | `#1B7A9E` vivid teal | 2dp `#2DC8EC` | `#FFFFFF` | active setting |
| Focused | `#131F28` dark | 3dp `#5BCCE6` | `#FFFFFF` | cursor ring |
| Focused + Selected | `#4FC0DF` bright teal | 3dp `#FFFFFF` | `#061117` | active + cursor |
| Disabled | `#0C1318` | 1dp `#172028` | `#3D5260` | unavailable |

### D-pad focus navigation fix

`btn_passthrough_off` and `btn_passthrough_on` had `nextFocusUp="@id/btn_about_back"` (copied
from the quality buttons), causing Up from the passthrough row to jump to the page Back button
instead of the quality row above it.

**Fix:** `btn_passthrough_off` → `nextFocusUp="@id/btn_quality_hd"` (left-aligned),
`btn_passthrough_on` → `nextFocusUp="@id/btn_quality_uhd"` (right-aligned).

---

## Phase 27: PENDING — Full AI Code Review

### Goal

**Primary**: Verify correctness, safety, and stability of everything added since Phase 21.
**Secondary**: Assess overall maintainability — flag anything that would be hard to understand,
extend, or debug by a developer unfamiliar with the codebase. This is not just a bug hunt;
the reviewer should flag unclear abstractions, dead code, missing error surfaces, inconsistent
patterns, and anything that would slow down future development.

### Scope — files added or substantially rewritten since Phase 21

| File | Changed in phase | Key new surface |
|------|-----------------|----------------|
| `BrowseActivity.kt` | 19 | Series → season → episode drill-down |
| `RailsAdapter.kt` | 19 | Outer vertical ListAdapter for home carousels |
| `CardPresentation.kt` | 19 | Focus scale animation, card variants |
| `ShimmerAdapter.kt` | 22 | Skeleton loading animation |
| `UiMotion.kt` | 22 | ValueAnimator / ObjectAnimator helpers |
| `UiTransitions.kt` | 22 | Activity-level fade/slide transitions |
| `DpadEditText.kt` | 22 | Fire TV remote keyboard interception |
| `DetailActivity.kt` | 23 | Hero image, metadata, watchlist toggle, trailer |
| `AboutActivity.kt` | 20, 26 | Quality section, audio passthrough section |
| `MainActivity.kt` | 19, 24 | Rail filter, tab nav, shimmer integration |
| `PlayerActivity.kt` | 21–26 | Overlay sync, H265 fallback, format label, passthrough renderer |
| `activity_about.xml` | 26 | AppCompatButton state-list buttons, passthrough section |
| `settings_quality_option_background.xml` | 26 | State-list drawable (rest/selected/focused/focused+selected) |

This is a **full pass**, not incremental. The reviewer must read the actual source, not rely on
summaries.

---

### Checklist

#### 1. Security & auth
- [ ] No token or credential values logged at any level in new activities or adapters
- [ ] `SharedPreferences("auth")` not accessed outside `LoginActivity` / `AboutActivity`
- [ ] `DpadEditText` — typed content not logged (search queries, login fields)
- [ ] `DetailActivity` intent extras validated before use (ASIN, title not trusted as safe)
- [ ] `AboutActivity` — Sign Out cannot be triggered accidentally (confirmation dialog present)

#### 2. Memory & lifecycle
- [ ] `RailsAdapter` / `ContentAdapter` — no anonymous `Handler` or `Runnable` retaining `Activity` context after detach
- [ ] `ShimmerAdapter` — animation drawables / animators released in `onViewRecycled` or `onDetachedFromRecyclerView`
- [ ] `UiMotion` / `UiTransitions` — `ValueAnimator` / `ObjectAnimator` cancelled when target view is detached
- [ ] `PlayerActivity` — coroutine `scopeJob` cancelled before re-creating player on H265 fallback path
- [ ] `DetailActivity` — coroutine scope cancelled in `onDestroy`; image loading cancelled on destroy
- [ ] `PlayerActivity` — no stale reference to released `ExoPlayer` instance after `releasePlayer()`

#### 3. Network & API
- [ ] No network calls dispatched on the main thread in any new code path
- [ ] Pagination / infinite scroll guards against duplicate in-flight requests
- [ ] `DetailActivity` — all API errors surfaced to the user; no silent swallowing
- [ ] `ShimmerAdapter` shimmer hidden on both success and error (no infinite skeleton)
- [ ] `RailsAdapter` — pagination token not reused after end-of-feed

#### 4. D-pad / TV UX
- [ ] All new interactive views declare `android:focusable="true"` and `android:focusableInTouchMode="false"`
- [ ] `DpadEditText` — correct `imeOptions` and `inputType` for TV on-screen keyboard
- [ ] `RailsAdapter` rail items — `nextFocusDown` from last rail row leads somewhere sensible (not into void)
- [ ] `DetailActivity` — every interactive element reachable by D-pad (metadata scroll, play, trailer, watchlist, seasons)
- [ ] `CardPresentation` — focus scale animation leaves no views in a permanently scaled-up state after fast scrolling or rapid focus changes
- [ ] `AboutActivity` — full D-pad traversal: Back → quality buttons → passthrough buttons → Sign Out, all reachable; Up from quality row goes to Back; Up from passthrough row goes to quality row

#### 5. Correctness & edge cases
- [ ] `PlaybackQuality.fromPrefValue` — safe fallback for unknown or null pref values
- [ ] `PlayerActivity` — `h265FallbackAttempted` flag reset on each `setupPlayer()` call so it does not persist across content items in the same session
- [ ] `MainActivity` — `activeSourceFilter` / `activeTypeFilter` preserved or reset correctly on back-stack pop
- [ ] `UiMotion.revealFresh` — handles views already `VISIBLE` without flicker
- [ ] `PREF_AUDIO_PASSTHROUGH` read inside `setupPlayer()` at player-creation time, not cached at activity start
- [ ] Volume warning Toast fires at most once across all sessions (gated by `PREF_AUDIO_PASSTHROUGH_WARNED`)
- [ ] `AboutActivity` — passthrough On button correctly disabled and dimmed when `supportsAny == false`; pref not saved as `true` when capability is absent
- [ ] `settings_quality_option_background.xml` — state order correct: `focused+selected` before `selected` before `focused` before default; disabled before all

#### 6. Maintainability (new goal)
This section assesses whether the codebase is **easy to understand, extend, and debug** — not
just whether it works today.

- [ ] **Naming**: classes, functions, and variables named to convey intent without requiring inline comments
- [ ] **Separation of concerns**: UI logic not mixed into adapters; API parsing not mixed into Activities
- [ ] **Dead code**: no commented-out blocks, unused functions, unreachable branches, or stale TODOs left in production files
- [ ] **Consistency**: similar problems solved the same way throughout (e.g. coroutine scope management, error display, pref keys)
- [ ] **Magic values**: no unexplained hardcoded strings, numbers, or colours inline in Kotlin — constants or resources used throughout
- [ ] **Error messages**: error Toasts / dialogs give the user (and developer reading logcat) enough context to understand what went wrong
- [ ] **Complexity**: no function longer than ~60 lines or with cyclomatic complexity that would make it hard to test mentally; flag candidates for extraction
- [ ] **Logging**: sufficient `Log.w` / `Log.e` at key decision points (player state changes, fallback triggers, auth events) that on-device debugging via `adb logcat` is practical; no noisy or redundant log lines
- [ ] **SharedPreferences key hygiene**: all pref keys defined as constants (not inline string literals scattered across files); keys in `PlayerActivity`, `AboutActivity`, `MainActivity` consistent and documented
- [ ] **Drawable / resource consistency**: `AppCompatButton` used consistently for all state-list-driven buttons; no remaining `<Button>` elements that rely on drawable state-lists in `Theme.MaterialComponents` activities

---

### Output

Write findings to `dev/review/review-findings-p27.md` using the format:

```
## [Severity] Short title
File: path/to/File.kt:line
Issue: …
Suggestion: …
```

Severity levels: **Critical** (correctness / security break), **Warning** (likely bug or
maintainability blocker), **Info** (minor improvement), **OK** (verified clean).

All **Critical** and **Warning** findings must be fixed before Phase 27 is marked COMPLETE.

### Definition of done

- `dev/review/review-findings-p27.md` exists with every checklist item assessed
- 0 Critical, 0 unresolved Warning findings
- Fix commit(s) SHA referenced in this section

---

## Phase 28: COMPLETE — Widevine L3 / SD quality fallback

### Problem

Playback on Android emulators (and any un-provisioned hardware) failed with a DRM license
error.  The root cause was confirmed by analysing the decompiled Amazon Prime APK
(`prime-3.0.412.2947-smali/`):

- Amazon's license server enforces: **HD + Widevine L3 + no HDCP → license DENIED**
- SD quality bypasses the restriction: **SD + L3 + no HDCP → license GRANTED**
- The official APK's `ConfigurablePlaybackSupportEvaluator` queries `HdcpLevelProvider` and
  automatically falls back to SD when `HDCP = NO_HDCP_SUPPORT` is detected.
- Our code hardcoded HD quality regardless of device capability, so every emulator playback
  attempt returned a license denial.

### Solution (mirrors official APK behaviour)

Query `MediaDrm.getPropertyString("securityLevel")` before player creation.
If the result is not `"L1"`, force `PlaybackQuality.SD` for the session regardless of the
user's quality preference — the license server will not grant HD to an L3 device.

A one-time Toast informs the user on first L3 detection; subsequent plays are silent.

### Files changed

- **`model/PlaybackQuality.kt`** — added `SD` preset (`"SD"`, `"H264"`, `"None"`) with doc
  comment explaining the license server enforcement rule.  `videoQuality` comment updated to
  list `"SD"` as a valid value.
- **`ui/PlayerActivity.kt`**:
  - `import android.media.MediaDrm` added
  - `PREF_WIDEVINE_L3_WARNED` constant added to companion object
  - `widevineSecurityLevel()` helper: opens `MediaDrm(WIDEVINE_UUID)`, reads `"securityLevel"`;
    falls back to `"L3"` on any exception (safe-fail)
  - `resolveQuality()`: L3 gate inserted before the user-preference check; returns `SD` when
    security level is not `"L1"`; fires one-time Toast gated by `PREF_WIDEVINE_L3_WARNED`
  - `updatePlaybackStatus()`: `PlaybackQuality.SD` case maps to label `"SD (Widevine L3)"`
  - Cleaned up redundant `android.widget.Toast` fully-qualified references in `resolveQuality()`
    (now uses the already-imported `Toast`)

### Result

- **Emulator**: playback now succeeds (SD quality, H264 manifest)
- **Fire TV (L1)**: unaffected — L3 gate passes immediately, existing quality logic runs
- **Decision 22** added to `decisions.md`

## Phase 29: COMPLETE — Continue Watching row

### What was built

A **Continue Watching** rail prepended to the home screen rails list, built entirely from
server-side watchlist progress data (no local tracking).

### Data source investigation

Smali analysis of the decompiled Prime Video APK (`ContinueWatchingCarouselProvider`) confirmed:
- Amazon's own CW row reads from a local SQLite `UserActivityHistory` database populated during
  playback — **no server read endpoint** exists for in-progress item data.
- The watchlist API never returns episode-level items.
- The v1/v2 home page APIs return editorial content, not personalised in-progress data.

**Decision**: Use watchlist API progress only (`watchProgressMs > 0 && runtimeMs > 0`).

### Changes

- **`AmazonApiService.getWatchlistData()`**: returns a `Triple<Set<String>, Map<String, Pair<Long,Long>>, List<ContentItem>>` — the third element is `watchlistInProgressItems` (movies/series with server-confirmed watch progress). Also calls `getHomePage()` to supplement `progressMap` for non-watchlist items (including in-progress episodes from the v1 home page).
- **`ContentItemParser.kt`**: fixed `ClassCastException` — `getAsJsonArray("entitlementMessageSlotCompact")` crashed on `JsonNull` fields; replaced with `safeArray()`.
- **`MainActivity.kt`**:
  - Added `watchlistInProgressItems: List<ContentItem>` field
  - Added `buildContinueWatchingRail()` — combines `watchlistInProgressItems` + in-progress items from `unfilteredRails`
  - CW rail prepended to `displayList` in `showRails()`, `loadHomeRailsNextPage()`, `applyRailsFilters()`, and `onResume()` — after `applyAllFiltersToRails()` so the row bypasses filters
  - `updateHomeFeaturedStrip()` overrides meta line to `progressSubtitle()` when CW is the first rail
  - Removed all `SharedPreferences("resume_positions")` reads from MainActivity (5 locations) — progress is now server-sourced only
- **`RailsAdapter.kt`**: store `contentAdapter` + `boundPresentation` in `RailViewHolder`; reuse the adapter on rebind if presentation matches (eliminates async-diff empty-frame flicker); added `onViewRecycled()` to clear the inner adapter list; pool contamination fix applied in `ContentAdapter`.
- **`ContentAdapter.kt`**: added `getItemViewType()` returning `presentation.ordinal` — prevents cross-presentation holder reuse via the shared `RecycledViewPool`.

### Result

- Home screen shows "Continue Watching" as the first rail with amber progress bars on all cards
- Hero strip shows "CONTINUE WATCHING" eyebrow + "X% watched · Y min left" meta line
- Source/type filters do not hide the CW row
- Progress bars render correctly on all cards including position 0 (first item)
- **Decision 23** added to `decisions.md`

---

## Phase 29 post-fixes — Server-sourced resume position

### Motivation

The player stability pass (pre-Phase 29) had added local `SharedPreferences("resume_positions")`
writes to `PlayerActivity` — periodic saves every 30 s via `resumeProgressRunnable`, forced saves
on pause/stop/seek/error — so that resume position persisted across cold starts. This duplicated
the server's own position tracking (UpdateStream / PES V2 heartbeats already report position
continuously) and used a different storage path from the `watchlistProgress` map that drives
progress bars and the Continue Watching row: a title watched partially via local storage would
show no progress bar on the home screen until the watchlist API was re-queried.

`BrowseActivity` also had two local reads that were missed in the initial removal.

### Approach

Remove all local resume storage from `PlayerActivity` and `BrowseActivity`. Resume position is
now passed by callers via intent extras sourced from the server `watchlistProgress` map.
The existing `UpdateStream` + PES V2 heartbeat logic (START/PLAY/PAUSE/STOP) that reports
position to the server is fully intact; `remainingTimeInSeconds` in the watchlist API reflects
the last reported position after a session.

The v1 home page supplement in `getWatchlistData()` already covers **episodes**: Amazon's watchlist
API skips episode-level items, but in-progress episodes appear in the v1 landing page with
`remainingTimeInSeconds > 0` and are merged into `watchlistProgress` at startup.

### Changes

**`PlayerActivity.kt`**:
- Removed fields: `resumePrefs`, `lastResumeSaveElapsedMs`, `resumeProgressRunnable`
- Removed methods: `persistPlaybackProgress()`, `saveResumePosition()`, `startResumeProgressUpdates()`, `stopResumeProgressUpdates()`
- Removed ~12 call sites across `playerListener`, `startStreamReporting`, `stopStreamReporting`, `onPause`, `onStop`, `onDestroy`
- Added `h265FallbackPositionMs: Long` — holds live position when H265 CDN returns 400; player restart uses this over the intent extra and resets it to 0 after use
- `setupPlayer()` reads resume from `intent.getLongExtra(EXTRA_RESUME_MS, 0L)`
- Added `EXTRA_RESUME_MS = "extra_resume_ms"` constant
- **Server-side UpdateStream / PES V2 reporting unchanged**

**Intent chain for resume position (`EXTRA_RESUME_MS`)**:

| Caller | Value | Destination |
|--------|-------|-------------|
| `MainActivity.onItemSelected()` | `watchlistProgress[asin]?.first ?: item.watchProgressMs` | `DetailActivity` |
| `DetailActivity.onPlayClicked()` | `serverResumeMs` (from own intent) | `PlayerActivity` |
| `BrowseActivity` episode play | `item.watchProgressMs` | `PlayerActivity` |

**`BrowseActivity.kt`**:
- Removed both local `SharedPreferences("resume_positions")` reads (initial load + `onResume`)
- Added `EXTRA_PROGRESS_MAP` — receives `HashMap<String, Long>` (ASIN → progressMs) from callers
- Initial load merges `serverProgressMap` into episode items
- `onResume` after player return: refreshes watchlist star state only (no local progress update)

**`DetailActivity.kt`**:
- Added `EXTRA_RESUME_MS` and `EXTRA_PROGRESS_MAP` constants
- Reads both from intent; forwards `EXTRA_PROGRESS_MAP` to both BrowseActivity launches

**`MainActivity.kt`**:
- Passes `HashMap(watchlistProgress.mapValues { it.value.first })` as `DetailActivity.EXTRA_PROGRESS_MAP`

### Resume support matrix (current)

| Scenario | Resume works? |
|---|---|
| Movie or series in watchlist | ✓ server `remainingTimeInSeconds` |
| Episode (series in watchlist) | ✓ v1 home page supplement |
| Title not in watchlist | ✗ no server data, no local fallback (→ Phase 30) |
| H265 CDN fallback (H264 restart) | ✓ `h265FallbackPositionMs` instance variable |
| Trailer | ✗ always starts from 0 |

### Commits

- `2aa4638` — `refactor: replace local resume-position SharedPreferences with server-sourced intent extra`
- `ad55485` — `fix: remove local SharedPreferences resume reads from BrowseActivity, use server progress`

---

## Phase 30: COMPLETE — Centralized Progress Repository

### What was built

Phase 30 replaces the old progress intent chain with a single `ProgressRepository` used by
Home, Browse, Detail, Player, and logout handling.

### Implementation

**New singleton**:
- `data/ProgressRepository.kt`
  - in-memory ASIN → `(positionMs, runtimeMs)` map
  - `SharedPreferences("progress_cache")` persistence
  - `refresh()`, `update()`, `get()`, `getInProgressItems()`, `getInProgressEntries()`, `clear()`

**Player integration**:
- `PlayerActivity` now:
  - reads initial resume from `ProgressRepository.get(asin)`
  - writes progress back via `ProgressRepository.update(...)`
  - persists progress every 30 seconds during playback and on pause/stop/seek/error

**Screen integration**:
- `MainActivity`, `BrowseActivity`, and `DetailActivity` no longer pass progress through
  `EXTRA_RESUME_MS` / `EXTRA_PROGRESS_MAP`
- Home and Browse re-read progress directly from the repository
- About/logout clears the repository cache

**Continue Watching expansion**:
- CW still prefers server-backed in-progress items
- Home now also resolves a small capped set of local-only in-progress ASINs by detail lookup and
  can surface them in Continue Watching without introducing a full metadata repository

### Merge behavior

**Refresh policy**:
1. local cached entries are loaded from `SharedPreferences("progress_cache")`
2. server progress from `getWatchlistData()` is applied over that map
3. during active playback after refresh, local writes can become newer again

**Effective rule**:
- startup / explicit refresh: **server wins**
- active playback after refresh: **local writes win until next refresh**

### Result

- `ProgressRepository` is now the single progress source used by all main screens
- resume no longer depends on intent pass-through
- Browse/Home can pick up updated progress after returning from playback
- non-watchlist titles with local progress can now appear in Continue Watching if their ASIN can
  be resolved through the detail API

### Validation

- `./gradlew assembleRelease` passed
- emulator smoke:
  - `MainActivity` resumed cleanly after cold launch
  - `ProgressRepository` refresh succeeded (`entries=6, inProgressItems=5`)
  - `Continue Watching` rail rendered on Home
  - repository contained at least one extra local-only entry beyond the server-backed in-progress
    set, confirming the fallback path is meaningful for this account

### Follow-up limitation kept

- there is still no trustworthy backend `lastUpdatedAt`, so cross-device conflict resolution is
  still server-first on refresh rather than true newest-wins

### Post-completion fix — Continue Watching direct-play + server-backed episode resume

After Phase 30 landed, one regression remained:
- Continue Watching clicks always went through `DetailActivity`
- server-backed episode progress could be visible on the CW card but lost at playback start if the
  local cache had been cleared

**Fix**:
- `MainActivity` / `BrowseActivity` / `DetailActivity` now pass `PlayerActivity.EXTRA_RESUME_MS`
  when they already have a visible resume position for the selected item
- `PlayerActivity` now prefers:
  - H265 fallback resume
  - explicit intent resume
  - repository resume
- Home Continue Watching is now rail-aware:
  - movies / episodes direct-play
  - series / seasons still open overview flows
- local-only CW metadata resolution now prefers `getDetailPage(asin)` exact-item matches before
  falling back to `getDetailInfo(asin)`, which helps preserve episode identity

**Validation**:
- emulator: Continue Watching movie direct-play opened `PlayerActivity` directly
- Fire TV: `Fallout` episode resume worked again from server-backed progress even after local
  cache was cleared

---

## Phase 31: COMPLETE — Minor UI Improvements

Four focused polish items spanning the detail page, home hero strip, content card labels, and
the player seekbar.

---

### Item 1: Detail page — watch progress + distinct Play/Resume/Trailer icons

#### 1a. Watch progress on the detail page

**Goal**: When the user opens a detail page for a title they have partially watched, show a
progress indicator so they can see where they left off before tapping Play.

**What to show**:
- An amber horizontal `ProgressBar` directly below the hero image or above the Play button area
- A text label using the same format as card subtitles: `"X% watched · Y min left"` or `"Resume from Xh Ym"`
- When `watchProgressMs == -1L` or fully watched (≥ 90%): show `"Finished recently"` and no bar

**Data source**: `ProgressRepository.get(asin)` is already called in `onCreate()` (the
`serverResumeMs` field stores the intent-passed resume ms; fall back to repository if not set).
Runtime is available from `DetailInfo.runtimeSeconds * 1000`.

**Files to change**:

| File | Change |
|------|--------|
| `res/layout/activity_detail.xml` | Add `ProgressBar` (horizontal, amber tint, 0dp height=4dp) + `TextView` above the button row |
| `ui/DetailActivity.kt` | Bind both views; after `loadDetail()` resolves `DetailInfo`, call `bindProgress()` which reads `ProgressRepository.get(asin)` and either shows or hides the bar |
| `ui/UiMetadataFormatter.kt` | Add `detailProgressLine(posMs, runtimeMs): String?` — same logic as `progressSubtitle()` but returns null when no progress |

**Edge cases**:
- `runtimeMs == 0L`: hide progress bar (can't draw a meaningful fraction)
- `watchProgressMs > 0L && runtimeMs > 0L && fraction >= 0.9`: show "Finished recently", no bar
- `materialType == "Trailer"`: never show resume state

#### 1b. Distinct icons for Play and Trailer buttons

**Goal**: The two buttons currently differ only in text. Make them visually distinct at a glance.

| Button | Current | Target |
|--------|---------|--------|
| Play / Resume | Text only | Leading `▶` vector icon (filled play triangle) |
| Trailer | Text only | Leading `◻` / filmstrip-style icon (hollow play or clapper icon) |

**Implementation**:
- Add `ic_play_filled.xml` vector drawable (standard Material `play_arrow` path, 20dp)
- Add `ic_trailer.xml` vector drawable (outline play circle or `movie` icon, 20dp)
- Set `android:drawableStart` on each `AppCompatButton` in `activity_detail.xml`
- In `DetailActivity.bindDetail()`, swap `btnPlay` text to `"▶ Resume"` when `posMs > 10_000`,
  `"▶ Play"` otherwise (icon stays the same; text signals resume vs fresh start)
- `btnTrailer` always shows its film icon regardless of progress

---

### Item 2: Progress bar on the home screen hero strip

**Goal**: The featured hero image at the top of the home screen should show an amber progress
bar at the bottom of the image when the featured item has watch progress, matching the progress
bar on the Continue Watching rail cards.

**Current state**: The hero `FrameLayout` (`home_featured_strip`) has a hero image, gradient
overlay, eyebrow/title/meta `TextViews`, and a click handler. No progress indication.

**Implementation**:

| File | Change |
|------|--------|
| `res/layout/activity_main.xml` | Add `ProgressBar` (horizontal, `layout_gravity="bottom"`, full width, 4dp height, amber tint, `visibility="gone"`) inside `home_featured_strip` FrameLayout, z-ordered above the gradient overlay |
| `ui/MainActivity.kt` | In `updateHomeFeaturedStrip()`: after resolving `nonNullFeaturedItem`, call `ProgressRepository.get(item.asin)` and set bar progress / visibility. Use `max=1000`, `progress=(posMs * 1000 / runtimeMs).toInt()`. Hide when `watchProgressMs == 0L` or `runtimeMs == 0L` |

**Note**: The bar should be shown for the CW hero (which always has progress) AND for any other
featured item that happens to have partial progress.

---

### Item 3: Remove misleading "Feature film" fallback label from content cards

**Current state**: `UiMetadataFormatter.secondaryLine()` (line 127) falls back to
`"Feature film"` for movies when the API-sourced `subtitle` field is empty. This text is
redundant — the card overline already says `"Movie"` — and slightly formal/awkward.

**Source**: `UiMetadataFormatter.kt`, `secondaryLine()`:
```kotlin
item.isMovie() -> parts += "Feature film"
```

**Fix**: Remove this fallback line. When a movie has no API subtitle and no watch progress,
the subtitle area on the card will be blank rather than showing a redundant label. The overline
`"Movie"` (or `"Continue Watching"` if in progress) provides sufficient context.

Also check `landscapeSubtitle()` — it delegates to `secondaryLine()` for movies so the same
fix covers landscape cards (e.g., Continue Watching cards with no progress).

| File | Change |
|------|--------|
| `ui/UiMetadataFormatter.kt` | Remove `item.isMovie() -> parts += "Feature film"` line from `secondaryLine()` |

Unit test `UiMetadataFormatterTest.kt` should be updated to verify blank subtitle for movies
with no subtitle and no progress.

---

### Item 4: Player seekbar — thumbnail preview during scrubbing

**Goal**: Show a small image preview above the seekbar while the user scrubs (as in the
official Prime Video app), so they can see what scene they are seeking to before releasing.

#### How Amazon implements it

Amazon DASH manifests typically include an image adaptation set — a "trick play" track — that
provides thumbnail sprite sheets at regular intervals:

```xml
<AdaptationSet mimeType="image/jpeg" contentType="image">
  <SegmentTemplate media="$Number$" timescale="1" duration="10" startNumber="1">
    ...
  </SegmentTemplate>
  <Representation id="thumbnail" width="1920" height="90" bandwidth="512">
    ...
  </Representation>
</AdaptationSet>
```

Each sprite sheet image contains multiple frames arranged in a grid. The number of columns and
rows per sprite and the per-frame width/height are encoded in the adaptation set attributes or
in a `<EssentialProperty>` with scheme `urn:mpeg:dash:thumbnail:2013`.

Amazon also sometimes includes a dedicated thumbnails URL in the `GetPlaybackResources` response
body as a separate property (not always present; varies by title and region).

#### Investigation required (before implementation)

1. Capture a live `GetPlaybackResources` response for a HD title and a 4K title — check if
   there is a thumbnail/trickplay track in the MPD XML.
2. If present in the MPD, parse:
   - `SegmentTemplate duration` → interval between frames (seconds)
   - `Representation width`/`height` → sprite sheet total dimensions
   - Number of columns and rows (often found in `@sar` or essential property)
   - Template URL pattern
3. If not in the MPD, check the `GetPlaybackResources` JSON response top level for a
   `thumbnailTrack` / `trickPlayTrack` property.

#### UI implementation plan

1. **`PlayerActivity.kt`**:
   - After manifest is loaded, extract `thumbnailTrackUrl`, `frameIntervalSec`, `frameWidth`,
     `frameHeight`, `framesPerRow` from the `PlaybackInfo` model (add new fields)
   - Attach `DefaultTimeBar.OnScrubListener` to the seekbar:
     - `onScrubStart` / `onScrubMove(position: Long)` → `showThumbnailAt(position)`
     - `onScrubStop` → `hideThumbnail()`

2. **`showThumbnailAt(positionMs)`**:
   - `frameIndex = (positionMs / 1000 / frameIntervalSec).toInt()`
   - `sheetIndex = frameIndex / (framesPerRow * framesPerRow)` (frames per sheet)
   - `col = frameIndex % framesPerRow`
   - `row = (frameIndex / framesPerRow) % framesPerRow`
   - Request the sprite sheet image URL via `Coil` (cacheable), crop the frame as a `Bitmap`
     using `BitmapRegionDecoder`
   - Display in a floating `ImageView` / `CardView` above the seekbar at the x-position
     corresponding to the seek position

3. **Layout (`activity_player.xml`)**:
   - Add a `CardView` + `ImageView` (`id=iv_seek_thumbnail`) with `visibility="gone"`, fixed
     size (e.g., 160×90dp), positioned above the `exo_progress` bar
   - `alpha=0` while hidden; fade in on scrub start

4. **`AmazonApiService.kt` / `PlaybackInfo.kt`**:
   - Add `thumbnailTrackUrl: String`, `frameIntervalSec: Int`, `spriteColumns: Int`,
     `spriteRows: Int`, `frameWidthPx: Int`, `frameHeightPx: Int` to `PlaybackInfo`
   - Parse from DASH manifest (or `GetPlaybackResources` JSON) in `getPlaybackInfo()`

#### Known constraints

- `BitmapRegionDecoder` decodes only JPEG and PNG — matches Amazon's sprite format
- Coil image loading must be done off the main thread; `BitmapRegionDecoder` must not be called
  on UI thread
- The `DefaultTimeBar.OnScrubListener` interface must be set via
  `(playerView.findViewById<DefaultTimeBar>(R.id.exo_progress))?.addListener(scrubListener)`
- If no thumbnail track is found for a title, the feature degrades gracefully (no preview shown)
- Trailer playback: thumbnails should be disabled (thumbnail track likely absent for trailers)

---

### Definition of done (Phase 31)

- [x] Detail page shows progress bar + "X% watched · Y min left" for partially watched titles
- [x] Play button shows `▶ Resume` when progress > 10 s; `▶ Play` otherwise
- [x] Trailer button has a distinct icon (`▷` outline triangle vs `▶` filled)
- [x] Home hero strip shows amber progress bar when featured item has watch progress
- [x] `"Feature film"` fallback text removed from movie cards
- [ ] Thumbnail preview appears above seekbar on scrub for titles that have a trick play track (deferred — requires DASH manifest investigation)
- [ ] Thumbnail degrades gracefully (no crash, no UI artifact) when track is absent (deferred)
- [x] `./gradlew assembleRelease` passes; no new warnings

### Completed items

| Item | File(s) changed | Notes |
|------|-----------------|-------|
| Remove "Feature film" | `UiMetadataFormatter.kt` | Removed fallback from `secondaryLine()`; overline already says "Movie" |
| Detail page progress | `activity_detail.xml`, `DetailActivity.kt` | Amber `ProgressBar` + "X% watched · Y min left" `TextView` below metadata; reads `ProgressRepository.get(asin)` |
| Play/Resume button | `DetailActivity.kt` | `bindDetail()` sets text to "▶  Resume" when `posMs > 10 s`, "▶  Play" otherwise |
| Trailer icon | `activity_detail.xml` | Changed `▶  Trailer` → `▷  Trailer` (outline triangle = preview/trailer semantic) |
| Hero strip progress bar | `activity_main.xml`, `MainActivity.kt` | `iv_home_featured` wrapped in `FrameLayout`; amber `ProgressBar` at bottom; wired via `ProgressRepository.get()` in `updateHomeFeaturedStrip()` |
| Seekbar thumbnail preview | — | Deferred to a later phase — requires DASH trick-play track investigation per title |

### Post-ship analysis

A full audit of Phase 31's implementation was conducted after shipping.
See `dev/phase31-analysis.md` for the detailed findings.

**Summary of issues found:**

| ID | Priority | Description |
|----|----------|-------------|
| P0-A | P0 | `-1L` sentinel (fully-watched) produces blank detail page — "Finished recently" never shown |
| P0-B | P0 | No `onResume()` in `DetailActivity` — button text and bar stale after returning from player |
| P1-A | P1 | Progress % formula diverges: detail page double-truncates vs card subtitle single-truncation |
| P1-B | P1 | `OTHER` type → "MOVIE" on detail page eyebrow, "Featured" on card overline |
| P1-C | P1 | Season detail suppresses Trailer button even when `isTrailerAvailable = true` |
| P2-A | P2 | Redundant `pbWatchProgress.max = 1000` in code couples to XML value silently |
| P2-B | P2 | Watchlist toggle has no in-flight guard — double-tap sends duplicate API calls |
| P2-C | P2 | `getInProgressEntries()` has redundant `!= -1L` (`-1L > 0L` already excludes it) |
| P2-D | P2 | `refresh()` can overwrite newer local progress with older server value |
| P3-A | P3 | Series detail page has no last-episode resume shortcut |
| P3-B | P3 | No TTL or background refresh for `ProgressRepository` |
| P3-C | P3 | `seriesAsin` not set in `detailInfoToContentItem()` — blocks future series resume |
| P3-D | P3 | `contentLabel()` catch-all `"Featured"` is misleading for movie-like unknown types |

P0 and P1 fixes are planned for the next phase alongside the seekbar thumbnail feature.


## Phase 32: COMPLETE
- Applied the high-value P0/P1 fixes and selected low-risk P2 fixes identified in the Phase 31 post-ship analysis
- See `dev/phase31-analysis.md` for issue details and fix rationale

### What was fixed

| Issue | Fix |
|-------|-----|
| P0-A `-1L` sentinel invisible on detail page | `bindProgress()` guard changed `posMs <= 0L` → `posMs == 0L`; new explicit `-1L` branch shows "Finished recently" (no bar) |
| P0-B Detail page stale after returning from player | Added `onResume()` in `DetailActivity` that re-reads `ProgressRepository` and updates button text + progress bar |
| P1-A Percent formula diverges between detail page and card | Extracted `progressText(posMs, runtimeMs)` in `UiMetadataFormatter`; both surfaces delegate to it |
| P1-B `OTHER` type label inconsistency ("MOVIE" vs "Featured") | Unified `contentLabel()` and `defaultOverline()` catch-all to `"Movie"` |
| P1-C Season trailer button suppressed by `!isSeries` | Fixed to `(!isSeries || isSeason)` so seasons with a trailer show the Trailer button |
| P2-A Redundant `pbWatchProgress.max = 1000` | Removed from `bindProgress()`; XML declaration is the single source of truth |
| P2-B Watchlist double-tap sends duplicate API call | Added `watchlistUpdateInFlight` boolean with `try/finally` in `onWatchlistClicked()` |
| P2-C `getInProgressEntries()` redundant `!= -1L` | Simplified to `positionMs > 0L` (the extra condition was already implied) |

### Emulator test results

| Test | Result |
|------|--------|
| P0-B: The Wrecking Crew — Play → watch 20 s → Back → detail page | ✅ Button changed to "▶  Resume", "1% watched · 123 min left" visible |
| P1-A: Borderlands detail page vs CW card subtitle | ✅ Both show "11% watched · 86 min left" — canonical formatter confirmed |
| P1-B: Unknown-type content detail page eyebrow | ✅ Shows "MOVIE" (was "Featured") |
| P1-C: The Wrecking Crew — movie with `isTrailerAvailable = true` | ✅ "▷ Trailer" button visible |
| P0-A: `-1L` sentinel → "Finished recently" | Logic-verified via code inspection (no fully-watched title available in test data) |

### Not in scope (P3 deferred)
- P3-A: Series detail last-episode resume shortcut
- P3-B: Background progress refresh / TTL
- P3-C: `seriesAsin` not set in `detailInfoToContentItem()`
- P2-D alternative merge policy — server-first refresh was kept to preserve Decision 25 and README behavior
- Seekbar thumbnail preview (deferred from Phase 31)

---

## Phase 33: COMPLETE — Player Scrub Preview + Series Resume

### Goal

Deliver the two highest-value remaining playback UX improvements:

1. **Player scrub preview overlay** — the small preview window shown above the seekbar while the
   user scrubs left/right in the player controls, matching the behavior of the official app.
2. **Series detail resume shortcut** — a direct `Resume Episode` action on series detail pages
   when the app already knows the user has in-progress episode data for that show.

This phase should stay focused on playback/navigation quality, not broader metadata caching or
cross-device sync redesign.

### User-facing behavior

#### 1. Scrub preview overlay

When the user focuses the player seekbar and presses left/right:
- a small preview card appears above the seekbar
- it updates as the target position changes
- it hides when scrubbing stops or when the player controls auto-hide

This preview is an **image thumbnail**, not a second video player.

#### 2. Series resume shortcut

When the user opens a series detail page and the app can identify an in-progress episode:
- the primary action becomes `▶ Resume Sx Ex`
- selecting it starts playback for that episode directly
- `Browse Seasons` remains available as the fallback navigation path

### Technical design

#### Part A — Trick-play thumbnail metadata

Amazon likely exposes seek previews through a DASH image adaptation set ("trick-play" track).
Implementation should prefer manifest- or playback-resource-driven metadata, not runtime video
frame extraction.

**Model changes**:

Extend `PlaybackInfo` with optional thumbnail metadata:

```kotlin
val thumbnailTrackUrl: String = ""
val frameIntervalSec: Int = 0
val spriteColumns: Int = 0
val spriteRows: Int = 0
val frameWidthPx: Int = 0
val frameHeightPx: Int = 0
```

**API layer**:
- `AmazonApiService.getPlaybackInfo()`
  - inspect `GetPlaybackResources`
  - if no direct thumbnail metadata is present, fetch/parse the MPD
  - detect image/trick-play adaptation sets
- expected inputs:
  - sprite sheet URL template
  - frame interval
  - sprite grid dimensions
  - frame dimensions

**Important rule**:
- if no trick-play metadata is found, leave all fields empty/zero and degrade gracefully

#### Part B — Player overlay implementation

**Files**:
- `app/src/main/res/layout/activity_player.xml`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt`

**Layout**:
- add a small floating thumbnail card above `exo_progress`
- suggested size: about `160dp x 90dp`
- hidden by default

**Player logic**:
- attach `DefaultTimeBar.OnScrubListener`
- on scrub start / move:
  - compute frame index from target position
  - compute sprite sheet index + crop rect
  - load sprite sheet off the main thread
  - crop the correct frame with `BitmapRegionDecoder`
  - show/update the preview card
- on scrub stop:
  - hide preview card

**Visibility behavior**:
- preview must disappear with the player controls
- no independent linger/flicker behavior

#### Part C — Series resume shortcut

**Files**:
- `app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt`
- `app/src/main/java/com/scriptgod/fireos/avod/data/ProgressRepository.kt`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt`

**Needed data work**:
- preserve enough parent-series identity on episode items
- specifically ensure locally resolved progress-backed items keep:
  - `seriesAsin`
  - `showId`
  - `seasonId` when available

**Series detail behavior**:
- if current page is a series and an in-progress episode for that series is known:
  - render `▶ Resume Sx Ex`
  - wire it directly to `PlayerActivity`
  - pass `EXTRA_ASIN`, `EXTRA_TITLE`, `EXTRA_CONTENT_TYPE`, and `EXTRA_RESUME_MS`

**Selection rule**:
- prefer the most recently progressed episode if that can be inferred
- otherwise prefer the furthest-progress episode for the series

### Small supporting improvement

#### Repository freshness TTL

Keep the current server-first merge policy, but allow lightweight refresh on long-lived sessions:
- if Home resumes after a small TTL (for example 5-10 minutes), refresh progress
- otherwise reuse in-memory repository state

This is intentionally not a background sync feature.

### Out of scope

- full metadata repository / offline content cache
- true timestamp-based conflict resolution
- background worker for progress sync
- broader player redesign beyond scrub preview

### Recommended implementation order

1. Investigate trick-play metadata in `GetPlaybackResources` / MPD
2. Add thumbnail fields to `PlaybackInfo`
3. Implement player scrub preview overlay
4. Fix parent-series identity on progress-backed items
5. Implement series detail resume CTA
6. Add simple repository TTL

### Definition of done

- thumbnail preview appears above the seekbar for titles with trick-play metadata
- no preview is shown, and no errors occur, for titles without trick-play metadata
- preview hides in sync with player controls
- series detail pages can show `Resume Episode` when in-progress episode data exists
- `Resume Episode` starts the correct episode with the correct resume position
- `./gradlew assembleRelease` passes

---

## Phase 33 implementation notes

### Part A — Seekbar scrub thumbnail preview (BIF)

**Root cause discovery**: `parseThumbnailTrack()` looked for DASH image adaptation sets,
but Amazon does not embed thumbnail tracks in the MPD. Decompiled APK smali revealed the
real mechanism: `TrickplayPlugin$DownloadBifFileFromUrl` + `Resource` enum value
`"TrickplayUrls"`.

**Solution**:
- Added `TrickplayUrls` to `desiredResources` in `GetPlaybackResources`
- `AmazonApiService.extractBifUrl()` parses `trickplayUrls.trickplayUrlsCdnSets[].trickplayUrlInfoList[]`, prefers 480p
- `PlaybackInfo.bifUrl` replaces the old sprite-sheet fields
- `PlayerActivity.loadBifIndex()` downloads BIF header+index (64 B header, then N+1 × 8 B index) via HTTP Range
- `PlayerActivity.showThumbnailAt()` binary-searches the index and range-fetches individual JPEG frames on demand; LRU-caches up to 10 frames
- D-pad LEFT/RIGHT accumulates `seekPreviewPos` and shows the thumbnail overlay; `dpadSeekHandler` hides it 1.5 s after the last key press
- Confirmed working on device (user: "works")

**Commits**: f8d43b5

### Part B — Series resume shortcut + post-ship review fixes

**Series resume CTA** (`DetailActivity`):
- `updateSeriesResumeCta(info)` helper checks server-backed `getInProgressItems()` first,
  falls back to `getLocalProgressForSeries()` for episodes watched but not yet server-refreshed
- Called from both `bindDetailInfo()` and `onResume()` so the CTA appears immediately on
  return from playback
- Button shows `▶  Resume SxEx` when season/episode numbers are available; generic
  `▶  Resume Episode` for local-only entries where metadata is absent

**ProgressRepository changes**:
- `ProgressEntry` gains `seriesAsin: String = ""` (Gson-backward-compatible)
- `update()` accepts optional `seriesAsin`; stored per entry
- `getLocalProgressForSeries(seriesAsin)` returns the highest-positionMs local entry

**PlayerActivity / BrowseActivity**:
- `EXTRA_SERIES_ASIN` constant; read on startup; threaded through to every `update()` call
- `BrowseActivity` passes `item.seriesAsin` when launching episodes

**TTL refresh flat-grid fix** (`MainActivity`):
- TTL coroutine now has `else if (adapter.currentList.isNotEmpty())` branch so Watchlist /
  Library / search results are also rebound after the async server refresh

**Commits**: 3ab9b56

### Definition of done — verified

- ✅ Thumbnail preview appears above seekbar for titles with trick-play metadata
- ✅ No preview shown (no errors) for titles without trick-play metadata
- ✅ Preview hides in sync with player controls (1.5 s dpad idle)
- ✅ Series detail pages show `Resume Episode` CTA for in-progress episodes (server + local)
- ✅ `Resume Episode` starts correct episode with correct resume position
- ✅ `./gradlew assembleRelease` passes
- ✅ TTL refresh updates flat-grid surfaces (Watchlist, Library, search)

---

## Phase 34: Playback Completion Behaviour — COMPLETE (2026-03-02)

### What was built

- `PlayerActivity`: new `EXTRA_SEASON_ASIN` constant and `currentSeasonAsin` field; reads season ASIN from intent on `onCreate`; `STATE_ENDED` now calls `onPlaybackCompleted()`
- `onPlaybackCompleted()`: trailers and movies (no season context) call `finish()`; episodes fetch the season's episode list via `getDetailPage`, binary-search for the current episode, and auto-play the next one if it exists; last episode of season calls `finish()`
- `BrowseActivity`: passes `item.seasonId` as `EXTRA_SEASON_ASIN` when launching player for an episode
- `DetailActivity.onResumeEpisodeClicked()`: passes `episode.seasonId` as `EXTRA_SEASON_ASIN`

### Edge cases handled

| Scenario | Outcome |
|----------|---------|
| Trailer ends | `finish()` |
| Movie ends | `finish()` |
| Episode ends, next exists | auto-play next episode |
| Last episode of season | `finish()` → returns to season BrowseActivity |
| API lookup fails | `finish()` (safe fallback) |
| Next episode partially watched | resumes from ProgressRepository |
| User presses Back during API call | coroutine cancelled in `onDestroy`, no crash |

### Definition of done — verified

- ✅ `./gradlew assembleRelease` passes, no new warnings
- ✅ Movie ends → `finish()` confirmed on device (logcat: `onPlaybackCompleted → finish (no season/trailer)`)
- ✅ Episode ends → next episode auto-plays confirmed on device (logcat: `onPlaybackCompleted → auto-play next episode`, `loadAndPlay` called with new ASIN, same season ASIN)
- Note: `item.seasonId` is empty for episodes listed in BrowseActivity; fixed by storing `browseAsin` as class field and using it as fallback when `currentFilter == "episodes"`
- ✅ CW rail removes finished items on next `onResume()` — `buildContinueWatchingRail()` applies `withRepositoryProgress` + `filter { watchProgressMs > 0 }`
- ✅ Home CW direct-play passes `EXTRA_SEASON_ASIN` — `openPlayer()` updated; `withRepositoryProgress()` propagates `ProgressEntry.seasonAsin` → `ContentItem.seasonId`
- ✅ `ProgressEntry` stores `seasonAsin`; `persistPlaybackProgress()` writes `currentSeasonAsin`
- ✅ Local-only series resume `ContentItem` synthesis sets `seasonId` from `ProgressEntry.seasonAsin`
- ✅ Old `ExoPlayer` released in `setupPlayer()` before building a new instance (no renderer leak)
