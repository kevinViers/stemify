"""Plex Media Server and plex.tv calls (standard library only; call from worker threads)."""

import contextlib
import json
import platform
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from . import config

PRODUCT = "Stemify Player"
PAGE = 1000
RESOURCES = "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1"
PROBE_SECS = 2.5
RESOLVE_TTL = 600  # look again this often; a failed request looks again at once
REFRESH_SECS = 6 * 3600  # how old plex.tv's list of connections may get
LINKS = {"local": "Local", "remote": "Remote", "relay": "Plex Relay", "none": "Not Reachable"}


class PlexError(Exception):
    pass


class NotSignedIn(PlexError):
    def __init__(self):
        super().__init__("Not signed in to Plex")


def _track(m: dict) -> dict:
    """A Plex track as the player's flat dict."""
    media = (m.get("Media") or [{}])[0]
    part = (media.get("Part") or [{}])[0]
    artist = m.get("originalTitle") or m.get("grandparentTitle") or ""
    return {
        "key": str(m.get("ratingKey", "")),
        # Plex leaves untagged files (old WAV rips) without a title; their file name beats a blank row.
        "title": m.get("title") or _file_title(part.get("file", "")),
        "artist": artist,
        "albumArtist": m.get("grandparentTitle", ""),
        "album": m.get("parentTitle", ""),
        "albumKey": str(m.get("parentRatingKey", "")),
        "artistKey": str(m.get("grandparentRatingKey", "")),
        "year": m.get("parentYear") or m.get("year") or 0,
        "index": m.get("index") or 0,
        "disc": m.get("parentIndex") or 0,
        "durationMs": m.get("duration") or media.get("duration") or part.get("duration") or 0,
        "thumb": m.get("thumb") or m.get("parentThumb") or m.get("grandparentThumb") or "",
        "partFile": part.get("file", ""),
        "partKey": part.get("key", ""),
        "partId": part.get("id") or 0,
        "partSize": part.get("size") or 0,
        "codec": media.get("audioCodec", ""),
        "plays": m.get("viewCount") or 0,
        "addedAt": m.get("addedAt") or 0,
        "playlistItemID": m.get("playlistItemID"),
    }


def _file_title(path: str) -> str:
    name = path.replace("\\", "/").rsplit("/", 1)[-1]
    return name.rsplit(".", 1)[0] if "." in name[1:] else name


def _album(m: dict) -> dict:
    return {
        "key": str(m.get("ratingKey", "")),
        "title": m.get("title", ""),
        "artist": m.get("parentTitle", ""),
        "artistKey": str(m.get("parentRatingKey", "")),
        "year": m.get("year") or 0,
        "tracks": m.get("leafCount") or 0,
        "thumb": m.get("thumb", ""),
        "addedAt": m.get("addedAt") or 0,
        "genre": ", ".join(g.get("tag", "") for g in (m.get("Genre") or [])[:2]),
        "released": (m.get("originallyAvailableAt") or "")[:10],
        # The MusicBrainz release the files are tagged with (listings need includeGuids=1).
        "mbid": next((g["id"][7:] for g in m.get("Guid") or [] if g.get("id", "").startswith("mbid://")), ""),
    }


def _artist(m: dict) -> dict:
    return {
        "key": str(m.get("ratingKey", "")),
        "title": m.get("title", ""),
        "sortTitle": m.get("titleSort") or m.get("title", ""),
        "albums": m.get("childCount") or 0,
        "thumb": m.get("thumb", ""),
        "genre": ", ".join(g.get("tag", "") for g in (m.get("Genre") or [])[:2]),
    }


def _playlist(m: dict) -> dict:
    return {
        "key": str(m.get("ratingKey", "")),
        "title": m.get("title", ""),
        "tracks": m.get("leafCount") or 0,
        "durationMs": m.get("duration") or 0,
        "thumb": m.get("composite") or m.get("thumb", ""),
        "smart": bool(m.get("smart")),
        "updatedAt": m.get("updatedAt") or 0,
    }


