# Stemify Connect: the nearby control protocol

Every Stemify player (Android, Stemify Mobile, Stemify Player) is a **receiver**: other devices
nearby can find it and drive it. They are all **controllers** too: pick a device in Now Playing
(Stemify Player: **Play On** at the right of the ribbon tabs, or **Data → Play On**) and the app's
own screens (transport, seek bar, mixer, queue, EQ, crossfade, and every Play button in the
library) act on that device instead of this one. Stemify Player has no EQ screen, so it leaves
the other device's EQ alone, and its spectrum chart stays local (spectra aren't on the wire).

## Transports

| | Wi-Fi (same network) | Bluetooth (paired devices) |
|---|---|---|
| Finding devices | UDP broadcast beacon on port **47809** | The OS's paired-device list, probed for the service |
| Talking | TCP on 47810 (or 47811, 47812, then any free port), announced in the beacon | RFCOMM, service UUID `5f3a7c2e-8d1b-4b6a-9c0e-57656d696679` |
| Trust | Approved once on the receiver ("Allow Pixel to control this?"), then remembered | Pairing already proved it; no prompt |

Both carry the same stream: **UTF-8 JSON, one object per line**, `"t"` naming the message.

### Beacon (UDP 47809, broadcast)

```json
{"stemify": 1, "id": "<install id>", "name": "Pixel 10", "app": "android", "port": 40123}
```

Receivers send it every 3 s and right after hearing a probe. Controllers send
`{"stemify": 1, "probe": true}` when they start looking, and forget a device 10 s after its
last beacon. `app` is `android`, `mobile` (Stemify Mobile) or `player` (Stemify Player).

## Session

```
controller                                  receiver
  hello {v, id, name, app, probe?}    →
                                       ←    welcome {v, id, name, app, caps}
                                            (or pending … welcome / denied)
                                       ←    state, queue, wave, eq   (full snapshot)
  cmd {seq, op, …}                    →
                                       ←    state {ack: seq, …}       (as things change)
  meters {on}                         →
                                       ←    levels {v} at ~20 Hz while on and playing
  ping                                →
                                       ←    pong
```

- `probe: true` asks only for `welcome` (or `pending`) and then the receiver closes. It's how
  Bluetooth devices are checked for the service without starting a session.
- `caps` lists optional features: `eq`, `crossfade`, `keepMix`.
- Either side may close at any time; `bye {reason}` says why first when it can.
- Controllers ping every 5 s; either side gives up after 20 s of silence.

### Acks and optimistic updates

Every `cmd` carries an increasing `seq`. Every `state`, `queue` and `eq` the receiver sends
afterwards carries `ack`: the last `seq` it has applied. Controllers apply their own edits at
once (a fader follows the finger) and ignore snapshots whose `ack` is older than their last
edit of that kind, so a slow echo can't yank a slider back mid-drag.

## Receiver → controller

**state** (on any change, at most every 60 ms, and every 2 s while playing to correct drift):

```json
{"t": "state", "ack": 12, "index": 3, "track": {…}, "playing": true, "buffering": false,
 "positionMs": 81234, "durationMs": 241000, "shuffle": false, "repeat": "off",
 "source": "stems", "stems": ["vocals", "drums", "bass", "other"],
 "mix": {"vocals": {"level": 0.8, "muted": true, "solo": false}},
 "master": 0.85, "keepMix": true, "format": "OPUS 256",
 "crossfade": {"mode": "off", "seconds": 6, "albums": false}}
```

Crossfade `mode` is `off`, `short`, `medium`, `long` (the three Smart lengths) or `fixed`.

`playing` means "wants to play" (it stays true while buffering). Controllers advance the
position themselves between snapshots while `playing && !buffering`. `repeat` is `off`, `all`
or `one`; `source` is `none`, `stems` or `original`. A stem missing from `mix` is at unity
(`level` 0.8, the fader position for 0 dB) and neither muted nor soloed.

**queue** (when it changes): a window of the queue around the current song.

```json
{"t": "queue", "ack": 12, "offset": 0, "total": 120, "index": 3,
 "items": [{"uid": 98213, "track": {…}}, …]}
```

