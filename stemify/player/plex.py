"""Plex Media Server and plex.tv calls (standard library only; call from worker threads)."""

import json
import platform
import urllib.error
import urllib.parse
import urllib.request

PRODUCT = "Stemify Player"
PAGE = 1000


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
        "title": m.get("title", ""),
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
        "codec": media.get("audioCodec", ""),
        "plays": m.get("viewCount") or 0,
        "addedAt": m.get("addedAt") or 0,
        "playlistItemID": m.get("playlistItemID"),
    }


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
    def __init__(self, config, product=PRODUCT):
        self.cfg = config
        self.product = product

    @property
    def base(self):
        return self.cfg["plexUrl"].rstrip("/")

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
        req = urllib.request.Request(url, method=method, headers={**self._headers(token), **(headers or {})},
                                     data=b"" if method in ("POST", "PUT") else None)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read()
        except urllib.error.HTTPError as e:
            raise PlexError(f"Plex answered {e.code}") from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise PlexError(f"Can't reach Plex: {getattr(e, 'reason', e)}") from e
        if not body.strip() or not body.lstrip().startswith(b"{"):
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
        return [_album(m) for m in self.all(f"/library/sections/{self.section}/all", type=9, sort=sort)]

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
    def art_url(self, thumb, px):
        if not thumb or not self.signed_in:
            return ""
        return self._url("/photo/:/transcode", width=px, height=px, minSize=1, upscale=1, url=thumb,
                         **{"X-Plex-Token": self.token})

    def part_url(self, part_key):
        return f"{self.base}{part_key}?X-Plex-Token={urllib.parse.quote(self.token)}"

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
