# Phase 31 — Post-Implementation Analysis

> Conducted after Phase 31 shipped. All issues verified against source code.
> Severity: **P0** = silent wrong behaviour, **P1** = visible inconsistency, **P2** = fragile/minor,
> **P3** = nice-to-have.

---

## What Phase 31 delivered

| Item | File(s) changed | Status |
|------|----------------|--------|
| 1a. Detail page: amber progress bar + "X% watched · Y min left" | `DetailActivity.kt`, `activity_detail.xml` | ✓ shipped |
| 1b. ▶ Resume / ▶ Play button distinction | `DetailActivity.kt` | ✓ shipped |
| 1c. ▷ Trailer distinct outline icon | `activity_detail.xml` | ✓ shipped |
| 2. Hero strip thumbnail: amber progress bar | `MainActivity.kt`, `activity_main.xml` | ✓ shipped |
| 3. Remove "Feature film" fallback label | `UiMetadataFormatter.kt` | ✓ shipped |
| 4. Seekbar thumbnail preview | — | ✗ deferred (Phase 32) |

---

## Issues found

### P0 — Silent failures

#### P0-A: `-1L` sentinel invisible on detail page
**File**: `DetailActivity.kt:286` — `bindProgress()`

```kotlin
if (runtimeMs <= 0L || posMs <= 0L) {   // posMs = -1L passes this guard
    pbWatchProgress.isVisible = false
    tvWatchProgress.isVisible = false
    return
}
```

`ProgressRepository.update()` stores `-1L` for any item watched ≥ 90%. Because `-1L < 0`,
the guard fires and returns early — the detail page shows no progress UI at all for a
fully-watched movie. Contrast with `UiMetadataFormatter.progressSubtitle()` (line 87) which
explicitly handles `-1L → "Finished recently"` and the CW card subtitle that uses it.

**Effect**: The user watches a movie to 95%, opens its detail page, sees neither a progress bar
nor a "Finished recently" label, and cannot tell whether they have seen this film. The button
also says "▶ Play" (because `-1L > 10_000L` is `false`), so there is no resume hint either.

**Fix**:
```kotlin
private fun bindProgress(info: DetailInfo) {
    val runtimeMs = info.runtimeSeconds * 1000L
    val entry = ProgressRepository.get(info.asin)
    val posMs = entry?.positionMs ?: 0L
    if (runtimeMs <= 0L || posMs == 0L) {          // was: posMs <= 0L
        pbWatchProgress.isVisible = false
        tvWatchProgress.isVisible = false
        return
    }
    if (posMs == -1L) {                             // new explicit branch
        pbWatchProgress.isVisible = false
        tvWatchProgress.text = "Finished recently"
        tvWatchProgress.isVisible = true
        return
    }
    // … rest of method unchanged
}
```

Also change the Play button evaluation in `bindDetail()` to treat `-1L` as "Play" (not
Resume), matching the existing `-1L > 10_000L = false` shortcut but making the intent explicit:
```kotlin
val resumeMs = ProgressRepository.get(info.asin)?.positionMs ?: 0L
btnPlay.text = if (resumeMs > 10_000L) "▶  Resume" else "▶  Play"
// resumeMs = -1L → "▶  Play" (correct, no seek; start from beginning)
```
This is already the accidental result of the signed comparison, but deserves a comment.

---

#### P0-B: Detail page progress is stale after returning from `PlayerActivity`
**File**: `DetailActivity.kt` — no `onResume()` override

`bindDetail()` (and inside it `bindProgress()`) runs once during `onCreate()`. When the user:
1. Opens a movie detail page (no progress) → button says "▶ Play", bar hidden
2. Taps Play, watches 15 minutes, presses Back
3. Returns to the detail page

`DetailActivity` is resumed from the back stack. Its `onCreate()` does **not** run again.
The Play button still says "▶ Play". The progress bar is still hidden. The repository was
updated by `PlayerActivity`, but nobody re-reads it.

