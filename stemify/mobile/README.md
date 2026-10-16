# Stemify Mobile

The phone app, on the desktop. Same screens, same look (Black, Dark or White, nine accents,
album-colour Now Playing), same mixer and the same equalizer, in a window you can make as tall
or as wide as you like (album grids grow extra columns).

```sh
stemify-mobile            # or "Stemify Mobile" in the app grid (scripts/install-desktop.sh)
```

## First run

If you've signed in to Stemify Player, Stemify Mobile starts from its Plex account, servers and
folders. Otherwise: **Library → Connect** (or Settings → Plex), enter the 4-character code at
plex.tv/link, and pick your music library. Settings → Stem Server should say "Online".

## What's here

Everything from the phone app (see the main README), with desktop habits added:

| Phone | Here |
|---|---|
| Hold a song, album, artist or playlist | Right-click it (or hold), or its ••• button |
| Swipe back | Esc, Alt+←, or the mouse's back button |
| Drag Now Playing down | Drag its grabber or the art, click the grabber, or Esc |
| Drag the seek bar up and down for volume | That, or scroll on it |
| | Space play/pause, ← / → 10 seconds back / ahead, Ctrl+← / → previous / next, Ctrl+P Now Playing, Ctrl+F search |
| Phone speaker, headphones, Bluetooth | Whatever PipeWire plays to: speakers, headphones, Bluetooth, USB, HDMI, each with its own EQ profile |
| Download on Wi-Fi only | Music & Stems Folders: the mounted shares, played straight off the disk |

The Now Playing transport has **10-second skip buttons** outside previous and next, as on the
phone.

## How it plays

The audio comes from Stemify Player's engine (`stemify/player/engine.py`): a decoder thread
reads every stem ahead of the playhead, and the audio callback mixes them, then runs the
equalizer, the master volume and a peak limiter. The EQ maths is a port of the phone's
(`eq.py`; checked against `docs/eq/make_figures.py`), so a profile, or an AutoEQ
`ParametricEQ.txt` pasted into it, sounds the same on both.

- **Stems** play straight off the mounted Stems share when it's there. Otherwise they come from
  stemserve at your **Stem Quality** (FLAC, or Opus made on the server) into a streaming cache
  in `~/.cache/stemify/mobile/files`, trimmed to the limit set in Storage.
- **Downloads** keep every stem (or the original) plus its waveform in
  `~/.local/share/stemify/mobile/downloads`, and play first.
- Settings, the queue and the mix live in `~/.config/stemify/mobile.json` and
  `mobile-queue.json`; EQ profiles in `~/.local/share/stemify/mobile/eq.json`.
- Plays are reported to Plex, and media keys and desktop media controls work through MPRIS.

## Checking it without a window

```sh
QT_QPA_PLATFORM=offscreen stemify-mobile --no-mpris --screenshot /tmp/m.png@5 --quit-after 6
```

`--eval 'JS@seconds'` runs JavaScript against the window, e.g. `Ui.push("EqScreen")@2` or
`Ui.openNowPlaying()@2`.
