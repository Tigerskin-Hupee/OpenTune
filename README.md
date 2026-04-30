# OpenTune

<img src="./assets/outertune.webp" height="88" alt="OpenTune app icon">

A Material 3 YouTube Music player for Android — powered by yt-dlp

[![License](https://img.shields.io/github/license/Tigerskin-Hupee/OpenTune)](https://www.gnu.org/licenses/gpl-3.0)
[![Latest release](https://img.shields.io/github/v/release/Tigerskin-Hupee/OpenTune?include_prereleases)](https://github.com/Tigerskin-Hupee/OpenTune/releases)

[<img src="assets/badge_github.png" alt="Get it on GitHub" height="40">](https://github.com/Tigerskin-Hupee/OpenTune/releases/latest)

---

## What is OpenTune?

OpenTune is a fork of [OuterTune](https://github.com/DD3Boh/OuterTune) that replaces the traditional YouTube stream resolution with **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** — the most actively maintained YouTube downloader available.

### Why yt-dlp?

YouTube frequently changes its internal stream cipher and API formats, causing music players to break silently. yt-dlp is a community-maintained project that stays ahead of every YouTube change.

OpenTune **automatically downloads and updates yt-dlp in the background** — so when YouTube changes its format, the app self-heals without needing an APK update.

---

## Features

### YouTube Music
- Search and play from YouTube Music directly
- Recommended tracks on the home screen (YouTube Music home feed)
- Share any `youtube.com`, `youtu.be`, or `music.youtube.com` link to the app to play instantly

### yt-dlp Engine
- Auto-downloads the yt-dlp binary from GitHub Releases on first launch
- Checks for updates every 24 hours in the background — no APK update needed when YouTube changes
- Stream URL resolved via YouTube player API (fast path), yt-dlp used as fallback
- Status visible in **Settings → Experimental**

### Local Music
- Plays MP3, OGG, FLAC, WAV, AAC and more
- Custom tag extractor — no broken MediaStore metadata
- Local and YouTube songs can coexist in the same queue

### Player & UI
- Material 3 design with dynamic color theming
- Multiple independent queues
- Synchronized lyrics — word-by-word / karaoke (LRC, TTML)
- Audio normalization, tempo and pitch adjustment
- Android Auto support
- Adaptive layout for phones and tablets
- Android 8 (Oreo) and higher

---

## How yt-dlp Integration Works

```
Search / Recommendation → tap to play
        ↓
  MusicService receives MediaItem (videoId)
        ↓
  StreamResolver checks in-memory cache (5h TTL)
        ↓ cache miss
  YouTube ANDROID player API  ←— primary, no binary needed
        ↓ if fails (geo-block, etc.)
  yt-dlp binary               ←— fallback, auto-updated
        ↓
  CDN stream URL → ExoPlayer
```

yt-dlp is kept in the app's private storage and updated silently whenever a new release is published on the [official yt-dlp repository](https://github.com/yt-dlp/yt-dlp/releases).

---

## Screenshots

<img src="./assets/main-interface.jpg" alt="Home screen and library" />
<br/><br/>
<img src="./assets/player.jpg" alt="Now playing screen"/>

---

## Building

Requirements: Android Studio Ladybug or newer, JDK 17+, Android SDK 36.

```bash
git clone --recurse-submodules https://github.com/Tigerskin-Hupee/OpenTune.git
cd OpenTune
./gradlew assembleRelease
```

For detailed setup and contribution guidelines, see [CONTRIBUTING.md](./CONTRIBUTING.md).

---

## Attribution

OpenTune is built on top of:

- [OuterTune](https://github.com/DD3Boh/OuterTune) — direct upstream (local music + multi-queue architecture)
- [InnerTune](https://github.com/z-huang/InnerTune) — original base project
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) — stream resolution engine

---

## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project are owned by the respective owners.
