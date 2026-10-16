# Stemify Player

The desktop counterpart to the phone app: your Plex library and its stems, with per-stem
faders, mute/solo, mix presets and a master volume. It is dressed as Microsoft Excel 2016, in
the Black Office theme (File → Options → Office Theme switches to Colorful).

```sh
stemify-player            # or "Stemify Player" in the app grid (scripts/install-desktop.sh)
```

## First run

**Data → Connect to Plex** (or click **Sign in** on the title bar), then enter the 4-character
code at plex.tv/link. It picks the music library at `…/Flacs`. Until then the workbook is built
from the stem server's index alone (artist / album / song from the stems folders), so every
separated song is already playable.

## Where things are

| Excel | Here |
|---|---|
| Sheets: Songs, Artists, Albums, Playlists, Stems, Queue | The library. Double-click (or Enter) plays a song from that point, or opens an artist, album or playlist as a new sheet. Backspace / middle-click closes it |
| Row 1 filter buttons, Sort & Filter | Sort by any column |
| "Tell me what you want to do" | Search (Ctrl+F). Results open as a sheet |
| Status bar Average / Count / Sum | Select a range of times and it adds them up |
| Zoom slider | Master volume (scroll on it, too). Ctrl+scroll on the grid is the real zoom |
| Home → Cell Styles | Mix presets: Full Mix, Karaoke ("Bad"), A Cappella ("Good"), No Drums, Rhythm, No Guitar, No Bass |
| Home → Cells | Play Next, Add to Queue, Delete Rows (queue or playlist) |
| Insert → Playlists | New Plex playlist from the selection, or add the selection to one |
| AutoSum | AutoShuffle: shuffle the whole sheet |
| Quick Access Toolbar | Play/Pause, Undo = previous song, Redo = next song |
| Home → Playback | Play, previous / next, Back 10s / Ahead 10s, stop, shuffle, repeat |
| Format Mix task pane | Now playing, a sparkline of the song to seek on, and the stems as a column chart: drag a column to set its level, double-click for 0 dB, M / S below. Under it, a live spectrum of each stem after its fader, redrawn every frame (240Hz on a 240Hz panel) |
| Green triangle in a cell | The song that's playing |
| `#GETTING_DATA` | A playlist loading from Plex |
| File → Info / Account / Options | Library and server status, Plex sign-in, server addresses and folders |

Keys: Space play/pause, Ctrl+←/→ previous/next, Ctrl+Shift+←/→ 10 seconds back/ahead, V vocals on/off, arrows/Shift+arrows select,
Ctrl+C copies cells as text, Ctrl+Q quits. The media keys, the desktop's media controls and
KDE Connect on the phone work through MPRIS.

## How it plays

- **Stems** come straight off the `Stems` share (`/var/mnt/unraid/Music/Stems`) when it's
  mounted, else they're downloaded from stemserve once into `~/.cache/stemify/player/files`
  (4 GB, oldest first out). Songs without stems play the original from the music share, or from
  Plex if the share isn't there.
- A decoder thread reads every stem about 1.5 s ahead of the playhead, so songs start at once
  and nothing is decoded whole; the audio callback mixes with click-free gain ramps. The fader
  taper matches the phone (0.8 = 0 dB, top = +3.9 dB).
- Plays are reported to Plex (now playing, and the play count at 90%).
- Album art: Plex's, or the album folder's `cover.*`, or the picture embedded in the file.
- The queue, position, mix and volume come back on the next launch (`~/.config/stemify/`).

## Checking it without a window

```sh
QT_QPA_PLATFORM=offscreen stemify-player --no-mpris --screenshot /tmp/p.png@5 --quit-after 6
```

`--eval 'JS@seconds'` runs JavaScript against the window (e.g. `library.activate(0, 0)@2`).
