"""What the screens ask of the app: settings, library calls (run off the GUI thread, answered by
request id), Plex sign-in, stemserve status, and which albums and artists have stems."""

import json
import os
import time
from concurrent.futures import ThreadPoolExecutor

from PySide6.QtCore import Property, QObject, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QGuiApplication

from ..player import config
from ..player.plex import PlexError
from .art import album_color

# How stems travel from stemserve when they aren't on a mounted share; Opus is made (and kept)
# on the server. Same choices as the phone.
QUALITIES = [
    {"id": "lossless", "label": "Lossless", "detail": "Original 24-bit FLAC, ~30 MB per stem-minute", "kbps": 0},
    {"id": "high", "label": "High", "detail": "Opus 256 kbps per stem", "kbps": 256},
    {"id": "standard", "label": "Standard", "detail": "Opus 160 kbps per stem", "kbps": 160},
    {"id": "saver", "label": "Data Saver", "detail": "Opus 96 kbps per stem", "kbps": 96},
]

# Settings the screens may change with app.set().
EDITABLE = {"theme", "accent", "albumColor", "stemQuality", "keepMix", "cacheLimitGb", "downloadSort",
            "plexUrl", "stemUrl", "stemToken", "stemsDir", "musicDir", "musicSection", "musicSectionTitle", "sectionLocations"}
COVERAGE_STALE = 6 * 3600


