# stemserve

Serves the StemRoller stems folder to the Android app: an index of every separated
song, the stem files with HTTP range support (so the phone can seek and stream), per-stem
waveforms, and optional Opus transcodes so cached songs take ~1/8 the space.

One Python file, standard library only. `ffmpeg` is optional; without it you get FLAC
only and no waveforms.

## Run it on unraid

The stems live at `/mnt/user/Music/Stems` on the server (`//192.168.86.2/Music/Stems`
from the desktop). From an unraid terminal:

```sh
mkdir -p /mnt/user/appdata/stemserve/cache /mnt/user/appdata/stemd/state/queue
chown -R 99:100 /mnt/user/appdata/stemserve /mnt/user/appdata/stemd
cp -r /path/to/stemRoller2/server /mnt/user/appdata/stemserve/src   # or copy over SMB
docker build -t stemserve /mnt/user/appdata/stemserve/src
docker run -d --name stemserve --restart unless-stopped -p 8765:8765 \
  -v /mnt/user/Music/Stems:/stems:ro \
  -v /mnt/user/appdata/stemserve/cache:/cache \
  -v /mnt/user/appdata/stemd/state/queue:/queue \
  stemserve
```

or `docker compose up -d --build` in this folder (Compose Manager plugin).

Check it from any machine on the LAN:

```sh
curl http://192.168.86.2:8765/api/info
```

## Settings (environment)

| Variable    | Default  | Meaning                                                    |
|-------------|----------|------------------------------------------------------------|
| `STEMS_DIR` | `/stems` | The StemRoller library output folder                       |
| `CACHE_DIR` | `/cache` | Opus transcodes and waveforms (safe to delete)             |
| `PORT`      | `8765`   | Listen port                                                |
| `TOKEN`     | empty    | If set, requests need `X-Stem-Token` or `?token=`           |
| `INDEX_TTL` | `30`     | Seconds between rescans of the stems folder                |
| `QUEUE_DIR` | `/queue` | stemd's Stem Now folder (`stemd/state/queue` on unraid)    |

## API

| Request                                           | Returns                                         |
|---------------------------------------------------|-------------------------------------------------|
| `GET /api/info`                                   | track count, whether ffmpeg is available        |
| `GET /api/index`                                  | every finished song (ETag; 304 when unchanged)  |
| `GET /api/peaks?key=K&n=256`                      | RMS per stem in `n` buckets, 0..1000            |
| `POST /api/prepare?key=K&fmt=opus&br=256`         | starts the Opus transcodes in the background    |
| `POST /api/stem` `{"paths": [plex file paths]}`   | Stem Now: stemd separates these next            |
| `GET /file/K/<file>.flac[?fmt=opus&br=256]`       | the stem, with `Range` support                  |

`K` is the song's folder under the stems root, e.g. `1970 - Paranoid/1-02 - Paranoid`.
The app matches Plex tracks to it by path: the stems folder mirrors the music folder.

## Run it anywhere else

```sh
STEMS_DIR=/var/mnt/unraid/Music/Stems CACHE_DIR=~/.cache/stemserve python3 stemserve.py
```

# stemd: separate new music on the server's GPU

`stemd.py` keeps the stems library up to date. It walks the music folder, separates every
track whose stems folder has no `.stemroller.json` yet (BS-RoFormer SW 6-stem by default,
like the desktop app), and writes them in the layout above, so stemserve and the phone pick
them up within `INDEX_TTL`. Once it has caught up it checks for new music every 30 minutes.
Nothing runs on the desktop.

It writes the same layout the desktop app's old library mode did (`stemroller/library_core.py`),
so the ~300 tracks already done on the desktop are skipped, and a track interrupted
mid-way (`docker stop`, a reboot) is redone from scratch next time.

## Run it on unraid (NVIDIA)

Needs the **Nvidia-Driver** plugin (Apps tab). The image builds from the repo root
because it uses the `stemroller` package. Copy the repo, or at least `stemroller/`,
`server/` and `.dockerignore`, to `/mnt/user/appdata/stemd/src`, then from an unraid terminal:

```sh
mkdir -p /mnt/user/appdata/stemd/models /mnt/user/appdata/stemd/state
chown -R 99:100 /mnt/user/appdata/stemd
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

The first track downloads the model (~700 MB) into `models/`. Each scan logs how many
tracks are left, and each track logs its name, how long it took and how many times
realtime that was.

Separation runs one track at a time at low CPU priority, and the model is unloaded between
passes so the VRAM is free while idle. It can share the card with Plex transcoding; if
VRAM runs out mid-track, that track is retried on the next pass rather than marked failed.
Tracks that fail (corrupt files, over 40 minutes) are listed in `state/failed.json` and
retried when the file changes; delete that file to retry them all.

**Stem Now** (hold a song, album, artist or playlist in the app) jumps the queue: stemserve
writes the request into `state/queue/`, and stemd separates those tracks next, after the
one it's on (or within 5 seconds if it's idle). Requests run oldest first, and survive a
restart. stemd matches the Plex paths to files under `/music` by their artist/album/file
tail, so the two containers don't need the same mount points. Tracks in `failed.json` are
skipped here too.

To update after changing the code: copy the files over again, rebuild, then
`docker rm -f stemd` and repeat the `docker run`.

| Variable     | Default          | Meaning                                              |
|--------------|------------------|------------------------------------------------------|
| `MODEL`      | `roformer_bs_sw` | any id from `stemroller/catalog.py`                  |
| `DEVICE`     | `cuda`           | `cuda`, `cuda:N` or `cpu`                            |
| `RESCAN_MIN` | `30`             | minutes between scans once caught up                 |
| `FP16`       | `1`              | half precision on the GPU (`0` for fp32)             |
| `OVERLAP`, `SEGMENT`, `SHIFTS` | `0.25`, `0`, `0` | as in the desktop app's settings     |

`docker build --build-arg TORCH_INDEX=cpu ...` makes a small image without CUDA for testing.
