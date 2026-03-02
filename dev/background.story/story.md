# Get rid of Amazon Ads on FireTV journey

Want to get rid of ads on Amazon FireTV

## Kodi
1. There is a Kodi (old XBMC) plugin that can play it
    1. Works, but...
    1. Does not track the watch progress in Amazon - also the progress tracking in Kodi is buggy. So no watch progress which makes it a little bit unhandy
    1. Kodi plugin crashes from time to time audio decoding of the FireTV - requires restart
    1. minor: no fancy player control slider with a thumbnail
1. So Kodi plugin is not perfect. But the Ads are controlled by the client not the server - so is there a better way to get rid of them

## ReVanced
The ReVanced project with custom patches for APKs...
1. ...and there is one for Amazon Prime :) So we can use the Amazon App
1. ...but wait it's only for an old version of it from last year

### Original AI APK handling
So let's see if the patch works also for the recent version.
But how? We would need to analyze the APK and compare the build for compatibility - quite hard
1. requires good decompiler to higher language or ability to read Dalvik VM instructions
1. lot of know-how

...but yeah with fancy AIs now :P

#### AI analysis
Let the AI analyze whether the APK version in the ReVanced patch can be similarly patched for the most recent version...
...but first put AI in a container (AI designed - https://github.com/ScriptGod1337/ai-sandbox-vscode) and block access to LAN to protect my home.

1. Asked an AI for this analysis with just a simple prompt.
AI starting and running but a lot of strange download requests going on for 15 mins... ah AI is blocked by bit protection
1. Download the relevant APKs as a human, and try again
    1. AI understood that it has to decompile the APKs compare them and test the patch
    1. AI downloaded all relevant tools (e.g, apktools etc.)
    1. AI understood the behavior
    1. AI saw a difference of the APKs and adapted the patch slightly
    1. AI updated the ReVanced patch
1. During testing by myself there were some issues. But after some loops with the AI in an emulator
    1. I found issues with the patch, updated it and created a valid patch along with a new patched APK
    1. Instructions for future version patching (https://github.com/ScriptGod1337/amazon-vod-noads/blob/main/analysis/primevideo_skipads_ai_prompt.md)

### Amazon Prime APK on FireTV
Ready to go let's install the APK to my FireTV...
...and it broke: Seems that Amazon Prime APK can't be installed on FireTV - missing Google Playstore and conflict with vital parts of the system.

### Amazon Prime remote control
Amazon Prime app can control FireTV remotely maybe. Maybe we can skip ads by remote controlling with a patched app.
1. Did not work: it showed Ads
1. AI analysis of the Amazon Prime app for remote control confirmed the Ads are controlled by the FireTV (https://github.com/ScriptGod1337/amazon-vod-noads/blob/main/analysis/primevideo_cast_ads_ai_prompt.md)


### Firebat APK
Ok but I doubt that Amazon develops a complete own code base for FireTV - probably a similar app exists.
After some AI research it pointed me to Firebat. Let's analyze this..
1. Downloaded it from FireTV and asked AI for the analysis, if the same Ads logic. And yes it does
1. AI confirmed that patch should work

But we need to have a rooted device to patch the system.

#### Firebat deeper analysis
Let's analyze the Firebat APK: are there any other possible fixing options

| Approach | Preroll | Midroll | Seek-Forced | No Ad Traffic | Difficulty | Breakage Risk |
|----------|:-------:|:-------:|:-----------:|:-------------:|:----------:|:-------------:|
| **AdBreakSelector patch** | **Yes** | **Yes** | **Yes** | No | **Low** | **Very low** |
| DNS/host blocking | No | No | No | Beacons only | Low | None |
| MITM proxy | Maybe | Maybe | Maybe | Yes | Very high | High |
| Block ad plan API | — | — | — | Yes | Low | **Breaks all playback** |
| Patch `AdPlaybackFeature` | No | No | No | No | Medium | Low |
| Patch `AdClipState.enter()` | No | No | No | No | Medium | High (stuck state) |
| Patch `PrepareAdPlan` | Yes | Yes | Yes | Yes | Low | High (NPE risk) |
| Patch `hasPlayableAds()` | No | No | No | No | Low | Low |
| Patch `AdsConfig` flags | Partial | Partial | No | No | High | Medium |
| Patch `ContentType` parser | No* | No* | Yes | No | Low | Low |
| Force `AdInsertion.NONE` | Yes | Yes | Yes | Yes | Medium | Medium |
| Return `EmptyAdPlan` | Yes | Yes | Yes | Partial | Medium | Medium |
| Patch monitoring state only | No | Yes | No | No | Low | Low |

==> need to be rooted FireTV and no DNS patch (https://github.com/ScriptGod1337/amazon-vod-noads/blob/main/ANALYSIS.FIRETV.md)

# AI app
Ok they said Vibe Coding is the new shit :). Can we create our own APK?

## Costs
AI estimated the costs for one flat rate

### POC
| Phase                         | Effort         |
|-------------------------------|----------------|
| Phase 1 — RE API (from Kodi)  | 3–5 days       |
| Phase 2 — ExoPlayer + Widevine| 1–2 weeks      |
| Phase 3 — Auth & Token Flow   | 1–2 weeks      |
| Phase 4 — Basic Player UI     | 3–5 days       |
| **Total**                     | 3–5 weeks      |

### Full app
| Feature                        | Tokens        |
|--------------------------------|---------------|
| Content catalog browsing       | 500K–1M       |
| Search                         | 200K–400K     |
| Content detail page            | 200K–400K     |
| Thumbnail/poster images        | 100K–200K     |
| Watchlist / continue watching  | 300K–500K     |
| Fire TV remote/D-pad (Leanback)| 1M–2M         |
| Seasons/episodes browser       | 400K–800K     |
| UI polish                      | 300K–600K     |
| **Total**                      | 3M–6M         |

#### Cost Summary
| Scenario          | Tokens | Cost  |
|-------------------|--------|-------|
| Optimistic        | 3M     | ~$9   |
| Realistic         | 5M     | ~$15  |
| Heavy debug loops | 8M     | ~$24  |

## Full Project (PoC + App)
| Scenario   | Tokens   | Cost    |
|------------|----------|---------|
| Optimistic | 6M       | ~$18    |
| Realistic  | 8M       | ~$24    |
| Worst case | 11M      | ~$33    |
| **Total**  |          | ~$75    |

## Development

## PoC
1. Create a container for development by AI. Allows this container to access my FireTV
1. Let it run during my working meetings...
1. ...very surprised that during the meeting my living room TV started...
1. ...the AI was installing the PoC and remotely testing it ;)
1. ...AI confirmed yes it works: I can play an Amazon Prime video. It debugged by controlling my FireTV and "looking" at the screen via adb

## Missing features
1. AI started implementing basic features from Kodi - already looked good
1. Add missing feature from Kodi by prompting the AI
    1. AI implemented
    1. AI reverse engineered how to implement it
Testing happened on FireTV as well as emulator.

## Fancy Phase + bugfix
I wanted to have fancy UI and features not really necessary.
This phase took quite a while - especially bugfixes after UI redesign. I need several guidance here over days
Testing happened on FireTV as well as emulator.

### Costs
Theoretical token costs

| Phase                | Time    | Cost  | Comments
|----------------------|---------|-------|----------------------------------|
| PoC                  | 0.5 day | ~$30  |                                  |
| 1st usable vers      | ~2 days | ~$50  |                                  |
| Fancy UI + features  | ~3 days | ~$400 | Heavy debugging (claude + codex) |
| **Total**                      | ~$500 |                                  |

Real Claude Max + ChatGPT Pro for 1 month - shared for another project

# Learnings
- AI can very good reverse engineer code
- AI can vibe code a PoC or 80-90% solution very fast - but debugging is hard sometimes
- ...might I was able to do it fast? reverse engineering and 80-90% solution -> no chance; debugging? maybe... but the AI choose Kotlin I'm not so aware off
- Claude Sonnet is a good orchestrator
- Codex **fast**/good coder
- Claude Sonnet tries to solve problems to the end vs. Codex stops more at intermediate steps and confirms the next step
- AI can debug via adb and screenshot interpretation
- How to tell the AI what I need
- Amazon Prime tracks progress only for items in watchlist at the server ;)
