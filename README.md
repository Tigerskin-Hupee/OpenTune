# OpenTune

<div align="center">
  <img src="./assets/ic_launcher_round.png" alt="OpenTune" width="100" />

  **v1.0.7** · Material 3 · Android 8+

  [![License](https://img.shields.io/github/license/OuterTune/OuterTune)](https://www.gnu.org/licenses/gpl-3.0)
  [![Release](https://img.shields.io/github/v/release/Tigerskin-Hupee/OpenTune)](https://github.com/Tigerskin-Hupee/OpenTune/releases/latest)
</div>

A Material 3 music player for Android — streams YouTube audio **without** the YouTube Music API.

---

## What is OpenTune?

OpenTune is a fork of [OuterTune](https://github.com/OuterTune/OuterTune) that replaces the YouTube Music API with **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)** — the same pure-JVM library used by NewPipe, InnerTune, and Gramophone.

This means:
- No YouTube Music account required
- No region restrictions
- No official API keys needed
- Works anywhere YouTube is accessible

---

## Download

Get the latest APK from [**Releases**](https://github.com/Tigerskin-Hupee/OpenTune/releases/latest).

Install directly — no need to uninstall previous versions (consistent release signing).

---

## Features

- **YouTube streaming** — search and stream any song from YouTube
- **Tabbed search** — Songs / Artists / Albums / Playlists with infinite scroll
- **Personalised recommendations** — home feed based on your listening history
- **Infinite radio** — auto-fetches related songs when the queue runs low
- **Album & Playlist playback** — tap any album or playlist to play its songs
- **Synced lyrics** — fetched from lrclib.net, displayed word-by-word
- **Local audio playback** — MP3, FLAC, OGG, Opus, and more
- **Download** — cache YouTube audio for offline playback (fixed in v1.0.2)
- **Library** — like, save, and organize songs into playlists
- **Playback Diagnostics** — Settings → About, for quick debugging
- **Play history & statistics**
- **Audio effects** — normalization, equalizer, tempo/pitch adjustment
- **Material 3 design** — dynamic color, dark mode
- **Android Auto** support
- **Minimum SDK: Android 8 (Oreo, API 24)**

---

## Architecture

| Layer | Technology | Purpose |
|---|---|---|
| **Audio Streaming** | [NewPipeExtractor v0.26.1](https://github.com/TeamNewPipe/NewPipeExtractor) | Resolves YouTube video IDs to direct CDN audio URLs (handles PoToken, signature ciphers, n-parameter throttling) |
| **Search** | NewPipeExtractor `SearchInfo` | Songs / Artists / Albums / Playlists via content filters; paginated with infinite scroll |
| **Recommendations** | NewPipeExtractor `SearchInfo` | Personalised from play history (top artists); falls back to popular music |
| **Radio / Related** | NewPipeExtractor `StreamInfo.relatedItems` | Seeds a continuous radio queue from any track; auto-extends when queue runs low |
| **Lyrics** | [lrclib.net](https://lrclib.net) | Free, no-auth synced/plain lyrics API; results cached in local Room DB |
| **Local Lyrics** | `.lrc` file lookup | Reads sidecar `.lrc` files next to local audio files |
| **Image Loading** | [Coil 3](https://coil-kt.github.io/coil/) + OkHttp | Network thumbnails via `coil-network-okhttp`; local audio embedded art via `MediaMetadataRetriever` |
| **Download** | ExoPlayer DownloadManager | Resolves `youtube:<id>` URIs to real audio stream URLs at download time |
| **Player** | ExoPlayer (Media3) | Background playback, notification controls, Android Auto |
| **Database** | Room | Songs, playlists, lyrics cache, play history, queue persistence |
| **DI** | Hilt | Dependency injection throughout |

---

## Key Differences from OuterTune

| | OuterTune | OpenTune |
|---|---|---|
| Streaming backend | YouTube Music API (requires auth) | NewPipeExtractor (no auth) |
| Search | YouTube Music search | YouTube search with 4 tabs + infinite scroll |
| Recommendations | Personalised YTM feed | Personalised via play history |
| Album / Playlist playback | Full API-based | Fallback search when playlist API unavailable |
| Lyrics | KuGou, LrcLib, local | LrcLib, local |
| Account sync | YouTube Music account | — (local only) |
| Diagnostics | — | Playback Diagnostics in Settings |

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
| **1.0.7** | Search history, album/playlist song preview bottom sheet |
| **1.0.6** | Fix build: SearchInfo.getMoreItems pagination, playlist fallback type fix |
| **1.0.5** | Elegant tech blue icon redesign, infinite scroll pagination |
| **1.0.4** | Icon redesign (open-ring note concept) |
| **1.0.3** | Infinite scroll for all search tabs |
| **1.0.2** | Download fixed (youtube: URI resolution) |
| **1.0.1** | Album/playlist fallback search when PlaylistInfo API unavailable |
| **1.0.0** | First stable release |
| 1.0.0-alpha3 | Playback Diagnostics, CI/CD auto-release |
| 1.0.0-alpha2 | Personalised recommendations, Playlists search tab |
| 1.0.0-alpha1 | Initial release |

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
