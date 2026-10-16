# Stemify for Android

A Plex music player for the OnePlus 15 that plays the stems stemd separates on your
unraid server: per-stem faders, mute/solo, karaoke-style presets, a master volume drawn
over a Plexamp-style waveform seek bar, a 10-band graphic EQ plus Equalizer APO-style
parametric filters with per-device profiles, and an
iOS look in Black, Dark or White (flat colours, no gradients).

## Install

`../Stemify.apk` is a release build signed with the debug key, ready to sideload:

```sh
adb install -r ../Stemify.apk
```

## First run

1. Start stemserve on the unraid box (`../server/README.md`). It serves
   `/mnt/user/Music/Stems` on port 8765.
2. In the app: **Library → Connect** (or Settings → Plex). Enter the 4-character code at
   plex.tv/link, then pick the **Music** library (`/media/Music/Actual music/Flacs`).
3. Settings → Stem Server should say "Online" with a song count. The default address is
   `http://192.168.86.2:8765`.

Stems are the norm: stemd on the server separates the whole library on its own, and the app
checks stemserve for new stems every minute. So the exceptions are what's marked: songs show
**NO STEMS**, and albums and artists show **NO STEMS** or **7/10 STEMS** until they're done. Those
songs play the original file from Plex, so the whole library works in the meantime.

Hold any song, album, artist or playlist for: **Add to Front of Queue**, **Add to End of Queue**,
**Download** (or Remove Download), **Add to Playlist** (a playlist can go into another one) and,
while any of it lacks stems, **Stem Now**: stemd on the server separates those songs next, ahead
of the rest of the library, and they switch to stems within a minute or so of finishing.

The tabs are Library, Playlists, Search, Downloads and Settings. Library's home shows **On This
Day**: albums released on today's date in earlier years (or, on a quiet day, the few days either
side). **Downloads** lists albums, sorted by download order, artist or release date. The
**Equalizer** is in Settings.

The Equalizer has the 10 graphic bands plus **Parametric Filters**, as in Equalizer APO: peak,
low/high shelf, low/high pass (6–48 dB/oct), band pass, notch and all pass, each with
frequency, gain and Q, on both channels or just left or right, and switchable one by one.
Drag a filter's handle on its curve, use the sliders, or tap a value to type it. Each
profile has its own manual preamp; anti-clipping adds whatever else the curve needs. The
Filters page's ••• menu pastes an Equalizer APO config from the clipboard (AutoEQ's
`ParametricEQ.txt` for your headphones works as-is) and copies the profile out as one.

The seek bar's waveform is scaled to each song's own range (its loud moments fill the bar,
its quiet parts sit low), and the mixer's level meters follow what's audible at display rate.
Either side of the previous and next buttons, Now Playing has 10-second skip back and ahead.

## How it fits together

| Piece | What it does |
|---|---|
| `plex/` | Library, search, playlists (create/add/remove/reorder/rename), artwork, play reporting |
| `stems/` | stemserve's index; matches Plex file paths to stem folders by suffix + duration |
| `cache/` | Sparse block cache: streams with HTTP ranges, seeks anywhere, keeps what it fetched; downloads are pinned copies |
| `engine/` | One decoder thread per stem → sample-locked mixer → EQ (auto anti-clip preamp) → master → limiter → one AudioTrack. Gapless |
| `eq/` | Profiles (graphic bands, parametric filters, preamp), linked to output devices (speaker, wired, each Bluetooth headset, USB DAC); automatic or manual; APO config import/export |
| `playback/` | Queue, shuffle/repeat, audio focus, mix presets; Media3 library session for notification, lock screen, Bluetooth and Android Auto |
| `auto/` | Android Auto: browse tree, voice search, home-network probe, artwork provider |
| `stems/StemCoverage.kt` | Which albums and artists have stems: a slim list of every track's path, matched to the index |
| `ui/` | Compose, hand-built iOS components; per-tab navigation stacks driven by predictive back |

The EQ's anti-clip preamp is the negative of the curve's highest point (evaluated on the
actual biquads), so a full-scale signal at the boosted frequency still peaks at 0 dBFS.

