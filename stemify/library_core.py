"""Library rules for the server daemon (server/stemd.py): which file is a track's source,
where its stems go, and what marks it done. stemserve reads the same layout and marker.

No Qt here, so the server container doesn't need PySide6.

  <src>/Artist/Album/01 Song.flac -> <dst>/Artist/Album/01 Song/01 Song - vocals.flac, ...
"""

import errno
import json
import os
import shutil
import subprocess
import time

import numpy as np
import soundfile as sf

from .catalog import sort_stems

MARKER = ".stemify.json"
BEST_MODEL = "roformer_bs_sw"
MAX_SECONDS = 40 * 60  # longer files are mixes and radio rips
# Lower wins when one folder holds the same track in several formats.
SOURCE_RANK = {".flac": 0, ".wav": 0, ".aif": 0, ".aiff": 0, ".wv": 0, ".ape": 0, ".alac": 0,
               ".m4a": 1, ".opus": 2, ".ogg": 2, ".oga": 2, ".mp3": 3, ".aac": 3, ".wma": 4}
# Errors that mean the destination as a whole is unusable, not just this track.
SHARE_ERRNOS = {errno.ENOSPC, errno.EROFS, errno.EDQUOT, errno.ENOTCONN, errno.EHOSTDOWN,
                errno.EHOSTUNREACH, errno.ETIMEDOUT, errno.EIO, errno.EACCES}


def duration(path):
    if os.path.splitext(path)[1].lower() in (".flac", ".wav", ".aif", ".aiff"):
        try:
            return sf.info(path).duration
        except Exception:
            pass
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=30).stdout
        return float(out.strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def is_share_error(e):
    return isinstance(e, OSError) and e.errno in SHARE_ERRNOS


def iter_tracks(src, dst):
    """Yield (source path, stems folder) for every track under src, best format per name."""
    dst_real = os.path.realpath(dst)
    for root, dirs, files in os.walk(src):
        dirs[:] = sorted((d for d in dirs if not d.startswith(".")
                          and os.path.realpath(os.path.join(root, d)) != dst_real), key=str.casefold)
        best = {}
        for f in files:
            stem, ext = os.path.splitext(f)
            rank = SOURCE_RANK.get(ext.lower())
            if rank is None or f.startswith("."):
                continue
            key = stem.casefold()
            if key not in best or rank < best[key][0]:
                best[key] = (rank, f)
        for _, f in sorted(best.values(), key=lambda v: v[1].casefold()):
            path = os.path.join(root, f)
            yield path, os.path.join(dst, os.path.splitext(os.path.relpath(path, src))[0])


def is_done(dest):
    return os.path.exists(os.path.join(dest, MARKER))


def signature(path):
    """[size, mtime_ns]: a failed track is retried once its source changes."""
    st = os.stat(path)
    return [st.st_size, st.st_mtime_ns]


def write_track(dest, stems, meta):
    """Write stems into dest as 24-bit FLAC, then the marker, so a half-written track is redone.

    stems maps name -> a path to read, or float32 (samples, channels) audio; meta needs
    "samplerate" and is stored in the marker along with the stem list.
    """
    base = os.path.basename(dest)
    parent = os.path.dirname(dest)
    part = os.path.join(parent, f".{base}.partial")
    shutil.rmtree(part, ignore_errors=True)
    os.makedirs(part)
    written = []
    for name in sort_stems(list(stems)):
        data, sr = stems[name], meta["samplerate"]
        if isinstance(data, str):
            data, sr = sf.read(data, dtype="float32", always_2d=True)
        fname = f"{base} - {name}.flac"
        sf.write(os.path.join(part, fname), np.clip(data, -1, 1), sr, format="FLAC", subtype="PCM_24")
        written.append({"name": name, "file": fname})
    meta = dict(meta, stems=written, finished=time.time())
    with open(os.path.join(part, MARKER), "w") as f:
        json.dump(meta, f, indent=2)
    if not os.path.exists(dest):
        os.rename(part, dest)
    else:  # keep whatever else is in there; the marker lands last
        for name in [s["file"] for s in written] + [MARKER]:
            os.replace(os.path.join(part, name), os.path.join(dest, name))
        os.rmdir(part)
