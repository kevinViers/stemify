# Stemify

**Your whole music library, taken apart and put back together however you like.**

Ever wanted to hear "Carry On Wayward Son" without the vocals so you can belt it out yourself? Or
mute the drums on a song to figure out what the bass player is actually doing back there? Stemify
splits every song in your library into its separate parts (vocals, drums, bass, guitar, piano and
"other"), keeps them organised next to your music, and gives you players that let you mix them
live, on your phone, in your car, and on your desktop.

It's a home-grown, slightly over-engineered love letter to karaoke, music nerdery and the joy of
pressing the "mute vocals" button at exactly the wrong moment.

---

## Contents

- [The big picture](#the-big-picture)
- [What you get](#what-you-get)
- [Setting it up](#setting-it-up)
  - [1. The server: stemd and stemserve](#1-the-server-stemd-and-stemserve)
  - [2. The Android app](#2-the-android-app)
  - [3. The desktop apps (optional)](#3-the-desktop-apps-optional)
- [Using the Android app](#using-the-android-app)
- [Stem Now: skipping the line](#stem-now-skipping-the-line)
- [The Equalizer](#the-equalizer)
- [Android Auto](#android-auto)
- [The desktop apps](#the-desktop-apps)
- [Separation models](#separation-models)
- [How the pieces find each other](#how-the-pieces-find-each-other)
- [stemserve API](#stemserve-api)
- [Building and testing](#building-and-testing)
- [Repo tour](#repo-tour)
- [Troubleshooting and FAQ](#troubleshooting-and-faq)
- [Credits](#credits)
- [License](#license)

---

## The big picture

There are three moving parts, and they all agree on one simple idea: **the stems folder mirrors
the music folder.** If your song lives at `Kansas/Leftoverture/01 Carry On.flac`, its stems live at
`Stems/Kansas/Leftoverture/01 Carry On/`. That's it. That's the whole trick.

```
   ┌──────────────────────────── your server (unraid, in our case) ───────────────────────────┐
   │                                                                                           │
   │   Music/  ──read──▶  stemd (GPU)  ──writes──▶  Stems/  ──read──▶  stemserve (:8765)  ─────┼──▶ phone, car, desktop
   │      ▲                    ▲                                            │                  │
   │      │                    └──────── "Stem Now" requests ◀──────────────┘                  │
   │   Plex Media Server (:32400) ──────────────────────────────────────────────────────────────┼──▶ library, artwork, playlists
   └───────────────────────────────────────────────────────────────────────────────────────────┘
```

- **stemd** sits on the server and chews through your library on the GPU, one song at a time,
  forever. New album? It notices within half an hour and gets to work.
- **stemserve** hands the finished stems to your devices over HTTP, with seeking, waveforms and
  optional Opus transcodes so your phone storage doesn't cry.
- **Plex** stays in charge of what it's good at: your library, metadata, artwork and playlists.
- **The apps** (Android, Android Auto, and two desktop apps) play everything, and quietly fall back
  to the original file for any song that hasn't been separated yet. Nothing is ever unplayable.

---

## What you get

**On your phone (Android)**
- A Plex music player with per-stem faders, mute and solo, and one-tap mixes: Full, Karaoke,
  A Cappella, No Drums, Rhythm, No Guitar, No Bass.
- A Plexamp-style waveform seek bar that doubles as a volume control (drag sideways to seek, up and
  down for volume). The waveform is scaled to each song, so you can actually see the quiet verse
  and the big chorus.
- Live level meters for every stem, updated every frame and in sync with what you hear.
- A 10-band graphic EQ **plus** Equalizer APO-style parametric filters, with per-device profiles
  that switch automatically when you plug in headphones or connect to Bluetooth.
- Paste in an AutoEQ correction for your headphones and you're done.
- Downloads for offline listening, stems and all.
- Gapless playback, a proper queue, playlists (create, reorder, rename), search, and "On This
  Day" album birthdays on the home screen.
- "Stem Now": jump any song, album, artist or playlist to the front of the separation queue.
- An iOS-flavoured look in Black, Dark or White, at whatever refresh rate your panel can manage.

**In your car (Android Auto)**
- A browse tree built for a rotary knob and no touchscreen, with voice search, a Vocals on/off
  button and a Mix button right next to play/pause.

**On your desktop (Linux)**
- **Stemify (the separator)**: drag a song onto the window, get stems out the other side, preview them in sync,
  export them. Material layout meets Windows 7 Aero glass, running at 240 Hz because why not.
- **Stemify Player**: your Plex library and its stems, with full mixing... dressed up as
  Microsoft Excel 2016. Yes, really. Your boss will think you're working on the quarterly numbers.

**On the server**
- **stemd** separates everything automatically with BS-RoFormer SW (6 stems) on an NVIDIA GPU,
  politely, at low priority, sharing the card with Plex transcodes.
- **stemserve** is one Python file using only the standard library. ffmpeg is optional.

---

## Setting it up

The recommended order is server first, then phone, then (if you like) desktop. The examples below
use unraid paths and the addresses from the original setup (`192.168.86.2`). Swap in your own.

### 1. The server: stemd and stemserve

You need Docker, and for stemd an NVIDIA GPU (on unraid, install the **Nvidia-Driver** plugin from
the Apps tab). A CPU-only build exists for testing, but separating a whole library on a CPU is a
good way to celebrate several birthdays while you wait.

**Folders first** (owned by `99:100`, unraid's nobody:users):

```sh
mkdir -p /mnt/user/appdata/stemserve/cache \
         /mnt/user/appdata/stemd/models \
         /mnt/user/appdata/stemd/state/queue
chown -R 99:100 /mnt/user/appdata/stemserve /mnt/user/appdata/stemd
```

**stemd** builds from the repo root because it borrows the `stemify` package. Copy the repo (or
at least `stemify/`, `server/` and `.dockerignore`) to `/mnt/user/appdata/stemd/src`, then:

```sh
docker build -f /mnt/user/appdata/stemd/src/server/stemd.Dockerfile -t stemd /mnt/user/appdata/stemd/src
docker run -d --name stemd --restart unless-stopped --stop-timeout 60 \
  --runtime=nvidia -e NVIDIA_VISIBLE_DEVICES=all -e NVIDIA_DRIVER_CAPABILITIES=compute,utility \
  -v "/mnt/user/Music/Actual music/Flacs":/music:ro \
  -v /mnt/user/Music/Stems:/stems \
  -v /mnt/user/appdata/stemd/models:/models \
  -v /mnt/user/appdata/stemd/state:/state \
  stemd
docker logs -f stemd
```

The first song downloads the model (about 700 MB). After that the log reads like a very calm
sports commentary: how many tracks are left, which one it's on, and how many times faster than
realtime it went.

**stemserve** is tiny. Copy the `server/` folder to `/mnt/user/appdata/stemserve` and either use
the included compose file:

```sh
cd /mnt/user/appdata/stemserve && docker compose up -d --build
```

or run it by hand:

```sh
docker build -t stemserve /mnt/user/appdata/stemserve
docker run -d --name stemserve --restart unless-stopped -p 8765:8765 \
  -v /mnt/user/Music/Stems:/stems:ro \
  -v /mnt/user/appdata/stemserve/cache:/cache \
  -v /mnt/user/appdata/stemd/state/queue:/queue \
  stemserve
```

Check it from any machine on your network:

```sh
curl http://192.168.86.2:8765/api/info
```

If you see a happy little JSON blob with a track count, you're in business.

<details>
<summary><b>Server settings (environment variables)</b></summary>

**stemd**

| Variable | Default | What it does |
|---|---|---|
| `MUSIC_DIR` | `/music` | Your music library (read-only is fine) |
| `STEMS_DIR` | `/stems` | Where stems go |
| `STATE_DIR` | `/state` | The failed-track list lives here |
| `QUEUE_DIR` | `STATE_DIR/queue` | Where "Stem Now" requests arrive |
| `MODELS_DIR` | `/models` | Model weights, downloaded on first use |
| `MODEL` | `roformer_bs_sw` | Any model id from [the table below](#separation-models) |
| `DEVICE` | `cuda` | `cuda`, `cuda:N` or `cpu` |
| `RESCAN_MIN` | `30` | Minutes between library scans once it's caught up |
| `FP16` | `1` | Half precision on the GPU (`0` for full fp32) |
| `OVERLAP`, `SEGMENT`, `SHIFTS` | `0.25`, `0`, `0` | Separation quality knobs, same as the desktop app |

`docker build --build-arg TORCH_INDEX=cpu ...` makes a small GPU-less image for testing.

**stemserve**

| Variable | Default | What it does |
|---|---|---|
| `STEMS_DIR` | `/stems` | The stems folder |
| `CACHE_DIR` | `/cache` | Opus transcodes and waveforms (safe to delete any time) |
| `QUEUE_DIR` | `/queue` | stemd's "Stem Now" folder, shared with it |
| `PORT` | `8765` | Listen port |
| `TOKEN` | empty | If set, every request needs `X-Stem-Token` or `?token=` |
| `INDEX_TTL` | `30` | Seconds between rescans of the stems folder |

</details>

**Updating later:** copy the new files over, rebuild the image, `docker rm -f` the container, and
run it again. Your stems, models and settings live in the mounted folders, so nothing is lost.
Update stemd before stemserve: if you separated songs before the project was called Stemify,
stemd renames their old done-markers on its first start so nothing gets separated twice.

### 2. The Android app

Built for (and lovingly tested on) a OnePlus 15, but any recent Android phone should be happy.

1. Grab `Stemify.apk` from the Releases page, or [build it yourself](#building-and-testing),
   then sideload it:
   ```sh
   adb install -r Stemify.apk
   ```
2. Open it and go to **Library → Connect** (or Settings → Plex). Enter the 4-character code at
   [plex.tv/link](https://plex.tv/link) and pick your music library.
3. Visit **Settings → Stem Server** and point it at your stemserve. It should say "Online" with a
   song count. (The default address is `http://192.168.86.2:8765`, which is almost certainly not
   yours.)

The app checks stemserve for freshly separated songs every minute, so your library slowly "lights
up" as stemd works through it.

### 3. The desktop apps (optional)

Linux, Python 3.12 and [uv](https://docs.astral.sh/uv/). The default setup uses PyTorch for ROCm
(AMD GPUs, developed on an RX 9070 XT). NVIDIA and CPU work too if you point the torch index
elsewhere in `pyproject.toml`.

```sh
uv sync
uv run stemify                # the drag-and-drop separator
uv run stemify-player         # the Excel-shaped music player
./scripts/install-desktop.sh # adds both to your app grid ("--uninstall" removes them)
```

---

## Using the Android app

**Tabs:** Library, Playlists, Search, Downloads and Settings.

**Stems are the norm, so the exceptions are what's marked.** Songs without stems yet say
**NO STEMS**, and albums and artists say **NO STEMS** or something like **7/10 STEMS** until
they're done. Those songs still play, just from the original file.

**Hold anything** (a song, album, artist or playlist) for:

- **Add to Front of Queue** / **Add to End of Queue**
- **Download** (or **Remove Download**)
- **Add to Playlist** (playlists can even go into other playlists)
- **Stem Now**, while any of it lacks stems

**The Now Playing screen** has the waveform seek bar, the mixer (faders, mute, solo, mix presets,
meters, master volume) and the queue. Your mix can carry over from song to song or reset each
time, your choice.

**Downloads** are proper offline copies: every stem fully fetched (or the original file for songs
without stems). Sort by download order, artist or release date.

**Stem Quality** decides how stems travel from the server: lossless FLAC, or Opus at a bitrate of
your choosing, which takes roughly an eighth of the space and is transcoded and cached on the
server.

**Streaming** needs your home network (Plex and stemserve are plain HTTP on the LAN). Anything
you've downloaded or already streamed plays anywhere.

---

## Stem Now: skipping the line

stemd works through your library on its own, but a big library takes days. If you're desperate to
karaoke your way through an album tonight, hold it and choose **Stem Now**.

Here's what happens:

1. The app sends the songs that still lack stems to stemserve.
2. stemserve drops a little request file into stemd's queue folder.
3. stemd finishes the song it's on, then does yours next, ahead of everything else. If it was
   idle, it starts within about 5 seconds.
4. About a minute after each song finishes, the app notices and plays it with stems from then on.

Requests are handled oldest first and survive restarts. stemd matches the paths Plex reports to
files in its own music folder by their artist/album/file tail, so it doesn't matter that Plex and
stemd see the library under different mount points. Songs that failed before (corrupt files, or
anything over 40 minutes) are skipped; delete `state/failed.json` to give them all another shot.

---

## The Equalizer

Find it in **Settings → Equalizer**. It's two equalizers wearing one trench coat.

**The graphic EQ** has ten octave bands from 31 Hz to 16 kHz, a response curve, and a pile of
presets borrowed in spirit from iOS (Bass Booster, Late Night, Vocal Booster and friends).

**Parametric Filters** are modelled on [Equalizer APO](https://sourceforge.net/projects/equalizerapo/):

| Type | What it's for |
|---|---|
| Peak | Boost or cut around one frequency |
| Low Shelf / High Shelf | Tilt everything below or above a frequency |
| Low Pass / High Pass | Roll off the top or bottom, at 6, 12, 24, 36 or 48 dB per octave |
| Band Pass | Keep only a band |
| Notch | Surgically remove one frequency (hello, 60 Hz hum) |
| All Pass | Shift phase without changing level, for the true connoisseur |

Every filter has frequency, gain and Q, can apply to **both channels, or just left or right**,
and can be switched off on its own to compare. Drag a filter's handle on its curve (sideways for
frequency, up and down for gain), use the sliders, or tap any value to type it in.

**Headphone corrections in two taps:** copy a `ParametricEQ.txt` from
[AutoEQ](https://github.com/jaakkopasanen/AutoEq) for your headphones, then on the Filters page
choose **••• → Paste APO Config**. You can also **Copy as APO Config** to take your settings to a
PC.

**Profiles** link to output devices: the phone speaker, wired headphones, each Bluetooth headset,
USB DACs. Plug something in and its profile switches on automatically, or pick one by hand.

**Preamp and anti-clipping:** each profile has its own manual preamp, and the anti-clipping switch
adds just enough extra cut that a full-scale signal at the curve's loudest point still peaks at
0 dBFS. A limiter on the master bus catches anything else, so cranking the bass won't make things
crackle.

---

## Android Auto

Designed for a 2016 Mazda MX-5 with the Mazda Connect retrofit: an 800×480 screen with touch
disabled, driven entirely by the commander knob and the steering wheel buttons. If it works there,
it'll work in your car.

- **Four tabs:** Playlists, Albums, Artists, Downloads (Downloads comes first when you're away
  from home).
- **Shallow and short:** every song list starts with **Shuffle all**, and anything longer than 40
  items splits into A to Z folders, so you never have to twiddle the knob through 400 songs at a
  red light.
- **Now playing:** next to the transport controls you get **Vocals** on/off (instant karaoke),
  **Mix** (cycles through the presets), shuffle and repeat. The current mix shows on the artist
  line, e.g. "Black Sabbath · Karaoke".
- **Voice:** hold the talk button and say "play Paranoid on Stemify". Playlists, artists,
  albums and songs all work.
- **Offline:** away from home, the app quickly notices the server is unreachable and lists only
  songs that will actually play (downloaded or fully cached).

**Setting up the car (once):** Android Auto hides sideloaded apps until you allow them. On the
phone, search Settings for "Android Auto", tap **Version** ten times, then in the ⋮ menu go to
**Developer settings** and tick **Unknown sources**. Stemify then shows up in Auto's app
launcher.

**Testing without a car:** Google's Desktop Head Unit can impersonate the Miata. On the phone,
start the head unit server from Auto's developer settings, then:

```sh
adb forward tcp:5277 tcp:5277
~/Android/Sdk/extras/google/auto/desktop-head-unit -c ~/Android/Sdk/extras/google/auto/config/rotary.ini
```

---

## The desktop apps

### Stemify (the separator)

Drop files onto the window. Each becomes a queue item, separation starts right away with your
last-used model and device, and when it's done you get a lane per stem with a waveform, solo, mute,
gain and export. Export everything, one stem, or your current mix, as WAV, FLAC or MP3.

Other niceties:

- Separation runs in its own worker process, so the UI never stutters, even mid-separation.
- Works on AMD (ROCm), NVIDIA and CPU, and shows free VRAM.
- Results are cached, so flipping back to an old song is instant (with a size limit you can set).
- A dev frame-time overlay, for anyone who wants proof of the 240 fps claims.

### Stemify Player (the spreadsheet)

The desktop sibling of the phone app, disguised as Microsoft Excel 2016. Some highlights from the
translation guide:

| In Excel | In Stemify Player |
|---|---|
| Sheets (Songs, Artists, Albums, Playlists, Stems, Queue) | Your library |
| "Tell me what you want to do" | Search |
| Zoom slider | Master volume |
| Cell Styles | Mix presets (Karaoke is filed under "Bad", A Cappella under "Good") |
| AutoSum | AutoShuffle |
| Undo / Redo | Previous / next song |
| Format task pane | Now playing, with the stems as a column chart you can drag |
| `#GETTING_DATA` | A playlist loading from Plex |

It reports plays to Plex, supports media keys and desktop media controls (MPRIS), and remembers
your queue, position and mix between launches. The full tour is in
[`stemify/player/README.md`](stemify/player/README.md).

---

## Separation models

| Model id | Name | Stems | Notes |
|---|---|---|---|
| `htdemucs` | HT Demucs | 4 | Fast |
| `htdemucs_ft` | HT Demucs FT | 4 | Fine-tuned, about 4x slower |
| `htdemucs_6s` | HT Demucs 6-stem | 6 | Adds guitar and piano |
| `roformer_bs_vocals` | BS-RoFormer Vocals | 2 | Vocals / instrumental (Viperx 1297) |
| `roformer_mel_kim_ft` | Mel-RoFormer Vocals FT | 2 | Cleanest vocals (Kim FT by unwa) |
| `roformer_bs_sw` | BS-RoFormer SW 6-stem | 6 | stemd's default (by jarredou) |

The 4-stem models give vocals, drums, bass and other. The 6-stem ones add guitar and piano.

---

## How the pieces find each other

The stems folder mirrors the music folder, with one folder per song:

```
Music/Kansas/Leftoverture/01 Carry On Wayward Son.flac
Stems/Kansas/Leftoverture/01 Carry On Wayward Son/
    .stemify.json                              ← the "this one's done" marker
    01 Carry On Wayward Son - vocals.flac
    01 Carry On Wayward Son - drums.flac
    01 Carry On Wayward Son - bass.flac
    ...
```

- Stems are written as 24-bit FLAC into a hidden `.partial` folder first, and the marker goes in
  last, so a separation interrupted by a reboot is simply redone next time. No half-baked songs.
- When a folder holds the same song in several formats, the best one wins (FLAC/WAV, then M4A,
  Opus/Ogg, MP3, WMA).
- Plex reports file paths as *its* container sees them, so the apps match them to stems by suffix,
  checking durations along the way, and learn the path prefix from the first confident match. A
  song called "Intro.flac" on one album will never steal another album's intro.

---

## stemserve API

| Request | Returns |
|---|---|
| `GET /api/info` | Track count, whether ffmpeg is available, whether Stem Now is set up |
| `GET /api/index` | Every finished song (with ETag, so unchanged indexes cost one tiny 304) |
| `GET /api/peaks?key=K&n=256` | Per-stem loudness envelopes for waveforms, 0 to 1000 |
| `POST /api/prepare?key=K&fmt=opus&br=256` | Starts Opus transcodes in the background |
| `POST /api/stem` with `{"paths": [...]}` | Stem Now: stemd separates these Plex files next |
| `GET /file/K/<file>.flac[?fmt=opus&br=256]` | The stem itself, with HTTP range support for seeking |

`K` is the song's folder under the stems root, e.g. `Kansas/Leftoverture/01 Carry On Wayward Son`.
Want to run it outside Docker? It's one file:

```sh
STEMS_DIR=/path/to/Stems CACHE_DIR=~/.cache/stemserve python3 server/stemserve.py
```

---

## Building and testing

**Android** needs JDK 21 and the Android SDK (`android/local.properties` points at it):

```sh
cd android
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleRelease     # app/build/outputs/apk/release/
JAVA_HOME=/path/to/jdk-21 ./gradlew testDebugUnitTest   # stem matching, EQ and filter maths, limiter, cache, car tree
```

The cache and index tests stream real stems from a stemserve on `localhost:8765` and skip
themselves when there isn't one. Debug builds include a hook for screenshots and back-gesture
testing (`app/src/debug`); release builds don't.

**Desktop** apps can be checked without opening a window:

```sh
QT_QPA_PLATFORM=offscreen uv run stemify-player --no-mpris --screenshot /tmp/p.png@5 --quit-after 6
```

---

## Repo tour

```
android/                 the Android and Android Auto app (Kotlin, Jetpack Compose, Media3)
  app/src/main/java/com/stemify/mobile/
    plex/                Plex library, search, playlists, artwork, play reporting
    stems/               stemserve client, path matching, which albums have stems
    cache/               sparse block cache for streaming, and downloads
    engine/              decoders, the sample-locked mixer, EQ, limiter, meters
    eq/                  EQ profiles, device switching, APO config import/export
    playback/            queue, audio focus, mix presets, the Media3 session
    auto/                Android Auto browse tree, voice search, offline mode
    ui/                  every screen, hand-built iOS-style components
server/
  stemd.py               the GPU separation daemon
  stemserve.py           the stem file server (standard library only)
  *.Dockerfile, docker-compose.yml
stemify/              the desktop separator (PySide6 + QML) and shared separation code
  worker/                separation backends (Demucs, RoFormer) in a separate process
  player/                Stemify Player, the Excel-shaped one
  library_core.py        the folder layout rules shared with stemd
scripts/install-desktop.sh
specs.txt                the original design doc for the desktop separator
```

---

## Troubleshooting and FAQ

**A song says NO STEMS and I'm impatient.**
Hold it and choose **Stem Now**. It'll be next in line.

**Stem Now says "stemserve needs updating" or "queue folder isn't mounted".**
stemserve is older than the app, or it's missing the `/queue` mount. See
[the server setup](#1-the-server-stemd-and-stemserve). The queue folder needs to be owned by
`99:100` *before* the container first starts, or Docker creates it as root and nobody can write
to it.

**stemd says it's separating on the CPU.**
The container can't see the GPU. Make sure it's running with `--runtime=nvidia` and the NVIDIA
environment variables shown above.

**stemd keeps saying "out of GPU memory".**
Something else (Plex transcoding, a game server, your ambitions) is hogging VRAM. stemd shrugs and
retries that song on the next pass. Nothing is marked failed.

**The Stem Server page says Offline.**
Check the address in Settings → Stem Server, check `curl http://<server>:8765/api/info` from
another machine, and if you set a `TOKEN` on the server, set the same one in the app.

**The app doesn't show up in Android Auto.**
Sideloaded apps need **Unknown sources** turned on in Auto's developer settings. See
[setting up the car](#android-auto).

**Some songs are marked failed in `state/failed.json`.**
Usually corrupt files, or "songs" over 40 minutes (those are almost always DJ mixes or radio rips).
Failed tracks retry automatically when the file changes; delete the file to retry everything.

**Why is the desktop player an Excel spreadsheet?**
We don't talk about it.

---

## Credits

Standing on the shoulders of some very clever people:

- [Demucs](https://github.com/adefossez/demucs) by Alexandre Défossez
- BS-RoFormer and Mel-RoFormer models, including the SW 6-stem model by jarredou, Viperx's 1297,
  and Kim's vocal model fine-tuned by unwa, via
  [python-audio-separator](https://github.com/nomadkaraoke/python-audio-separator)
- [PyTorch](https://pytorch.org/), [PySide6 / Qt](https://www.qt.io/qt-for-python),
  [Jetpack Compose](https://developer.android.com/compose) and
  [Media3](https://developer.android.com/media/media3)
- [Equalizer APO](https://sourceforge.net/projects/equalizerapo/) and
  [AutoEQ](https://github.com/jaakkopasanen/AutoEq), whose formats the EQ speaks
- The Inter, Selawik and Carlito fonts (licences included alongside them)
- [Plex](https://www.plex.tv/), for keeping the library tidy so we didn't have to

Stemify isn't affiliated with Plex, Google, Microsoft or Mazda. It just likes them.

---

## License

[GPL-3.0](LICENSE). Take it apart and put it back together however you like, just like the songs.