## Android Auto

Built for the 2016 MX-5's Mazda Connect retrofit: an 800×480 screen with touch disabled,
driven by the commander knob (turn, nudge, press, Back) and the steering-wheel buttons.
Auto draws a media app's screens itself; the app supplies a browse tree and buttons, so
that's what is shaped for the knob (`auto/`, `playback/PlaybackService.kt`):

- **Four tabs** (all Auto shows): Playlists, Albums, Artists, Downloads. Away from home
  Downloads comes first.
- **Shallow and short.** Every list of songs starts with **Shuffle all**; lists longer than
  40 split into A–Z folders (big letter tiles), so the knob never has to crawl through
  hundreds of rows. Albums are a grid (the art identifies them); everything else is a list.
  Songs with stems say **Stems** under the title.
- **Now playing:** previous / play / next stay put (and the wheel's seek buttons work).
  Beside them, in priority order: **Vocals** on/off (karaoke), **Mix** (cycles Full →
  Karaoke → A Cappella → No Drums → Rhythm → No Guitar → No Bass), then shuffle and repeat;
  whatever doesn't fit beside the transport goes behind Auto's overflow button (on the
  Miata's 800×480 all four fit). The current mix
  shows on the artist line ("Black Sabbath · Karaoke"), since a button press gets no other
  feedback. Songs without stems just get shuffle and repeat, and say "No stems" in the lists. The same buttons appear on the
  phone's lock-screen controls.
- **Voice:** hold the wheel's talk button: "play Paranoid on Stemify" (a playlist,
  artist, album or song; artists shuffle). There's no keyboard while driving, so Auto's
  search button is voice too.
- **Offline:** in the car the phone is on mobile data and Plex/stemserve are LAN-only. The
  app probes them (1.5 s, re-checked on network changes) and when they're unreachable reads
  library pages from cache only and lists **only songs that will play** (downloaded, or
  fully cached); downloaded songs carry Auto's downloaded mark. Art comes from a local
  `content://` provider fed by what downloads already cached.

### Setting up the car (once)

Auto hides sideloaded apps until you allow them:

1. Phone Settings → search "Android Auto" → scroll to the bottom and tap **Version** ten
   times → OK.
2. ⋮ menu → **Developer settings** → tick **Unknown sources**.
3. Plug into the Miata. Stemify is in Auto's app launcher (the grid of icons).

Download what you want for the drive while on home Wi-Fi.

### Testing without the car

Google's Desktop Head Unit runs Auto on the desktop. `config/rotary.ini` is exactly the
Miata: 800×480, touch off, rotary controller on. On the phone: Auto developer settings ⋮ →
**Start head unit server**; then:

```sh
adb forward tcp:5277 tcp:5277
~/Android/Sdk/extras/google/auto/desktop-head-unit -c ~/Android/Sdk/extras/google/auto/config/rotary.ini
```

(It needs libc++, which this host lacks; here it runs in the `dhu-headless` podman image.)

## Display

The app asks for the panel's fastest mode at its full resolution (1272×2772 at 165 Hz on
the OnePlus 15) and Compose renders at that vsync. The footer of Settings shows the active
refresh rate; turn High Refresh Rate off to let the system choose.

## Build

JDK 21 and the Android SDK (`local.properties` points at `~/Android/Sdk`):

```sh
JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1 ./gradlew assembleRelease   # app/build/outputs/apk/release/
JAVA_HOME=~/.local/opt/jdk-21.0.12.1+1 ./gradlew testDebugUnitTest  # stem matching, EQ/limiter maths, block cache
```

The cache and index tests stream real stems from a stemserve on `localhost:8765` and
are skipped when it isn't running.

Debug builds include a hook for screenshots and back-gesture testing in the emulator
(`app/src/debug`); release builds don't.

## Limits

- Plex can't hold an empty playlist, so new playlists start from a song, the queue or a
  selection.
- Streaming needs the LAN (Plex and stemserve are plain HTTP on 192.168.86.2). Downloads
  and anything already streamed play offline.