**Fix**: Add `onResume()`:
```kotlin
override fun onResume() {
    super.onResume()
    val info = detailInfo ?: return     // not yet loaded, nothing to refresh
    val entry = ProgressRepository.get(info.asin)
    val resumeMs = entry?.positionMs ?: 0L
    btnPlay.text = if (resumeMs > 10_000L) "▶  Resume" else "▶  Play"
    bindProgress(info)
}
```
No network call needed — the repository is already updated in-memory by `PlayerActivity`.

---

### P1 — Visible inconsistencies

#### P1-A: Progress percent formula diverges between detail page and card subtitle
**Files**: `DetailActivity.kt:301` vs `UiMetadataFormatter.kt:90`

Detail page (`bindProgress`):
```kotlin
val fraction     = (posMs * 1000L / runtimeMs).toInt().coerceIn(0, 1000)  // 0–1000 scale
val progressPct  = (fraction / 10).coerceIn(1, 99)                         // two truncations
```

Card subtitle (`progressSubtitle`):
```kotlin
val progressPct = ((item.watchProgressMs * 100) / item.runtimeMs).toInt().coerceIn(1, 99)
// one truncation
```

For `posMs = 3_750_000L`, `runtimeMs = 10_000_000L`:
- Detail page: `fraction = 375 → pct = 37`
- Card: `37_500_000 / 10_000_000 = 37` — same here by coincidence

For `posMs = 3_795_000L`, `runtimeMs = 10_000_000L`:
- Detail page: `fraction = 379 → pct = 37`
- Card: `37_950_000 / 10_000_000 = 37` — same again

The two-stage truncation means the detail page can read one integer lower than the card
subtitle for positions near a percentage boundary. A user who sees "38% watched" on the CW
card and then opens the detail page to see "37% watched" experiences silent inconsistency.

**Fix**: Replace the percent text computation in `bindProgress()` with a call to
`UiMetadataFormatter.progressSubtitle()`. The bar fraction is still computed locally (it needs
the 0–1000 scale), but the displayed text is delegated:
```kotlin
// bar fraction — must stay local
val fraction = (posMs * 1000L / runtimeMs).toInt().coerceIn(0, 1000)
pbWatchProgress.progress = fraction
pbWatchProgress.isVisible = true

// text — delegate to the canonical formatter
val syntheticItem = ContentItem(
    asin = info.asin,
    title = info.title,
    watchProgressMs = posMs,
    runtimeMs = runtimeMs
)
tvWatchProgress.text = UiMetadataFormatter.progressSubtitle(syntheticItem) ?: "${ (fraction / 10).coerceIn(1,99) }% watched"
tvWatchProgress.isVisible = true
```

---

#### P1-B: `detailEyebrow()` and card overline disagree on `OTHER` content type
**Files**: `DetailActivity.kt:315–323` (`detailEyebrow`) vs `UiMetadataFormatter.kt:115` (`contentLabel`)

| Surface | `ContentKind.OTHER` label |
|---------|--------------------------|
| Card overline (`contentLabel`) | `"Featured"` |
| Detail page eyebrow (`detailEyebrow`) | `"MOVIE"` |

A user selects a card labelled "Featured", opens the detail page, and sees "MOVIE". The
two surfaces disagree on the same item.

**Fix**: Align `detailEyebrow()` with `contentLabel()` — or vice-versa — so both fall back
to the same string for unknown types. The simplest approach is to change `detailEyebrow()`:
```kotlin
else -> "FEATURED"   // was "MOVIE"; matches contentLabel "Featured"
```
Or change `contentLabel()` to return `"Movie"` for the catch-all:
```kotlin
else -> "Movie"      // was "Featured"; matches detailEyebrow "MOVIE"
```
The second option is probably more accurate (an unknown non-series item is more likely a
movie than a generic "Featured" item).

---

#### P1-C: Season detail pages suppress the Trailer button even when a trailer is available
**File**: `DetailActivity.kt:261`

```kotlin
if (info.isTrailerAvailable && !isSeries) {
```

`isSeries` is `true` for both `SERIES` and `SEASON` content types (via
`AmazonApiService.isSeriesContentType()`). A season detail page with `isTrailerAvailable = true`
never shows the Trailer button. Whether this is intentional is not documented. If trailers
are intentionally suppressed for seasons, a comment should say so. If it is an oversight,
the fix is:
```kotlin
val isSeries = info.contentType.uppercase().let {
    it.contains("SERIES") && !it.contains("SEASON")
}
```
so that seasons — which behave more like movies for the purposes of having a playable trailer
— show the Trailer button.

