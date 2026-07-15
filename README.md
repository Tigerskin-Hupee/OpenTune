# OpenTune

<div align="center">
  <img src="./assets/ic_launcher_round.png" alt="OpenTune" width="100" />

  **v2.0.9** · Material 3 · Android 7+

  [![License](https://img.shields.io/github/license/OuterTune/OuterTune)](https://www.gnu.org/licenses/gpl-3.0)
  [![Release](https://img.shields.io/github/v/release/Tigerskin-Hupee/OpenTune)](https://github.com/Tigerskin-Hupee/OpenTune/releases/latest)
</div>

A Material 3 music player for Android — streams YouTube audio **without** the YouTube Music API.

---

## What is OpenTune?

OpenTune is a fork of [OuterTune](https://github.com/OuterTune/OuterTune) that replaces the YouTube Music API with a multi-path streaming engine. The primary extraction backend is **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)** — the same pure-JVM library used by NewPipe, InnerTune, and Gramophone.

This means:
- No YouTube Music account required
- No region restrictions
- No official API keys needed
- Works anywhere YouTube is accessible
- Resilient to YouTube player JS updates (multi-fallback waterfall)

---

## Download

Get the latest APK from [**Releases**](https://github.com/Tigerskin-Hupee/OpenTune/releases/latest).

Install directly — no need to uninstall previous versions (consistent release signing).

---

## Features

- **YouTube streaming** — search and stream any song from YouTube
- **Resilient playback** — 3-path waterfall (Piped → NPE → Native) survives YouTube API changes
- **Tabbed search** — Songs / Artists / Albums / Playlists with infinite scroll
- **Personalised recommendations** — home feed based on your listening history
- **Infinite radio** — auto-fetches related songs when the queue runs low
- **Album & Playlist playback** — tap any album or playlist to play its songs
- **Synced lyrics** — fetched from lrclib.net, displayed word-by-word
- **Local audio playback** — MP3, FLAC, OGG, Opus, and more
- **Download** — cache YouTube audio for offline playback
- **Library** — like, save, and organize songs into playlists
- **Playback Diagnostics** — Settings → About → run a live stream test and copy a full report
- **Play history & statistics**
- **Audio effects** — normalization, equalizer, tempo/pitch adjustment
- **Material 3 design** — dynamic color, dark mode
- **Android Auto** support
- **Minimum SDK: Android 7 (API 24)**

---

## Architecture

### Streaming Waterfall

OpenTune tries three independent paths in order. If one fails, it falls through to the next:

| Priority | Path | When it wins |
|---|---|---|
| 1 | **Piped** public proxy | Fast (< 500 ms); works when a public Piped instance is reachable |
| 2 | **NewPipeExtractor** (Rhino JS) | Most reliable; handles any player version by executing real YouTube player JS |
| 3 | **Native Innertube** cascade | Fallback; direct-URL clients (ANDROID_VR, ANDROID_MUSIC) bypass sig decode entirely |

> **Why three paths?** YouTube rotates its player JS every 1–2 weeks. New players can use different
> signature-decode obfuscation that breaks the Kotlin regex parser (`sigOps=null`). NPE executes the
> actual player JS via Rhino and is immune to obfuscation changes, at the cost of ~1–2 s startup
> latency (cached after the first play per session).

### Full Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Audio Streaming** | [NewPipeExtractor v0.26.3](https://github.com/TeamNewPipe/NewPipeExtractor) | Primary: resolves YouTube video IDs to direct CDN audio URLs; handles PoToken, signature ciphers, n-parameter throttling via Rhino JS execution |
| **Streaming Fallback** | [Piped](https://github.com/TeamPiped/Piped) public instances | Server-side YouTube proxy; first in waterfall, no auth needed |
| **Streaming Fallback 2** | Native Innertube API | WEB_EMBEDDED_PLAYER + ANDROID_VR/ANDROID_MUSIC clients; direct-URL clients bypass sig decode |
| **Search** | NewPipeExtractor `SearchInfo` | Songs / Artists / Albums / Playlists via content filters; paginated with infinite scroll |
| **Recommendations** | NewPipeExtractor `SearchInfo` | Personalised from play history (top artists); falls back to popular music |
| **Radio / Related** | NewPipeExtractor `StreamInfo.relatedItems` | Seeds a continuous radio queue from any track; auto-extends when queue runs low |
| **Lyrics** | [lrclib.net](https://lrclib.net) | Free, no-auth synced/plain lyrics API; results cached in local Room DB |
| **Local Lyrics** | `.lrc` file lookup | Reads sidecar `.lrc` files next to local audio files |
| **Image Loading** | [Coil 3](https://coil-kt.github.io/coil/) + OkHttp | Network thumbnails; local audio embedded art via `MediaMetadataRetriever` |
| **Download** | ExoPlayer DownloadManager | Resolves `youtube:<id>` URIs to real audio stream URLs at download time |
| **Player** | ExoPlayer (Media3) | Background playback, notification controls, Android Auto |
| **Database** | Room | Songs, playlists, lyrics cache, play history, queue persistence |
| **DI** | Hilt | Dependency injection throughout |

---

## Playback Diagnostics

If a song fails to play, go to **Settings → About → Playback Diagnostics** and tap **Run Test**.

The diagnostic panel shows:

```
PlayerJS: id=9d2ef9ef  fetched=42s ago
SigOps : FAIL [d5-noTableStr(a)]    ← Kotlin parser broke on new player
NPE    : v0.26.3                     ← Rhino-based fallback still works
[14:23:01] OK   dQw4w9WgXcQ  1843ms [NPE]  rr3.sn-xxx.googlevideo.com n=dec_np
```

Tap **Copy Report** to share the full log when reporting issues.

---

## Key Differences from OuterTune

| | OuterTune | OpenTune |
|---|---|---|
| Streaming backend | YouTube Music API (requires auth) | Multi-path: Piped → NPE → Native (no auth) |
| Search | YouTube Music search | YouTube search with 4 tabs + infinite scroll |
| Recommendations | Personalised YTM feed | Personalised via play history |
| Album / Playlist playback | Full API-based | Direct playback via NPE |
| Lyrics | KuGou, LrcLib, local | LrcLib, local |
| Account sync | YouTube Music account | — (local only) |
| Diagnostics | — | Live stream test + full report in Settings |

---

## Building

```bash
git clone https://github.com/Tigerskin-Hupee/OpenTune.git
cd OpenTune
./gradlew assembleCoreRelease
```

APK output: `app/build/outputs/apk/core/release/OpenTune-<version>-release.apk`

No API keys or accounts required.

---

## Version History

| Version | Highlights |
|---|---|
| **2.0.9** | Fix all downloads failing: the download data source had no network upstream (only worked for already-cached songs); now reads through player cache → OkHttp (IPv4-pinned, same as playback) |
| **2.0.8** | Search-result download icon now shows three states: not downloaded → downloading (spinner) → completed ✓ (previously showed ✓ while still downloading) |
| **2.0.7** | Download everywhere: per-song download button on YouTube search results (auto-adds to library); download-all in the queue menu |
| **2.0.6** | Fix crash on duplicate-artist merge (UNIQUE constraint in song_artist_map): merge UPDATEs now use OR IGNORE; async db.query/transaction blocks catch exceptions and log them as non-fatals instead of killing the app |
| **2.0.5** | In-app update checker (GitHub Releases → download → installer prompt); crash logger with copyable report in Settings → About; prefetch next song's stream URL for near-instant track transitions |

---

## Attribution

- [OuterTune](https://github.com/OuterTune/OuterTune) — base fork (GPL-3.0)
- [InnerTune](https://github.com/z-huang/InnerTune) — original foundation
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube extraction engine
- [Gramophone](https://github.com/FoedusProgramme/Gramophone) — tag extractor and LRC lyrics parser
- [lrclib.net](https://lrclib.net) — free synced lyrics database
- [Piped](https://github.com/TeamPiped/Piped) — open-source YouTube proxy

---

## Disclaimer

This project is not affiliated with, funded, authorized, endorsed by, or in any way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project are owned by the respective owners.
