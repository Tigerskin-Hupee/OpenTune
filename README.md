# OpenTune

<img src="./assets/outertune.webp" height="88" alt="OpenTune app icon">

A Material 3 music player for Android that plays YouTube Music via **yt-dlp** — no broken streams, no APK updates needed when YouTube changes.

[![License](https://img.shields.io/github/license/Tigerskin-Hupee/OpenTune)](https://www.gnu.org/licenses/gpl-3.0)
[![Latest release](https://img.shields.io/github/v/release/Tigerskin-Hupee/OpenTune?include_prereleases)](https://github.com/Tigerskin-Hupee/OpenTune/releases)

[<img src="assets/badge_github.png" alt="Get it on GitHub" height="40">](https://github.com/Tigerskin-Hupee/OpenTune/releases/latest)

---

## What makes OpenTune different?

Most YouTube Music clients break silently whenever YouTube changes its internal stream format. OpenTune solves this by using **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** as the stream engine:

- Downloaded automatically on first launch
- Updated in the background every 24 hours
- When YouTube changes — yt-dlp updates, the app keeps working

No APK release needed to fix playback.

---

## Features

- Search and play from YouTube Music
- YouTube Music recommendations on the home screen
- Share any YouTube link to the app to play instantly
- Local music playback (MP3, FLAC, OGG, etc.)
- Material 3 design, lyrics, audio effects, Android Auto
- Android 8+ supported

---

## Screenshots

<img src="./assets/main-interface.jpg" alt="Home screen" />
<br/><br/>
<img src="./assets/player.jpg" alt="Now playing"/>

---

## Building

```bash
git clone --recurse-submodules https://github.com/Tigerskin-Hupee/OpenTune.git
cd OpenTune
./gradlew assembleRelease
```

---

## Based on

- [OuterTune](https://github.com/DD3Boh/OuterTune) — upstream fork
- [InnerTune](https://github.com/z-huang/InnerTune) — original base
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) — stream engine

---

## Disclaimer

Not affiliated with YouTube or Google LLC. All trademarks belong to their respective owners.