---

### P2 — Fragile / minor

#### P2-A: Redundant `pbWatchProgress.max = 1000` in code
**File**: `DetailActivity.kt:298`

`activity_detail.xml` already declares `android:max="1000"`. The in-code re-assignment at
line 298 creates implicit coupling: if someone changes the XML max without updating the
`* 1000L` formula on line 291 (or vice versa), the bar will render at the wrong scale
silently. Remove the line from `bindProgress()`:
```kotlin
// remove: pbWatchProgress.max = 1000
pbWatchProgress.progress = fraction
```

#### P2-B: Watchlist toggle has no debounce
**File**: `DetailActivity.kt:383–410`

`isIn` is captured at line 384 before the coroutine launches. If the user double-taps the
Watchlist button before the first coroutine completes, a second coroutine captures the same
`isIn = false` and issues a second `addToWatchlist`. The result is a duplicate API call and
a confusing "Added to watchlist" toast followed immediately by another "Added to watchlist".

**Fix**: set a boolean flag `watchlistUpdateInFlight` before launching, clear it in
`finally`, and return early at the top of `onWatchlistClicked()` if `true`.

#### P2-C: `getInProgressEntries()` has a redundant condition
**File**: `ProgressRepository.kt` — `getInProgressEntries()`

```kotlin
entry.positionMs > 0L && entry.positionMs != -1L
```
`-1L > 0L` is `false`, so the `!= -1L` condition is never needed. Remove it.

#### P2-D: `ProgressRepository.refresh()` can overwrite newer local progress with older server data
**File**: `ProgressRepository.kt:47–50`

```kotlin
progressMap.clear()
progressMap.putAll(loadPersistedEntries())       // disk
for ((asin, progress) in serverProgress) {
    progressMap[asin] = ProgressEntry(...)       // server overwrites local
}
```

If the user watched a movie locally between two server refreshes, the second `refresh()` call
(triggered by returning to `MainActivity`) will overwrite the newer local position with the
older server value. There is no timestamp guard because Amazon does not expose a
`lastUpdatedAt` field.

**Partial mitigation**: before overwriting, compare positions and keep the larger one:
```kotlin
for ((asin, progress) in serverProgress) {
    val existing = progressMap[asin]
    val newEntry = ProgressEntry(progress.first, progress.second)
    if (existing == null || newEntry.positionMs > existing.positionMs) {
        progressMap[asin] = newEntry
    }
}
```
This is "max position wins" rather than true timestamp-based conflict resolution, which is
the best achievable without a server-side `lastUpdatedAt`. A full-reset `-1L` (watched to
completion) must still win over any positive position:
```kotlin
if (existing == null
    || newEntry.positionMs == -1L
    || (existing.positionMs != -1L && newEntry.positionMs > existing.positionMs)) {
    progressMap[asin] = newEntry
}
```

---

### P3 — UX gaps / future improvements

#### P3-A: Series detail page has no resume hint
**File**: `DetailActivity.kt:244–249`

When a series detail page is opened, the only actions are Browse Seasons / Browse Episodes.
There is no indicator of "You were watching S2E4", and no shortcut to resume the last-played
episode directly. The user must navigate series → seasons → episodes to find their place.

`ProgressRepository.getInProgressEntries()` contains episode-level items if those episodes
were played locally or appeared in the server's home-page supplement. The series ASIN is not
directly correlated to episode ASINs in the repository, but the episode's `ContentItem` would
carry a `seriesAsin` field if populated by `detailInfoToContentItem()`.

**Possible implementation**:
1. When `isSeries && !isSeason`, call `ProgressRepository.getInProgressEntries()` and look
   for any entry whose `ContentItem.seriesAsin == info.asin`.
2. If found, show a "▶ Resume S2E4" button above Browse Seasons, pre-wired with the episode
   ASIN and its stored resume position.
3. This requires `seriesAsin` to be populated in `ContentItem` when fetched via
   `detailInfoToContentItem()` — currently it is not (see P3-C below).