`items[i]` is queue position `offset + i`; `index` is absolute. `uid` is stable while the item
stays in the queue (it keys list animations). The window is at most 50 songs back and 1000 ahead.

**wave**: the seek bar's waveform for the current song: per-stem loudness envelopes (0 to 1)
from stemserve, or Plex's loudness in dB for songs without stems.

```json
{"t": "wave", "key": "12345", "stems": {"vocals": [0.1, …], …}, "db": null}
```

**levels**: `{"t": "levels", "v": [0.42, 0.9, …]}`, one peak (0 to 1, after the faders) per
stem in `state.stems` order.

**eq** (receivers with `caps: eq`): the whole EQ, in the desktop's format, and the output it's on.

```json
{"t": "eq", "ack": 12, "device": {"id": "bluetooth|bluez_output.AC_80_0A", "kind": "bluetooth", "label": "WH-1000XM5"},
 "state": {"enabled": true, "automatic": true, "manualId": null, "defaultId": "flat", "antiClip": true,
           "profiles": [{"id": "flat", "name": "Flat", "gains": [0, …ten bands…], "devices": ["speaker|"],
                         "filters": [{"id": "…", "type": "peak", "freq": 1000, "gain": -3, "q": 1.41,
                                      "slope": 12, "channel": "both", "on": true}], "preamp": 0}],
           "seenDevices": [{"id": "speaker|", "kind": "speaker", "label": "Speakers"}]}}
```

Device ids are opaque to everyone but the device that made them: Android writes
`android:<AudioDeviceInfo type>|<name>`. Filter `type` is `peak`, `lowshelf`, `highshelf`,
`lowpass`, `highpass`, `bandpass`, `notch` or `allpass`; `channel` is `both`, `left` or `right`.

**toast**: `{"t": "toast", "text": "Couldn't load this song"}`, the receiver's own warnings.

## Controller → receiver: `cmd` ops

| op | arguments | |
|---|---|---|
| `play`, `pause`, `toggle`, `next`, `previous` | | |
| `seek` | `ms` | |
| `skipBy` | `ms` | negative goes back; past the end is the next song |
| `skipTo` | `i` | absolute queue position |
| `playTracks` | `tracks`, `start`, `shuffle`, `positionMs?`, `mix?`, `paused?` | replaces the queue; `start` -1 with `shuffle` picks a random first song. `positionMs`, `mix` and `paused` move playback from one device to another |
| `playNext`, `addToQueue` | `tracks` | |
| `removeAt` | `i` | |
| `move` | `from`, `to` | absolute positions |
| `clearUpNext` | | everything after the current song |
| `toggleShuffle`, `cycleRepeat` | | |
| `setRepeat` | `mode` | |
| `setLevel` | `stem`, `level` | fader position 0 to 1 |
| `toggleMute`, `toggleSolo` | `stem` | |
| `setMix` | `mix` | the whole mix at once (presets, the Vocals switch) |
| `setMaster` | `v` | master volume 0 to 1 |
| `setKeepMix` | `on` | |
| `setCrossfade` | `mode`, `seconds`, `albums` | |
| `eqState` | `state` | the whole EQ, as in `eq`; the receiver keeps its own output |

### Tracks

Tracks travel as Plex tracks, in the Android app's field names. Every device reads the same
Plex server, so `thumb` and the keys mean the same thing everywhere.

```json
{"ratingKey": "12345", "title": "Carry On Wayward Son", "artist": "Kansas", "album": "Leftoverture",
 "albumKey": "123", "artistKey": "12", "index": 1, "disc": 1, "durationMs": 323000,
 "thumb": "/library/metadata/123/thumb/1700000000", "partKey": "/library/parts/…", "partId": 4567,
 "partFile": "/music/Kansas/Leftoverture/01 Carry On Wayward Son.flac", "partSize": 41234567,
 "codec": "flac", "year": 1976, "playlistItemId": null, "stemKey": null}
```

Empty and null fields may be left out. A receiver that needs a field a sender didn't have
(Android wants `partId` and `partSize`) looks the track up in Plex by `ratingKey`.
