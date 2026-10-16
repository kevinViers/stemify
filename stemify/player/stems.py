"""stemserve's index, matched to Plex tracks by path (a port of the phone app's StemIndex)."""

import json
import os
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

# Display order and colours, matching the desktop separator and the phone.
ORDER = ["vocals", "drums", "bass", "guitar", "piano", "other", "instrumental", "mix"]
COLORS = {
    "vocals": "#FF7AB6", "drums": "#FFB547", "bass": "#7C8CFF", "other": "#4CD07D",
    "guitar": "#FF8A5C", "piano": "#B48CFF", "instrumental": "#3DD6C6", "mix": "#8E8E93",
}


def sort_names(names):
    return sorted(names, key=lambda n: ORDER.index(n) if n in ORDER else 99)


def norm(path: str) -> str:
    """NFC, forward slashes, no extension, lower case."""
    p = unicodedata.normalize("NFC", path).replace("\\", "/")
    slash, dot = p.rfind("/"), p.rfind(".")
    if dot > slash + 1:
        p = p[:dot]
    return p.lower()


class StemIndex:
    """Maps Plex file paths to stem folders.

    The stems folder mirrors the music folder, so the source path relative to the music root
    identifies a track. Plex reports paths as its container sees them, so the root is unknown
    up front: every suffix of the Plex path is tried, longest first. A bare file name, or a
    suffix whose duration disagrees, only counts under a root learned from a longer match.
    """

    def __init__(self, entries=(), roots=()):
        self.entries = list(entries)
        self.by_source = {}
        self.by_key = {}
        for e in self.entries:
            self.by_source.setdefault(norm(e["source"]), e)
            self.by_key[e["key"]] = e
        self.roots = list(dict.fromkeys(roots))

    def __len__(self):
        return len(self.entries)

    def find(self, plex_path, duration_ms=0):
        if not plex_path or not self.entries:
            return None
        path = norm(plex_path)
        for root in self.roots:
            if path.startswith(root):
                hit = self.by_source.get(path[len(root):])
                if hit:
                    return hit
        parts = path.split("/")
        for i in range(1, len(parts)):
            hit = self.by_source.get("/".join(parts[i:]))
            if hit is None:
                continue
            if len(parts) - i >= 2 and self._duration_ok(hit, duration_ms):
                self.roots.append("/".join(parts[:i]) + "/")
                return hit
        return None

    @staticmethod
    def _duration_ok(e, duration_ms):
        d = e.get("duration")
        return d is None or duration_ms <= 0 or abs(d * 1000 - duration_ms) < 3000


class StemServer:
    """Client for server/stemserve.py. Files come from the local mount when it's there."""

    def __init__(self, config, cache_dir):
        self.cfg = config
        self.cache_dir = cache_dir
        self.index = StemIndex(roots=config.get("stemRoots", []))
        self.online = None  # not checked yet
        self._index_file = os.path.join(cache_dir, "stem_index.json")
        self._etag_file = os.path.join(cache_dir, "stem_index.etag")
        try:
            with open(self._index_file) as f:
                self.index = StemIndex(json.load(f).get("tracks", []), self.index.roots)
        except (OSError, ValueError):
            pass

    @property
    def base(self):
        return self.cfg["stemUrl"].rstrip("/")

    def _open(self, url, headers=None, timeout=15):
        h = dict(headers or {})
        if self.cfg.get("stemToken"):
            h["X-Stem-Token"] = self.cfg["stemToken"]
        return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)

    def info(self):
        """/api/info: song count and whether it can transcode to Opus (ffmpeg)."""
        with self._open(self.base + "/api/info", timeout=6) as r:
            return json.loads(r.read())

    def stem_now(self, paths):
        """Stem Now: stemd separates these Plex files before the rest of the library."""
        req = urllib.request.Request(self.base + "/api/stem", data=json.dumps({"paths": paths}).encode(), method="POST",
                                     headers={"Content-Type": "application/json",
                                              **({"X-Stem-Token": self.cfg["stemToken"]} if self.cfg.get("stemToken") else {})})
        try:
            urllib.request.urlopen(req, timeout=15).close()
        except urllib.error.HTTPError as e:
            try:
                msg = json.loads(e.read()).get("error")
            except ValueError:
                msg = None
            raise OSError("stemserve needs updating for Stem Now" if e.code == 404 else msg or f"stemserve answered {e.code}") from e

    def prepare(self, entry, br):
        """Starts the Opus transcodes on the server so they're ready when asked for."""
        url = f"{self.base}/api/prepare?" + urllib.parse.urlencode({"key": entry["key"], "fmt": "opus", "br": br})
        h = {"X-Stem-Token": self.cfg["stemToken"]} if self.cfg.get("stemToken") else {}
        try:
            urllib.request.urlopen(urllib.request.Request(url, data=b"", method="POST", headers=h), timeout=10).close()
        except (urllib.error.URLError, TimeoutError, OSError):
            pass

    def refresh(self):
        """Fetches the index (ETag-cached). Returns True if it changed."""
        headers = {}
        if os.path.exists(self._etag_file) and os.path.exists(self._index_file):
            with open(self._etag_file) as f:
                headers["If-None-Match"] = f.read().strip()
        try:
            with self._open(self.base + "/api/index", headers, timeout=60) as r:
                body = r.read()
                etag = r.headers.get("ETag")
        except urllib.error.HTTPError as e:
            self.online = e.code == 304
            return False
        except (urllib.error.URLError, TimeoutError, OSError):
            self.online = False
            return False
        self.online = True
        data = json.loads(body)
        self.index = StemIndex(data.get("tracks", []), self.index.roots)
        os.makedirs(self.cache_dir, exist_ok=True)
        with open(self._index_file, "wb") as f:
            f.write(body)
        if etag:
            with open(self._etag_file, "w") as f:
                f.write(etag)
        return True

    def find(self, track):
        hit = self.index.find(track.get("partFile"), track.get("durationMs", 0))
        if self.index.roots != self.cfg.get("stemRoots"):
            self.cfg["stemRoots"] = list(self.index.roots)
        return hit

    def local_path(self, entry, stem):
        root = self.cfg.get("stemsDir", "")
        p = os.path.join(root, entry["key"], stem["file"]) if root else ""
        return p if p and os.path.exists(p) else None

    def file_url(self, entry, stem, opus_kbps=0):
        path = "/".join(urllib.parse.quote(p) for p in entry["key"].split("/"))
        url = f"{self.base}/file/{path}/{urllib.parse.quote(stem['file'])}"
        return url + f"?fmt=opus&br={opus_kbps}" if opus_kbps else url

    def peaks(self, entry, buckets):
        """Per-stem RMS envelopes (0..1), or None."""
        url = f"{self.base}/api/peaks?" + urllib.parse.urlencode({"key": entry["key"], "n": buckets})
        try:
            with self._open(url, timeout=30) as r:
                stems = json.loads(r.read()).get("stems", {})
            return {name: [v / 1000 for v in arr] for name, arr in stems.items()}
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            return None
