# OpenTune

<img src="./assets/outertune.webp" height="88" alt="OpenTune app icon">

A Material 3 music player for Android — streams YouTube audio **without** the YouTube Music API.

[![License](https://img.shields.io/github/license/OuterTune/OuterTune)](https://www.gnu.org/licenses/gpl-3.0)

---

## What is OpenTune?

OpenTune is a fork of [OuterTune](https://github.com/OuterTune/OuterTune) that replaces the YouTube Music API with **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)** — the same pure-JVM library used by NewPipe, InnerTune, and Gramophone.

This means:
- No YouTube Music account required
- No region restrictions
- No official API keys needed
- Works anywhere YouTube is accessible

---

## Architecture

| Layer | Technology | Purpose |
|---|---|---|
| **Audio Streaming** | [NewPipeExtractor v0.26.1](https://github.com/TeamNewPipe/NewPipeExtractor) | Resolves YouTube video IDs to direct CDN audio URLs (handles PoToken, signature ciphers, n-parameter throttling) |
| **Search** | NewPipeExtractor `SearchInfo` | Songs, Artists, Albums/Playlists via `music_songs` / `music_artists` / `music_albums` content filters |
| **Recommendations** | NewPipeExtractor `SearchInfo` | Aggregates popular music search terms for a global feed |
| **Radio / Related** | NewPipeExtractor `StreamInfo.relatedItems` | Seeds a continuous radio queue from any track; auto-extends when queue runs low |
| **Lyrics** | [lrclib.net](https://lrclib.net) | Free, no-auth synced/plain lyrics API; results cached in local Room DB |
| **Local Lyrics** | `.lrc` file lookup | Reads sidecar `.lrc` files next to local audio files |
| **Image Loading** | [Coil 3](https://coil-kt.github.io/coil/) + OkHttp | Network thumbnails via `coil-network-okhttp`; local audio embedded art via `MediaMetadataRetriever` |
| **Local Playback** | [Gramophone](https://github.com/FoedusProgramme/Gramophone) tag extractor | Parses ID3/FLAC/OGG tags; handles `\\`-delimited multi-value fields correctly |
| **Player** | ExoPlayer (Media3) | Background playback, notification controls, Android Auto |
| **Database** | Room | Stores songs, playlists, lyrics cache, play history, queue persistence |
| **DI** | Hilt | Dependency injection throughout |

---

## Features

- **YouTube streaming** — search songs, browse recommendations, start radio from any track
- **Tabbed search** — Songs / Artists / Albums tabs powered by NewPipeExtractor
- **Infinite radio** — auto-fetches related songs when the queue runs low
- **Synced lyrics** — fetched from lrclib.net, displayed word-by-word with the Gramophone parser
- **Local audio playback** — MP3, FLAC, OGG, Opus, and more
- **Multiple queues** — manage several queues simultaneously
- **Download** — cache YouTube audio for offline playback
- **Library** — like, save, and organize songs into playlists
- **Play history & statistics**
- **Audio effects** — normalization, equalizer, tempo/pitch adjustment
- **Material 3 design** — dynamic color, dark mode
- **Android Auto** support
- **Minimum SDK: Android 8 (Oreo, API 24)**

---

## Key Differences from OuterTune

| | OuterTune | OpenTune |
|---|---|---|
| Streaming backend | YouTube Music API (requires auth) | NewPipeExtractor (no auth) |
| Search | YouTube Music search | YouTube search via NewPipeExtractor |
| Lyrics | KuGou, LrcLib, local | LrcLib, local |
| Account sync | YouTube Music account | — (local only) |
| Recommendations | Personalised YTM feed | Global popular music feed |

---

## Building

```bash
git clone https://github.com/tigerskin-hupee/opentune.git
cd opentune
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/core/debug/OpenTune-1.0.0-alpha1-debug.apk`

---

## Attribution

- [OuterTune](https://github.com/OuterTune/OuterTune) — base fork (GPL-3.0)
- [InnerTune](https://github.com/z-huang/InnerTune) — original foundation
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube extraction engine
- [Gramophone](https://github.com/FoedusProgramme/Gramophone) — tag extractor and LRC lyrics parser
- [lrclib.net](https://lrclib.net) — free synced lyrics database

---

## Disclaimer

This project is not affiliated with, funded, authorized, endorsed by, or in any way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project are owned by the respective owners.
