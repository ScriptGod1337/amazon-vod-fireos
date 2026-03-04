---
marp: true
theme: default
paginate: true
---

# How I Fired Amazon's Ads

## with an AI I Barely Supervised

*A story about $500, a Dalvik decompiler, and a TV that turns itself on*

---

## I pay for Prime.

## Amazon still shows me ads.

> €8,99/month. Plus €2,99/month extra if you want it **without** ads.
> A Munich court called this illegal in December 2025.
> I called it a weekend project.

→ Replace the client. No ads.

*Narrator: It was not simple.*

---

## Attempt 1 — Kodi Plugin

✅ Plays video  
❌ No watch progress tracking  
❌ Crashes audio decoder ~every 3rd episode  
❌ No fancy scrubber thumbnails  

**Insight gained:** ads are injected **client-side**.
The stream itself is clean.

---

## Attempt 2 — ReVanced Patch

ReVanced patches Android APKs to remove ads.
There's even one for Amazon Prime Video.

**Catch:** it's for an older version of the app.

**Plan:** ask AI to adapt the patch for the current version.

---

## AI's First Field Trip

```
Me:  Analyze these two APKs and update the patch.
AI:  *makes 300 download requests for 15 minutes*
AI:  ...
AI:  I appear to be blocked.
Me:  Here, I downloaded the tools manually.
AI:  Ah. Decompiling now.
```

✅ AI compared bytecode, adapted patch, confirmed in emulator.

---

## Dead End #1

**Install patched Amazon APK on FireTV?**

❌ FireTV won't run Play Store APKs.
Missing Google services. System conflicts.
Amazon anticipated this. Rude of them.

---

## Dead End #2

**Remote-control FireTV from patched phone app?**

❌ FireTV controls the ads. Phone is just a remote.
AI confirmed via network traffic analysis.

*Very politely. Very thoroughly. Still no.*

---

## Dead End #3 — Firebat

The actual APK Amazon ships on FireTV.
Same ad logic. **Patchable!**

...on a rooted device.

I don't have a rooted device.
I have a warranty.

---

## The Spreadsheet

AI produced a 13-row patch strategy comparison:

| Approach | Preroll | Midroll | Difficulty | Risk |
| --- | --- | --- | --- | --- |
| AdBreakSelector patch | ✅ | ✅ | Low | Very low |
| DNS blocking | ❌ | ❌ | Low | None |
| Block ad plan API | ✅ | ✅ | Low | **Breaks everything** |
| Force AdInsertion.NONE | ✅ | ✅ | Medium | Medium |
| ... 9 more rows ... | | | | |

Conclusion: works, needs root. I have no root.

---

## New Plan: Just Build the App

> "They said Vibe Coding is the new thing."

**Idea:** native Android/Kotlin APK  
that talks directly to Amazon's API,  
plays DASH streams via ExoPlayer + Widevine DRM,  
and contains **exactly zero ad logic**.

---

## AI Cost Estimate

| Phase | AI Estimate |
| --- | --- |
| RE the API from Kodi | 3–5 days |
| ExoPlayer + Widevine | 1–2 weeks |
| Auth & Token Flow | 1–2 weeks |
| Basic Player UI | 3–5 days |
| **Total** | **3–5 weeks** |

*Narrator: The AI was talking about human developers.*

---

## What Actually Happened

```
Monday AM:  Me → standup meeting
Monday AM:  AI → reverse-engineers Kodi plugin Python source
Monday PM:  AI → Kotlin scaffold + auth layer
Tuesday AM: AI → catalog browser, token refresh, DRM handshake
Tuesday PM: My TV turns on by itself
```

---

## The TV Moment

The AI:

1. Finished building the app
2. Compiled the release APK
3. Pushed it to the FireTV via ADB
4. Launched the app
5. Pressed play on a video

**In my living room. During a quarterly OKR meeting.**

It worked. No ads.

How did it know? `adb shell screencap` → pull → look at screenshot → repeat.  
*An AI, watching TV via a debug bridge, to verify its own homework.*

---

## Actual Costs (German Reality Edition)

| Phase | Time | Cost |
| --- | --- | --- |
| PoC | ~half a day | ~$30 |
| 1st usable version | ~1–2 days | ~$50 |
| Fancy UI + features | ~2 days | ~$400 |
| **Total** | | **~$500** |

*Claude Max + ChatGPT Pro subscriptions, shared across projects.*

> Compare: Amazon charges **€2,99/month extra** to not show you ads.  
> That's **€35,88/year** to restore what you already paid for.  
> Also: a Munich court ruled this illegal in December 2025. Just saying.

---

## What I Learned

🔍 **AI reverse engineers well** — APKs, bytecode, proprietary APIs. No complaints.

🤔 **Could I have done this myself?**
* RE the API → no chance
* Build the PoC → no chance
* Debugging → maybe… but AI picked Kotlin. So: also no.

⚡ **80% solution is fast. Last 20% costs money.**

🐢 **Claude Sonnet doesn't give up.** Keeps iterating until it works.  
🐇 **Codex is faster** but stops at checkpoints.

🤝 **Let AIs review each other.** Claude writes, Codex reviews → faster, better results.

🏠 **Sandbox your AI.** Mine turned on my TV. Unannounced. During a call.

🗣️ **Prompting is a skill.** "Fix the UI" → nothing. "Crashes when scrolling past 20 items on the remote" → fixed.

📋 **Watchlist matters** — Amazon only syncs progress for watchlisted items.

---

## Links

**Code & AI prompts**  
github.com/ScriptGod1337/amazon-vod-noads

**APK analysis prompts**  
`.../analysis/primevideo_skipads_ai_prompt.md`

**AI dev sandbox**  
github.com/ScriptGod1337/ai-sandbox-vscode

---

# Thank you

*Total ads watched since deployment: **0***  
*Yogurt brands learned about: **0***  
*Times my TV turned on uninvited: **1** (acceptable)*  
*Munich court rulings in my favour: **1** (bonus)*