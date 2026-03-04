# How I Fired Amazon's Ads with an AI I Barely Supervised

> **TL;DR:** I pay €8,99/month for Amazon Prime. Amazon now charges an extra €2,99/month if you want to watch *without* ads — a move so audacious that a Munich court ruled it illegal in December 2025. I spent ~$500 in AI subscriptions and a long weekend of effort to fix this myself. Totally worth it. Probably.

---

## Act 1: The Five Stages of Ad Grief

It starts innocently. You sit down on the couch, open Amazon Prime Video on your FireTV, and before your movie begins — *bam* — ads. Not just any ads. *Loud* ads. For products you already own. Ads that mute themselves when you try to fast-forward. Ads that know you're watching.

You paid for Prime. You paid for the *ad-free experience*. And yet here you are, learning about yogurt brands you never asked about.

Stage 1: denial. "Maybe I'm misremembering." *(ad plays again)*  
Stage 2: anger. "This is illegal in at least three countries." *(Spoiler: a Munich court later agreed)*  
Stage 3: bargaining. "Let me just... find a workaround."  
Stage 4: googling "how to block amazon ads firetv" at 11pm.  
Stage 5: acceptance — specifically, acceptance that this is now a weekend project.

---

## Act 2: Kodi — Good Enough, But Not Good Enough

The internet suggests Kodi. There's even a plugin. It works! Mostly. Mostly works in the sense that:

* It plays video ✓
* It tracks zero watch progress (started "The Boys" at episode 1 again for the fifth time) ✗
* It crashes the audio decoder on the FireTV roughly every third episode ✗
* No fancy progress bar with thumbnails ✗

But here's the critical insight: **the ads are injected by the client, not the server.** Amazon doesn't hide them in the stream — the app on the FireTV decides to show them. Which means: replace the app, no ads. Simple.

*Narrator: It was not simple.*

---

## Act 3: ReVanced and the AI's First Field Trip

ReVanced is a project that patches Android apps to remove ads. There's even a patch for Amazon Prime Video — for a version from last year. The current version is... not that.

This is where the AI enters the story.

The task: analyze the old APK patch, reverse-engineer whether it applies to the current version, and if not, adapt it. Normally this requires fluency in Dalvik bytecode and several cups of coffee. With an AI? Just a prompt.

