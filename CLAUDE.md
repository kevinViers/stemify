# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Stemify splits a music library into stems (vocals, drums, bass, other, plus guitar/piano for
6-stem models) and plays them back with live per-stem mixing. Pieces:

- **server/stemd.py**: GPU daemon that walks `Music/` forever and writes stems to `Stems/`. Runs
  in Docker on an unraid box (`server/stemd.Dockerfile`). `stemd_web.py` / `stemd_ui.html` are
  its status page.
- **server/stemserve.py**: stdlib-only HTTP server for finished stems (index with ETag, peaks,
  range requests, Opus transcodes, "Stem Now" queueing). Keep it free of third-party imports.
- **android/**: Kotlin / Jetpack Compose / Media3 phone app and Android Auto. Plex is the source
  of library metadata; stemserve supplies stems.
- **stemify/** (Python 3.12, PySide6 + QML): desktop apps and shared separation code.
- **stemify/live/** (in progress): PipeWire virtual sink that separates audio in real time.
  Design in `liveProcessing.md`, measurements in `docs/live/`.
- `stemSlider/PLAN.md` (hardware controller) and `specs.txt` (original separator design) are
  planning docs only.

## Commands

Python is managed by uv; torch comes from the ROCm 7.2 index (AMD GPU on this machine).

```sh
uv sync
uv run stemify            # drag-drop separator
uv run stemify-player     # Excel-styled desktop player
uv run stemify-mobile     # the Android UI as a desktop app
uv run stemify-live       # or: python -m stemify.live --separator passthrough
uv run stemify-worker-cli # separation worker from the command line
```

Never open GUI windows (the user's main monitor is in use). Check desktop apps headless:

```sh
QT_QPA_PLATFORM=offscreen uv run stemify-player --no-mpris --screenshot /tmp/p.png@5 --quit-after 6
QT_QPA_PLATFORM=offscreen uv run stemify-mobile --no-mpris --screenshot /tmp/m.png@5 --quit-after 6
```

Both apps also take `--size WxH` and `--eval 'js@SECONDS'` (run JS against the QML window);
`--screenshot` and `--eval` are repeatable.

Other Python checks:

```sh
uv run python scripts/connect_selftest.py   # Stemify Connect end to end, offscreen, throwaway XDG dirs
uv run python -m stemify.live.selftest --seconds 20   # live path latency/glitch check, nothing reaches the speakers
uvx ruff check stemify server scripts       # lint (not a dependency; no config, defaults)
STEMS_DIR=/path/to/Stems CACHE_DIR=~/.cache/stemserve python3 server/stemserve.py
```

There is no Python unit test suite. Android has JVM unit tests (JDK 21; `android/local.properties`
points at the SDK):

```sh
cd android
JAVA_HOME=/path/to/jdk-21 ./gradlew testDebugUnitTest
JAVA_HOME=/path/to/jdk-21 ./gradlew testDebugUnitTest --tests 'com.stemify.mobile.DspTest'
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleRelease   # app/build/outputs/apk/release/
```

`MediaCacheTest` and `StemIndexTest` stream from a stemserve on `localhost:8765` and skip
themselves without one. `app/src/debug` holds a screenshot / back-gesture test hook that release
builds leave out. `Stemify.apk` at the repo root is the release build to sideload.

## Architecture

**Stem folder contract.** `stemify/library_core.py` (no Qt, so the server image doesn't need
PySide6) defines the layout shared by stemd, stemserve and every client:
`Music/Artist/Album/01 Song.flac` → `Stems/Artist/Album/01 Song/01 Song - <stem>.flac`, written
into a hidden `.partial` folder first, with the `.stemify.json` marker written last. A folder
without the marker is not done. When one folder has a song in several formats, `SOURCE_RANK`
picks the source. Changing anything here affects stemd, stemserve, the desktop apps and the
Android `stems/` matcher, so keep them in sync.

**Plex path matching.** Plex reports paths as its container sees them, so clients match Plex
files to stem folders by path suffix plus duration, and learn the path prefix from the first
confident match (Android `stems/`, desktop `stemify/player/stems.py`). Songs without stems play
the original file from Plex.

**Desktop package layering.**
- `stemify/app.py`, `controller.py`, `jobs_model.py`, `qml/`: the separator UI. Separation runs
  in a separate process (`worker_client.py` ↔ `stemify/worker/`, backends for Demucs and
  RoFormer via audio-separator) so the UI never blocks. Model ids live in `catalog.py`.
- `stemify/player/`: Stemify Player, but also the shared core: `engine.py` (audio engine),
  `player.py`, `plex.py`, `stems.py`, `crossfade.py`, `config.py`, `mpris.py`.
- `stemify/mobile/` reuses `player/`'s engine, Plex/stem clients and config, adding its own QML
  screens, EQ, downloads and playback wrapper. `config.use("mobile", ...)` points settings to
  `~/.config/stemify/mobile.json` and its own cache dir (one app per process).
- `stemify/connect/`: Stemify Connect (nearby control) over a UDP beacon / TCP on Wi-Fi and over
  Bluetooth. The wire protocol is in `docs/CONNECT.md` and is shared with Android
  (`android/.../connect/`, covered by `ConnectWireTest`). Change both sides together.

**Android ↔ desktop parity.** Stemify Mobile mirrors the Android app screen for screen, and the
EQ maths (biquads, anti-clip preamp, APO import) match across both (`docs/EQUALIZER.md`;
`docs/eq/make_figures.py` draws graphs from that maths). A behaviour change in one usually needs
the same change in the other.

## Conventions

- Commit subjects name the product and describe the user-visible result, e.g.
  `Stemify Mobile: downloaded albums show their release date, as JAN.01.70`.
- READMEs (`README.md`, `server/README.md`, `android/README.md`, `stemify/player/README.md`,
  `stemify/mobile/README.md`, `stemify/live/README.md`) are written in plain, user-facing
  language. Update the relevant one when behaviour changes.
- The default server address is `192.168.86.2` (Plex `:32400`, stemserve `:8765`); the unraid
  shares are mounted at `/var/mnt/unraid/Music/`.