class Backend(QObject):
    settingsChanged = Signal()
    statusChanged = Signal()
    indexChanged = Signal()
    coverageChanged = Signal()
    pinChanged = Signal()
    result = Signal(int, bool, "QVariant")
    hud = Signal(str, str)
    colorReady = Signal(str, str)
    _done = Signal(object, object)  # callback, value — runs the callback on the GUI thread

    def __init__(self, cfg, plex, stems, art, cache_dir, parent=None):
        super().__init__(parent)
        self.cfg, self.plex, self.stems, self.art_cache = cfg, plex, stems, art
        self.cache_dir = cache_dir
        self._pool = ThreadPoolExecutor(max_workers=6)
        self._next_id = 0
        self._status = {"state": "unknown", "tracks": len(stems.index), "transcode": False, "reason": ""}
        self._index_version = 0
        self._pin = ""
        self._pin_error = ""
        self._cov_file = os.path.join(cache_dir, "coverage.json")
        self._listing = None
        self._albums_cov, self._artists_cov, self._cov_ready = {}, {}, False
        self._done.connect(lambda fn, v: fn(v))
        try:
            with open(self._cov_file) as f:
                self._listing = json.load(f)
        except (OSError, ValueError):
            pass
        self._recompute()
        # stemd separates new music around the clock; look for its new stems every minute.
        self._stem_timer = QTimer(self, interval=60_000)
        self._stem_timer.timeout.connect(self.refreshStems)
        self._stem_timer.start()
        QTimer.singleShot(0, self.refreshStems)
        QTimer.singleShot(1500, self._maybe_list)

    def _bg(self, fn, then=None):
        def run():
            try:
                v = fn()
            except Exception as e:  # noqa: BLE001 - handed to `then` as the result
                v = e
            if then:
                self._done.emit(then, v)
        self._pool.submit(run)

    # ---- settings -----------------------------------------------------------------------------
    def _get_settings(self):
        c = self.cfg
        return {k: c.get(k) for k in EDITABLE | {"plexUser"}} | {"signedIn": self.signed_in, "token": bool(c.get("plexToken"))}

    settings = Property("QVariantMap", _get_settings, notify=settingsChanged)
    qualities = Property("QVariantList", lambda self: QUALITIES, constant=True)

    @property
    def signed_in(self):
        return bool(self.cfg.get("plexToken")) and bool(self.cfg.get("musicSection"))

    @Slot(str, "QVariant")
    def set(self, key, value):
        if key not in EDITABLE or self.cfg.get(key) == value:
            return
        self.cfg[key] = value
        config.save(self.cfg)
        self.settingsChanged.emit()
        if key == "musicSection":
            self._listing = None
            self._recompute()
            self._maybe_list()

    def opus_kbps(self):
        """What to ask stemserve for: Opus needs ffmpeg there."""
        q = next((q for q in QUALITIES if q["id"] == self.cfg.get("stemQuality")), QUALITIES[1])
        return q["kbps"] if self._status.get("transcode") else 0

    def _get_effective_quality(self):
        k = self.opus_kbps()
        return f"OPUS {k}" if k else "FLAC"

    effectiveQuality = Property(str, _get_effective_quality, notify=statusChanged)

    # ---- generic async calls: request(name, args) → result(id, ok, value) ----------------------
    @Slot(str, "QVariantList", result=int)
    def request(self, name, args):
        self._next_id += 1
        rid = self._next_id
        fn = getattr(self, "api_" + name, None)

        def run():
            try:
                self.result.emit(rid, True, fn(*args))
            except Exception as e:  # noqa: BLE001 - the screen shows it
                self.result.emit(rid, False, str(e) or type(e).__name__)
        if fn is None:
            QTimer.singleShot(0, lambda: self.result.emit(rid, False, f"no call {name}"))
        else:
            self._pool.submit(run)
        return rid

    def api_albums(self, sort="titleSort"):
        return self.plex.albums(sort)

    def api_artists(self):
        return self.plex.artists()

    def api_tracksPage(self, start, size):
        tracks, total = self.plex.tracks_page(int(start), int(size))
        return {"tracks": tracks, "total": total}

    def api_album(self, key):
        return self.plex.album(key) or {}

    def api_albumTracks(self, key):
        return self.plex.album_tracks(key)

    def api_artistAlbums(self, key):
        return sorted(self.plex.artist_albums(key), key=lambda a: -(a.get("year") or 0))

    def api_artistTracks(self, key):
        return self.plex.artist_tracks(key)

    def api_playlists(self):
        return self.plex.playlists()

    def api_playlistTracks(self, key):
        return self.plex.playlist_tracks(key)

    def api_tracksOf(self, kind, key):
        """The songs behind an album, artist or playlist (for the ••• menu's actions)."""
        return {"album": self.plex.album_tracks, "artist": self.plex.artist_tracks,
                "playlist": self.plex.playlist_tracks}[kind](key)

    def api_search(self, query):
        return self.plex.search(query)

    def api_sections(self):
        return self.plex.music_sections()

    def api_createPlaylist(self, title, tracks):
        keys = [t["key"] for t in tracks if not t["key"].startswith("stem:")]
        return self.plex.create_playlist(title, keys) or {}

    def api_addToPlaylist(self, key, tracks):
        self.plex.add_to_playlist(key, [t["key"] for t in tracks if not t["key"].startswith("stem:")])
        return True

    def api_removeFromPlaylist(self, key, item_id):
        self.plex.remove_from_playlist(key, int(item_id))
        return True

    def api_renamePlaylist(self, key, title):
        self.plex.rename_playlist(key, title)
        return True

    def api_deletePlaylist(self, key):
        self.plex.delete_playlist(key)
        return True

    def api_savePlaylist(self, key, original, target, removed):
        """Removes, then replays the new order as "move after" calls against a simulated list."""
        gone = {t["playlistItemID"] for t in removed}
        for i in gone:
            self.plex.remove_from_playlist(key, int(i))
        sim = [t["playlistItemID"] for t in original if t["playlistItemID"] not in gone]
        want = [t["playlistItemID"] for t in target]
        for i, item in enumerate(want):
            if i >= len(sim) or sim[i] != item:
                self.plex.move_playlist_item(key, int(item), int(want[i - 1]) if i else None)
                sim.remove(item)
                sim.insert(i, item)
        return True

    def api_stemNow(self, tracks):
        """Stem Now: returns how many it queued (the songs with no stems yet)."""
        paths = list(dict.fromkeys(t["partFile"] for t in tracks if t.get("partFile") and not self.stems.find(t)))
        if paths:
            self.stems.stem_now(paths)
        return len(paths)

    def api_probe(self, url, token):
        return self.plex.probe(url, token or self.cfg.get("plexToken", ""))

    # ---- small sync helpers -------------------------------------------------------------------
    @Slot("QVariantMap", result=bool)
    def hasStems(self, track):
        if track.get("key", "").startswith("stem:"):
            return True
        return self.stems.find(track) is not None

    @Slot(result=bool)
    def indexLoaded(self):
        return len(self.stems.index) > 0

    @Slot(str, bool)
    def requestColor(self, thumb, dark):
        if not thumb:
            return
        self._bg(lambda: album_color(self.art_cache.image(thumb, 120), dark),
                 lambda c: self.colorReady.emit(thumb, c if isinstance(c, str) else ""))

    @Slot(str)
    def copyText(self, text):
        QGuiApplication.clipboard().setText(text)

    @Slot(result=str)
    def clipboardText(self):
        return QGuiApplication.clipboard().text()

    @Slot(str)
    def openUrl(self, url):
        QDesktopServices.openUrl(QUrl(url))

    # ---- stemserve ----------------------------------------------------------------------------
    def _get_status(self):
        return dict(self._status, tracks=len(self.stems.index))

    status = Property("QVariantMap", _get_status, notify=statusChanged)
    indexVersion = Property(int, lambda self: self._index_version, notify=indexChanged)

    @Slot()
    def refreshStems(self):
        def work():
            try:
                info = self.stems.info()
            except Exception as e:  # noqa: BLE001 - shown as Offline
                return {"state": "offline", "transcode": False, "reason": str(getattr(e, "reason", e)), "changed": False}
            changed = self.stems.refresh()
            return {"state": "online" if self.stems.online else "offline", "transcode": bool(info.get("ffmpeg")),
                    "reason": "" if self.stems.online else "stemserve didn't answer", "changed": changed}

        def done(r):
            if isinstance(r, Exception):
                r = {"state": "offline", "transcode": False, "reason": str(r), "changed": False}
            changed = r.pop("changed")
            self._status = r
            self.statusChanged.emit()
            if changed:
                self._index_version += 1
                self.indexChanged.emit()
                self._recompute()
        self._bg(work, done)

    @Slot(str, str)
    def connectStems(self, url, token):
        self.cfg["stemUrl"], self.cfg["stemToken"] = url.strip().rstrip("/"), token.strip()
        config.save(self.cfg)
        self.settingsChanged.emit()

        def done(_):
            self.refreshStems()
        # force a full fetch: forget the ETag
        self._bg(lambda: os.path.exists(self.stems._etag_file) and os.remove(self.stems._etag_file), done)

    # ---- which albums and artists have stems ---------------------------------------------------
    def _maybe_list(self):
        sec = self.cfg.get("musicSection")
        l = self._listing
        if self.signed_in and (not l or l.get("section") != sec or time.time() - l.get("at", 0) > COVERAGE_STALE):
            self._bg(self._list, self._listed)

    def _list(self):
        sec = self.cfg.get("musicSection")
        tracks = self.plex.tracks()
        return {"section": sec, "at": time.time(),
                "tracks": [[t["key"], t["albumKey"], t["artistKey"], t["partFile"], t["durationMs"]] for t in tracks]}

    def _listed(self, l):
        if isinstance(l, Exception):
            return
        self._listing = l

        def save():
            os.makedirs(self.cache_dir, exist_ok=True)
            tmp = self._cov_file + ".tmp"
            with open(tmp, "w") as f:
                json.dump(l, f)
            os.replace(tmp, self._cov_file)
        self._bg(save)
        self._recompute()

    def _recompute(self):
        l = self._listing
        if not l or l.get("section") != self.cfg.get("musicSection") or not len(self.stems.index):
            return

        def work():
            albums, artists = {}, {}
            for key, al, ar, pf, d in l["tracks"]:
                has = self.stems.index.find(pf, d) is not None
                for m, k in ((albums, al), (artists, ar)):
                    if k:
                        c = m.setdefault(k, [0, 0])
                        c[0] += 1
                        c[1] += has
            return albums, artists

        def done(r):
            if isinstance(r, Exception):
                return
            self._albums_cov, self._artists_cov = r
            self._cov_ready = True
            self.coverageChanged.emit()
        self._bg(work, done)

    albumCoverage = Property("QVariantMap", lambda self: self._albums_cov, notify=coverageChanged)
    artistCoverage = Property("QVariantMap", lambda self: self._artists_cov, notify=coverageChanged)
    coverageReady = Property(bool, lambda self: self._cov_ready, notify=coverageChanged)

    # ---- Plex sign-in -------------------------------------------------------------------------
    pin = Property(str, lambda self: self._pin, notify=pinChanged)
    pinError = Property(str, lambda self: self._pin_error, notify=pinChanged)

    @Slot()
    def signIn(self):
        self._pin_error = ""
        self.pinChanged.emit()

        def started(p):
            if isinstance(p, Exception) or not p.get("code"):
                self._pin_error = f"Couldn't reach plex.tv: {p}"
                self.pinChanged.emit()
                return
            self._pin = p["code"]
            self.pinChanged.emit()
            code = p["code"]

            def wait():
                for _ in range(300):
                    time.sleep(2)
                    if self._pin != code:
                        return None
                    try:
                        t = self.plex.check_pin(p["id"]).get("authToken")
                    except PlexError:
                        continue
                    if t:
                        return t
                return ""

            def got(token):
                if self._pin != code:
                    return
                self._pin = ""
                if not token:
                    self._pin_error = "The code expired. Try again."
                    self.pinChanged.emit()
                    return
                self.pinChanged.emit()
                self._finish(token)
            self._bg(wait, got)
        self._bg(self.plex.create_pin, started)

    @Slot()
    def cancelSignIn(self):
        self._pin = ""
        self.pinChanged.emit()

    @Slot(str, str)
    def useToken(self, url, token):
        def check():
            return self.plex.probe(url, token)

        def done(ok):
            if ok is True:
                self.cfg["plexUrl"] = url.strip().rstrip("/")
                self._finish(token)
            else:
                self.hud.emit("That token didn't work on this server", "warning")
        self._bg(check, done)

    def _finish(self, token):
        self.cfg["plexToken"] = token

        def work():
            user = self.plex.username(token) or "Plex user"
            try:
                self.cfg["plexMachineId"] = self.plex.machine_id()
            except PlexError:
                pass
            return user, self.plex.music_sections()

        def done(r):
            if isinstance(r, Exception):
                self.hud.emit(f"Signed in, but Plex didn't answer: {r}", "warning")
                r = ("Plex user", [])
            user, secs = r
            self.cfg["plexUser"] = user
            if len(secs) >= 1 and not self.cfg.get("musicSection"):
                pick = next((s for s in secs if any("Flacs" in l for l in s["locations"])), secs[0])
                self.cfg["musicSection"], self.cfg["musicSectionTitle"] = pick["key"], pick["title"]
                self.cfg["sectionLocations"] = pick["locations"]
            config.save(self.cfg)
            self.settingsChanged.emit()
            self.hud.emit("Signed In", "check")
            self.refreshStems()
            self._maybe_list()
        self._bg(work, done)

    @Slot()
    def signOut(self):
        for k in ("plexToken", "plexUser", "musicSection", "musicSectionTitle"):
            self.cfg[k] = ""
        self.cfg["sectionLocations"] = []
        config.save(self.cfg)
        self.settingsChanged.emit()

    @Slot(str, str, "QVariantList")
    def selectSection(self, key, title, locations):
        self.cfg["sectionLocations"] = list(locations)
        self.cfg["musicSectionTitle"] = title
        self.set("musicSection", key)
        config.save(self.cfg)
        self.settingsChanged.emit()

    def shutdown(self):
        self._pool.shutdown(wait=False, cancel_futures=True)