class Plex:
    """Plex calls. [base] is the address from settings (the LAN one) and what every URL and cache key
    is built from; requests are sent to the address that works right now, found the way Plex's own apps
    do: plex.tv lists the server's connections (LAN, public, relay) and the first that answers as this
    server wins. Finding it blocks for up to a few seconds, so only worker threads may send requests."""

    def __init__(self, config, product=PRODUCT):
        self.cfg = config
        self.product = product
        self.on_change = None  # called (from any thread) when the connection kind changes
        self._lock = threading.RLock()
        self._live = None  # the address found, no trailing slash
        self._kind = "local"  # until the first look says otherwise
        self._at = 0.0
        self._seen = None  # (plexUrl, token) the answer is for

    @property
    def base(self):
        return self.cfg["plexUrl"].rstrip("/")

    @property
    def live_base(self):
        """Where requests go now, for handing to something that fetches by itself (Qt's Image)."""
        return self._live or self.base

    @property
    def link(self):
        """How Plex is being reached: Local, Remote, Plex Relay or Not Reachable."""
        return LINKS[self._kind]

    def _set_kind(self, kind):
        if kind != self._kind:
            self._kind = kind
            if self.on_change:
                self.on_change()

    # ---- finding the server -----------------------------------------------------------
    def resolve(self, force=False):
        """Looks for the address that works, unless it was checked lately. Returns it, or None."""
        with self._lock:
            key = (self.base, self.token)
            if self._seen != key:
                # A new address or sign-in starts over; the first look this run keeps what was saved.
                if self._seen is not None:
                    self.cfg["plexConnections"], self.cfg["plexConnectionsAt"] = [], 0
                    self._set_kind("local")
                self._seen, self._live, self._at = key, None, 0.0
            age = time.monotonic() - self._at
            # A forced look right after another (two requests failing together) needn't repeat it.
            if self._at and (age < 2 or (not force and age < RESOLVE_TTL)):
                return self._live
            # Keep plex.tv's list fresh, so the first time away from home it's already known.
            if self.signed_in and time.time() - (self.cfg.get("plexConnectionsAt") or 0) > REFRESH_SECS:
                self.refresh_connections()
            found = self._probe_all(self._candidates())
            # Nothing answered from what we knew: the public address may have changed.
            if found is None and self.signed_in and self.refresh_connections():
                found = self._probe_all(self._candidates())
            self._live = found[0] if found else None
            self._at = time.monotonic()
            self._set_kind(found[1] if found else "none")
            return self._live

    def _ensure(self):
        with contextlib.suppress(Exception):  # the request itself reports what's wrong
            self.resolve()

    def _candidates(self):
        out = [(self.base, "local")]
        for c in self.cfg.get("plexConnections") or []:
            uri = str(c.get("uri", "")).rstrip("/")
            if uri and c.get("kind") in ("local", "remote", "relay") and all(uri != u for u, _ in out):
                out.append((uri, c["kind"]))
        return out

    def _identity(self, base):
        try:
            req = urllib.request.Request(base + "/identity", headers={"Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=PROBE_SECS) as r:
                return json.loads(r.read()).get("MediaContainer", {}).get("machineIdentifier")
        except (OSError, ValueError, AttributeError):
            return None

    def _probe_all(self, cands):
        """Probes every candidate at once, takes the first in priority order that answers as our
        server: a quick LAN answer wins without waiting, and away from home the wait is one timeout."""
        machine = self.cfg.get("plexMachineId") or ""
        pool = ThreadPoolExecutor(max_workers=len(cands))
        try:
            futures = [pool.submit(self._identity, base) for base, _ in cands]
            for (base, kind), f in zip(cands, futures):
                mid = f.result()
                if not mid:
                    continue
                # A LAN address elsewhere (another house's 192.168.86.2) may be some other server.
                if machine and mid != machine:
                    continue
                if not machine:
                    self.cfg["plexMachineId"] = mid
                    self._save()
                return base, kind
            return None
        finally:
            pool.shutdown(wait=False)

    def refresh_connections(self):
        """Asks plex.tv for the server's connections and saves them. False if it couldn't."""
        if not self.signed_in:
            return False
        try:
            all_ = self._call(RESOURCES, token=self.token, timeout=15)
        except PlexError:
            return False
        servers = [r for r in all_ if isinstance(r, dict) and "server" in (r.get("provides") or "").split(",")] \
            if isinstance(all_, list) else []
        machine = self.cfg.get("plexMachineId") or ""
        server = next((r for r in servers if r.get("clientIdentifier") == machine), None)
        if server is None:
            owned = [r for r in servers if r.get("owned")]
            server = owned[0] if len(owned) == 1 else None
        if server is None:
            return False
        if not machine:
            self.cfg["plexMachineId"] = server.get("clientIdentifier", "")

        def rank(c):
            return 2 if c.get("relay") else 0 if c.get("local") else 1
        conns = [{"kind": "relay" if c.get("relay") else "local" if c.get("local") else "remote", "uri": c["uri"]}
                 for c in sorted(server.get("connections") or [], key=rank) if c.get("uri")]
        self.cfg["plexConnections"] = conns
        self.cfg["plexConnectionsAt"] = int(time.time())
        self._save()
        return True

    def _save(self):
        try:
            config.save(self.cfg)
        except OSError:
            pass

    def _rebase(self, url, live):
        """[url] on the live address, if it's built on the settings address."""
        if live and live != self.base and self._under_home(url):
            return live + url[len(self.base):]
        return url

    def _under_home(self, url):
        home = self.base
        return url == home or url.startswith((home + "/", home + "?"))

    def open(self, url, headers=None, timeout=20, method="GET", data=None):
        """urlopen for a URL built on [base] (or any other): sent to the address that works. A GET that
        can't connect looks again once and tries the new address; anything else is never sent twice."""
        h = headers or {}
        if not self._under_home(url):
            return urllib.request.urlopen(urllib.request.Request(url, method=method, headers=h, data=data), timeout=timeout)
        self._ensure()
        live = self._live
        try:
            return urllib.request.urlopen(
                urllib.request.Request(self._rebase(url, live), method=method, headers=h, data=data), timeout=timeout)
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, TimeoutError, OSError):
            if method != "GET":
                raise
            again = self.resolve(force=True)
            if again is None or again == live:
                raise
            return urllib.request.urlopen(
                urllib.request.Request(self._rebase(url, again), method=method, headers=h, data=data), timeout=timeout)

    @property
    def token(self):
        return self.cfg.get("plexToken", "")

    @property
    def signed_in(self):
        return bool(self.token)

    @property
    def section(self):
        return self.cfg.get("musicSection", "")

    def _headers(self, token=None):
        h = {
            "Accept": "application/json",
            "X-Plex-Product": self.product,
            "X-Plex-Version": "1.0",
            "X-Plex-Client-Identifier": self.cfg["clientId"],
            "X-Plex-Platform": "Linux",
            "X-Plex-Platform-Version": platform.release(),
            "X-Plex-Device": "Desktop",
            "X-Plex-Device-Name": f"{self.product} ({platform.node()})",
        }
        if token:
            h["X-Plex-Token"] = token
        return h

    def _call(self, url, method="GET", token=None, headers=None, timeout=20):
        hdrs = {**self._headers(token), **(headers or {})}
        try:
            with self.open(url, hdrs, timeout, method, b"" if method in ("POST", "PUT") else None) as r:
                body = r.read()
        except urllib.error.HTTPError as e:
            raise PlexError(f"Plex answered {e.code}") from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise PlexError(f"Can't reach Plex: {getattr(e, 'reason', e)}") from e
        if not body.strip() or body.lstrip()[:1] not in (b"{", b"["):
            return {}
        return json.loads(body)

    def _url(self, path, **params):
        q = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        return f"{self.base}{path}" + (f"?{q}" if q else "")

    def get(self, path, start=None, size=None, **params):
        if not self.signed_in:
            raise NotSignedIn()
        extra = {}
        if start is not None:
            extra["X-Plex-Container-Start"] = str(start)
        if size is not None:
            extra["X-Plex-Container-Size"] = str(size)
        return self._call(self._url(path, **params), token=self.token, headers=extra).get("MediaContainer", {})

    def send(self, method, path, **params):
        if not self.signed_in:
            raise NotSignedIn()
        return self._call(self._url(path, **params), method=method, token=self.token).get("MediaContainer", {})

    def all(self, path, progress=None, **params):
        """Pages through a listing; Plex caps one response on big libraries."""
        out, start = [], 0
        while True:
            page = self.get(path, start=start, size=PAGE, **params)
            items = page.get("Metadata") or []
            out += items
            start += len(items)
            total = page.get("totalSize") or page.get("size") or 0
            if progress:
                progress(start, total)
            if not items or start >= total:
                return out

    # ---- server ------------------------------------------------------------------
    def music_sections(self):
        dirs = self.get("/library/sections").get("Directory") or []
        return [{"key": d["key"], "title": d.get("title", ""), "locations": [l.get("path", "") for l in d.get("Location") or []]}
                for d in dirs if d.get("type") == "artist"]

    def machine_id(self):
        mid = self._call(self._url("/identity")).get("MediaContainer", {}).get("machineIdentifier")
        if not mid:
            raise PlexError("Not a Plex server")
        return mid

    # ---- library -----------------------------------------------------------------
    def tracks(self, progress=None):
        return [_track(m) for m in self.all(f"/library/sections/{self.section}/all", progress, type=10, sort="titleSort")]

    def albums(self, sort="titleSort"):
        return [_album(m) for m in self.all(f"/library/sections/{self.section}/all", type=9, sort=sort, includeGuids=1)]

    def tracks_page(self, start, size):
        """One page of every track by title, and the library's track count."""
        c = self.get(f"/library/sections/{self.section}/all", start=start, size=size, type=10, sort="titleSort")
        return [_track(m) for m in c.get("Metadata") or []], c.get("totalSize") or c.get("size") or 0

    def album(self, key):
        items = self.get(f"/library/metadata/{key}").get("Metadata") or []
        return _album(items[0]) if items else None

    def artist_albums(self, key):
        # Not /library/metadata/<artist>/children: that leaves out compilations and singles.
        return [_album(m) for m in self.all(f"/library/sections/{self.section}/all", type=9, **{"artist.id": key})]

    def search(self, query):
        hubs = self.get("/hubs/search", query=query, limit=12, sectionId=self.section).get("Hub") or []

        def hub(kind):
            return next((h.get("Metadata") or [] for h in hubs if h.get("type") == kind), [])
        return {"artists": [_artist(m) for m in hub("artist")], "albums": [_album(m) for m in hub("album")],
                "tracks": [_track(m) for m in hub("track")],
                "playlists": [_playlist(m) for m in hub("playlist") if m.get("playlistType") == "audio"]}

    def artists(self):
        return [_artist(m) for m in self.all(f"/library/sections/{self.section}/all", type=8, sort="titleSort")]

    def playlists(self):
        return [_playlist(m) for m in self.get("/playlists", playlistType="audio").get("Metadata") or []]

    def playlist_tracks(self, key):
        return [_track(m) for m in self.all(f"/playlists/{key}/items")]

    def album_tracks(self, key):
        return [_track(m) for m in self.get(f"/library/metadata/{key}/children").get("Metadata") or []]

    def artist_tracks(self, key):
        return [_track(m) for m in self.get(f"/library/metadata/{key}/allLeaves").get("Metadata") or []]

    def loudness(self, track_key, samples):
        """Plex's loudness analysis in dB (what Plexamp draws), or None if not analysed."""
        meta = (self.get(f"/library/metadata/{track_key}").get("Metadata") or [{}])[0]
        part = (((meta.get("Media") or [{}])[0]).get("Part") or [{}])[0]
        stream = next((s for s in part.get("Stream") or [] if s.get("streamType") == 2), None)
        if not stream:
            return None
        try:
            levels = self.get(f"/library/streams/{stream['id']}/levels", subsample=samples).get("Level") or []
        except PlexError:
            return None
        return [float(l.get("v", -60)) for l in levels] or None

    # ---- playlists -----------------------------------------------------------------
    def _library_uri(self, keys):
        mid = self.cfg.get("plexMachineId") or self.machine_id()
        self.cfg["plexMachineId"] = mid
        return f"server://{mid}/com.plexapp.plugins.library/library/metadata/{','.join(keys)}"

    def create_playlist(self, title, keys):
        c = self.send("POST", "/playlists", type="audio", title=title, smart=0, uri=self._library_uri(keys))
        items = c.get("Metadata") or []
        return _playlist(items[0]) if items else None

    def add_to_playlist(self, key, keys):
        self.send("PUT", f"/playlists/{key}/items", uri=self._library_uri(keys))

    def remove_from_playlist(self, key, item_id):
        self.send("DELETE", f"/playlists/{key}/items/{item_id}")

    def move_playlist_item(self, key, item_id, after_id=None):
        """Moves an item to just after [after_id], or to the top."""
        self.send("PUT", f"/playlists/{key}/items/{item_id}/move", after=after_id)

    def rename_playlist(self, key, title):
        self.send("PUT", f"/playlists/{key}", title=title)

    def delete_playlist(self, key):
        self.send("DELETE", f"/playlists/{key}")

    # ---- reporting ---------------------------------------------------------------
    def scrobble(self, key):
        try:
            self.get("/:/scrobble", key=key, identifier="com.plexapp.plugins.library")
        except PlexError:
            pass

    def timeline(self, track, state, position_ms):
        try:
            self.get("/:/timeline", ratingKey=track["key"], key=f"/library/metadata/{track['key']}", state=state,
                     time=int(position_ms), duration=track.get("durationMs", 0), hasMDE=1)
        except PlexError:
            pass

    # ---- URLs ----------------------------------------------------------------------
    def art_url(self, thumb, px, home=False):
        """Where art comes from: the address that works now, for Qt to fetch by itself. [home] builds it
        on the settings address instead, for [open] to route (and for a cache key)."""
        if not thumb or not self.signed_in:
            return ""
        url = self._url("/photo/:/transcode", width=px, height=px, minSize=1, upscale=1, url=thumb,
                        **{"X-Plex-Token": self.token})
        return url if home else self._rebase(url, self._live)

    def part_url(self, part_key):
        # Always a path on this server: a key like "@elsewhere/x" glued straight onto the
        # address would send the request, and the token, to elsewhere.
        return f"{self.base}/{str(part_key).lstrip('/')}?X-Plex-Token={urllib.parse.quote(self.token)}"

    # ---- plex.tv sign-in -----------------------------------------------------------
    def create_pin(self):
        return self._call("https://plex.tv/api/v2/pins?strong=false", method="POST")

    def check_pin(self, pin_id):
        return self._call(f"https://plex.tv/api/v2/pins/{pin_id}")

    def username(self, token):
        try:
            u = self._call("https://plex.tv/api/v2/user", token=token)
            return u.get("username") or u.get("title") or ""
        except PlexError:
            return ""

    def probe(self, url, token):
        try:
            self._call(url.rstrip("/") + "/library/sections", token=token, timeout=4)
            return True
        except PlexError:
            return False
