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

Away from home, Plex still works: the address in Settings → Plex is your server's LAN address, and
when it doesn't answer, Stemify finds the server through plex.tv (its public address, then Plex's
relay), like Plex's own apps, as long as Remote Access is on in Plex's settings. The Plex screen's
Connection line says Local, Remote, Plex Relay or Not Reachable. Stems still need the stem server to
be reachable.

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

## Moving to a new computer

**Settings → Transfer to a New Computer** saves a backup file and restores one, so a new install
picks up where this one is. **Save as a File…** writes a `.json` with your settings (Plex and
stem server sign-ins, Stem Quality, crossfade, theme, folders, volume), your EQ profiles, the
queue with its stem mix, and the list of what's downloaded. The music itself isn't in it. On the
new computer, **Restore from a Backup…** reads the file, tells you what's in it, and applies it
right away, no restart: settings and EQ replace that computer's, the queue and mix are taken
(only the mix, if something is playing), and the downloads start fetching from your server.
Songs already downloaded are left alone.

The file holds your sign-ins, so keep it to yourself. It doesn't carry Stemify Connect trust (a
new computer is approved on its own). Backups only go between computers: the phone app has the
same screen, but its file won't restore here, and this one won't restore there.

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
- **Download Queue** shows what's being fetched, what's next and what failed, with the overall
  percentage, the speed and how much has come down. Open it from the banner at the top of
  Downloads (it appears whenever something is queued or has failed) or from Storage, where the
  row reads "N left" or "Empty". **Retry Failed** puts the failed songs back in line.
- Settings, the queue and the mix live in `~/.config/stemify/mobile.json` and
  `mobile-queue.json`; EQ profiles in `~/.local/share/stemify/mobile/eq.json`.
- Plays are reported to Plex, and media keys and desktop media controls work through MPRIS.

## Checking it without a window

```sh
QT_QPA_PLATFORM=offscreen stemify-mobile --no-mpris --screenshot /tmp/m.png@5 --quit-after 6
```

`--eval 'JS@seconds'` runs JavaScript against the window, e.g. `Ui.push("EqScreen")@2` or
`Ui.openNowPlaying()@2`.