#### P3-B: No repository TTL or background refresh
**File**: `ProgressRepository.kt` / `MainActivity.kt`

Progress data from the server is fetched once at app start. If the app stays in the background
for hours while the user watches on another device, all progress positions are stale. There is
no background refresh, no pull-to-refresh gesture, and `MainActivity.onResume()` does not call
`ProgressRepository.refresh()`.

**Possible implementation**: call `ProgressRepository.refresh()` in `MainActivity.onResume()`
when the time since last refresh exceeds a threshold (e.g., 10 minutes):
```kotlin
if (System.currentTimeMillis() - lastProgressRefreshMs > 10 * 60_000L) {
    lifecycleScope.launch { ProgressRepository.refresh(apiService) }
}
```

#### P3-C: `seriesAsin` not populated in `ContentItem` from `detailInfoToContentItem()`
**File**: `MainActivity.kt` — `detailInfoToContentItem()` (approx. line 897)

`DetailInfo` has `showAsin: String`. `ContentItem` has a `seriesAsin: String` field. The
conversion does not set `seriesAsin = info.showAsin`, so local-only CW episodes resolved
through the detail API cannot be matched back to their parent series. This blocks the series
resume shortcut in P3-A and could cause duplicate series + episode entries in the CW rail.

**Fix**: in `detailInfoToContentItem()`, add:
```kotlin
seriesAsin = info.showAsin,
```

#### P3-D: `contentLabel()` catch-all is "Featured", not "Movie"
**File**: `UiMetadataFormatter.kt:115`

As noted in P1-B, the `else` branch returns `"Featured"` for unknown content types. Since
unknown non-series items are statistically more likely to be movies, returning `"Movie"` would
be less confusing and would make the card label consistent with the detail page eyebrow.

---

## Summary table

| ID | Priority | File | One-line description |
|----|----------|------|----------------------|
| P0-A | P0 | `DetailActivity.kt:286` | `-1L` sentinel shows no UI (should show "Finished recently") |
| P0-B | P0 | `DetailActivity.kt` | No `onResume()` — button text and bar stale after playback |
| P1-A | P1 | `DetailActivity.kt:301` vs `UiMetadataFormatter.kt:90` | Percent formula diverges; detail page can show N-1% vs card |
| P1-B | P1 | `DetailActivity.kt:315` vs `UiMetadataFormatter.kt:115` | `OTHER` type → "MOVIE" on detail, "Featured" on card |
| P1-C | P1 | `DetailActivity.kt:261` | Season trailer button suppressed by `!isSeries` check |
| P2-A | P2 | `DetailActivity.kt:298` | Redundant `max = 1000` in code couples to XML value |
| P2-B | P2 | `DetailActivity.kt:383` | Watchlist toggle has no in-flight guard — double-tap issues duplicate API call |
| P2-C | P2 | `ProgressRepository.kt` | `getInProgressEntries()` has redundant `!= -1L` guard |
| P2-D | P2 | `ProgressRepository.kt:47` | `refresh()` can overwrite newer local progress with older server data |
| P3-A | P3 | `DetailActivity.kt:244` | Series detail page has no last-episode resume shortcut |
| P3-B | P3 | `ProgressRepository.kt` | No TTL / background refresh — progress stale across long sessions |
| P3-C | P3 | `MainActivity.kt` | `seriesAsin` not set in `detailInfoToContentItem()` — blocks series resume shortcut |
| P3-D | P3 | `UiMetadataFormatter.kt:115` | `else → "Featured"` catch-all is misleading for movie-like content |

---

## Recommended fix order for Phase 32

The P0 items are the highest-value fixes and require only small, self-contained changes:

1. **P0-A** + **P0-B** — fix `bindProgress()` and add `onResume()` in `DetailActivity`
   (same file, same PR, ~15 lines total)
2. **P1-A** — delegate progress text to `UiMetadataFormatter.progressSubtitle()` to
   eliminate the formula divergence (~5 lines)
3. **P2-D** — "max-position wins" merge in `ProgressRepository.refresh()` (~8 lines)
4. **P2-B** — watchlist in-flight guard (~5 lines)

The P3 items (series resume shortcut, repository TTL) are larger features and should be
treated as separate phases if taken on.