First, I sandboxed the AI in a container (can't have it buying things on Amazon on my behalf) and pointed it at the problem.

The AI immediately started making hundreds of download requests. For 15 minutes. Turns out our corporate bit-protection filter had opinions about decompiler tools. The AI was blocked, confused, and quietly disappointed.

Round two: I manually downloaded the APKs and handed them over like a parent giving a toddler safe scissors. This time the AI decompiled both, compared the bytecode, spotted the differences, adapted the patch, and updated the ReVanced config. Confirmed working in an emulator.

Excellent. Ship it.

---

## Act 4: Four Dead Ends, One Good Spreadsheet

**Dead end #1:** Install the patched Amazon Prime APK on the FireTV. Doesn't work. FireTV won't run a Play Store app — different architecture, different system dependencies, also Amazon clearly anticipated this attack vector.

**Dead end #2:** Maybe the Amazon mobile app can remote-control the FireTV and skip ads that way? No. The FireTV controls the ads. The mobile app is just a remote. The AI confirmed this with a thorough analysis of the network traffic. Very politely. Very thoroughly. Still no.

**Dead end #3:** Firebat — the actual APK Amazon ships on FireTV under the hood. Same ad logic. Patchable! But only on a rooted device. I don't have a rooted device. I have a warranty.

At this point the AI produced a beautiful 13-row comparison table of every conceivable patching approach, rated by preroll, midroll, seek-forced ad bypass, difficulty, and breakage risk. It was the most thorough spreadsheet I've seen produced in response to not wanting to watch a yogurt commercial.

Conclusion: patch works, need root, don't have root. Next idea.

---

## Act 5: "They Said Vibe Coding is the New Thing"

New plan: build a native Android app from scratch that talks directly to Amazon's API, plays streams via ExoPlayer with Widevine DRM, and contains exactly zero ad logic.

The AI estimated this would take 3–5 weeks of human development time. I asked it to just do it while I was in meetings.

I sat down to a Monday morning standup. The AI sat down to reverse-engineering the Kodi plugin's Python source code, extracting every API endpoint, auth flow, and Widevine license handshake. By the time I was done explaining my sprint goals, the AI had a working Kotlin scaffold.

By the afternoon it had auth, token refresh, and a catalog browser.

Then, mid-standup on the second day, **my living room TV turned on.**

The AI had finished building the app, pushed it to the FireTV via ADB, launched it, and was now remotely testing whether it could play a video — by actually playing a video. In my living room. While I was in a call about quarterly OKRs.

It worked. First video played. No ads.

How did it confirm this without eyes? ADB. The AI took screenshots of the FireTV screen via `adb shell screencap`, pulled them, looked at them, and decided whether the video was playing or crashing. An AI, watching my TV via a debug bridge, to verify its own homework. Honestly more reliable than me.

---

## Act 6: Polish, Pain, and the Bill

The PoC phase cost ~$30 in AI tokens and half a day. Getting to a usable app with all features: another ~$50 over the next day or two. Then I got greedy and asked for a fancy UI.

Two intense days, ~$400, and the combined forces of Claude and Codex later — I had a Leanback-compatible Fire TV UI with category filters, search suggestions, watchlist, and an adaptive bitrate player that decoded H.264 in Widevine L1 hardware secure mode.

This phase was different from the PoC. The AI needed me. Several rounds of guidance: "that button is in the wrong place", "the crash happens when you scroll fast", "yes I know it compiles, but it looks terrible." Turns out AI is great at building things and less great at knowing what looks good on a TV from three metres away.

Total cost: ~$500 in AI subscriptions (shared across other projects). Total human hours: a few meetings plus several evenings of back-and-forth. Total ads watched since: zero.

> **Bonus:** Amazon was sued by the Verbraucherzentrale Bundesverband and the Landgericht München I ruled in December 2025 that forcing ads on existing Prime subscribers was unlawful. The AI beat them to it by several months.

---

## What I Actually Learned

* **AI is genuinely good at reverse engineering.** Give it a compiled APK or obfuscated Python plugin and it will map it like a detective. Patiently. Without complaining about the bytecode.
* **Could I have done this myself?** Reverse engineering the API: no chance. Building the 80% PoC: no chance. Debugging? Maybe — but the AI picked Kotlin and I'm not exactly fluent. So: also no.
* **The 80% solution is fast. The last 20% is where your money goes.** The PoC took half a day. The production-quality debug loop took two.
* **Claude Sonnet doesn't give up.** It will keep iterating on a broken DRM handshake until it works or until you tell it to stop. Codex is faster but stops at checkpoints and asks permission. Pick your fighter.
* **Let AIs review each other.** Claude wrote it, Codex reviewed it, things got fixed faster than either would have managed alone. Turns out AIs are less defensive about criticism than humans.
* **Sandbox your AI.** Seriously. Mine tried to download half the internet before I put it in a container. It also turned on my TV unannounced. These are features, apparently.
* **Learning to talk to the AI is a skill.** Vague prompts produce vague results. "Fix the UI" gets you nowhere. "The episode list crashes when I scroll past 20 items on the FireTV remote" gets you a fix.
* **Amazon only tracks watch progress for items in your watchlist.** This one is just a fun fact. Add things to your watchlist.

---

*Source code and AI prompts: [github.com/ScriptGod1337/amazon-vod-noads](https://github.com/ScriptGod1337/amazon-vod-noads)*  
*Sandbox container: [github.com/ScriptGod1337/ai-sandbox-vscode](https://github.com/ScriptGod1337/ai-sandbox-vscode)*