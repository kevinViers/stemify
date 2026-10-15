"""The library as a workbook: Plex data cached on disk, refreshed in the background, shown as sheets."""

import json
import os
import random
import re
import threading
import time

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot
from PySide6.QtGui import QGuiApplication

from . import config
from .plex import PlexError
from .sheets import ALBUM, ARTIST, PLAYLIST, SONG, Sheet, SheetModel, fmt, fmt_time, fold

BASE_TABS = ["Songs", "Artists", "Albums", "Playlists", "Stems", "Queue"]
_SONG_NAME = re.compile(r"^(?:.* - )?(?:\d{1,2}-)?(\d{1,3})\s*[-.]?\s+(.+)$")


class Library(QObject):
    tabsChanged = Signal()
    sheetChanged = Signal()  # anything about the current sheet: data, sort, columns
    sheetSwitched = Signal()  # a different sheet is showing: selection and scroll start over
    statusChanged = Signal()
    toast = Signal(str)
    _done = Signal(str, object)  # worker results, delivered on the GUI thread

    def __init__(self, cfg, plex, stems, parent=None):
        super().__init__(parent)
        self.cfg, self.plex, self.stems = cfg, plex, stems
        self.player = None  # set by the app; the queue sheet reads it
        self.tracks, self.artists, self.albums, self.playlists = [], [], [], []
        self._by_album, self._by_artist = {}, {}
        self._tabs = [Sheet(t, "songs", []) for t in BASE_TABS]
        self._tabs[1] = Sheet("Artists", "artists", [], ARTIST)
        self._tabs[2] = Sheet("Albums", "albums", [], ALBUM)
        self._tabs[3] = Sheet("Playlists", "playlists", [], PLAYLIST)
        self._tabs[5] = Sheet("Queue", "queue", [])
        self._current = 0
        self.model = SheetModel(self)
        self._status = ""
        self._progress = -1.0
        self._busy = False
        self._done.connect(self._on_done)
        self._cache = os.path.join(config.CACHE_DIR, "library.json")
        self._load_cache()
        self._rebuild()
        self.model.set_sheet(self._tabs[0])
        QTimer.singleShot(0, self.refresh)
        # stemserve rescans every 30 s; pick up newly separated songs while open
        self._stem_timer = QTimer(self, interval=120_000)
        self._stem_timer.timeout.connect(self._refresh_stems)
        self._stem_timer.start()

    # ---- data ------------------------------------------------------------------------
    def _load_cache(self):
        try:
            with open(self._cache) as f:
                d = json.load(f)
            if d.get("section") == self.cfg.get("musicSection"):
                self.tracks, self.artists = d["tracks"], d["artists"]
                self.albums, self.playlists = d["albums"], d["playlists"]
        except (OSError, ValueError, KeyError):
            pass

    def _save_cache(self):
        os.makedirs(config.CACHE_DIR, exist_ok=True)
        tmp = self._cache + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"section": self.cfg.get("musicSection"), "tracks": self.tracks, "artists": self.artists,
                       "albums": self.albums, "playlists": self.playlists}, f)
        os.replace(tmp, self._cache)

    def _work(self, tag, fn):
        def run():
            try:
                self._done.emit(tag, fn())
            except Exception as e:  # noqa: BLE001 - shown in the status bar
                self._done.emit(tag + ":error", e)
        threading.Thread(target=run, daemon=True).start()

    @Slot()
    def refresh(self):
        """Data → Refresh All."""
        self._refresh_stems()
        if not self.plex.signed_in or not self.cfg.get("musicSection") or self._busy:
            return
        self._busy = True
        self._set_status("Calculating: Plex library", 0.0)

        def fetch():
            def progress(n, total):
                self._done.emit("progress", (n, total))
            return {"tracks": self.plex.tracks(progress), "artists": self.plex.artists(),
                    "albums": self.plex.albums(), "playlists": self.plex.playlists()}
        self._work("library", fetch)

    def _refresh_stems(self):
        self._work("stems", self.stems.refresh)

    def _on_done(self, tag, result):
        if tag == "pin":
            self._poll_pin(result)
        elif tag == "token":
            self._signed_in(result)
        elif tag == "sections":
            self._sections(result)
        elif tag == "progress":
            n, total = result
            self._set_status("Calculating: Plex library", n / total if total else 0.0)
        elif tag == "library":
            self._busy = False
            self.tracks, self.artists = result["tracks"], result["artists"]
            self.albums, self.playlists = result["albums"], result["playlists"]
            self._rebuild()
            self._set_status("", -1)
            self._work("save", self._save_cache)
        elif tag == "stems":
            if result:
                self._rebuild()
            self.statusChanged.emit()
        elif tag == "library:error":
            self._busy = False
            self._set_status("", -1)
            self.toast.emit(str(result))
        elif tag.endswith(":error"):
            self.toast.emit(str(result))
            for s in self._tabs:
                if s.loading:
                    s.loading = False
            self.sheetChanged.emit()
        elif tag.startswith("playlist:"):
            key = tag.split(":", 1)[1]
            for s in self._tabs:
                if s.sid == f"playlist:{key}":
                    s.rows, s.loading = self._song_rows(result), False
                    if s is self._tabs[self._current]:
                        self.model.set_sheet(s)
                    self.sheetChanged.emit()
        elif tag == "playlists":
            self.playlists = result
            self._tabs[3].rows = self._playlist_rows()
            if self._current == 3:
                self.model.refresh()

    def _stems_only(self):
        """Without Plex, the library is the stem server's index: artist/album/song from the folders."""
        tracks, albums, artists = [], {}, {}
        for e in self.stems.index.entries:
            parts = e["key"].split("/")
            artist = parts[0] if len(parts) > 2 else ""
            album = parts[-2] if len(parts) > 1 else ""
            m = _SONG_NAME.match(parts[-1])
            title, index = (m.group(2), int(m.group(1))) if m else (parts[-1], 0)
            ak, rk = "stem-album:" + "/".join(parts[:-1]), "stem-artist:" + artist
            tracks.append({"key": "stem:" + e["key"], "title": title, "artist": artist, "albumArtist": artist,
                           "album": album, "albumKey": ak, "artistKey": rk, "year": 0, "index": index, "disc": 0,
                           "durationMs": int((e.get("duration") or 0) * 1000), "thumb": "", "partFile": "",
                           "partKey": "", "codec": "flac", "plays": 0, "addedAt": int(e.get("finished") or 0),
                           "stemKey": e["key"]})
            albums.setdefault(ak, {"key": ak, "title": album, "artist": artist, "artistKey": rk, "year": 0,
                                   "tracks": 0, "thumb": "", "addedAt": 0, "genre": ""})["tracks"] += 1
            artists.setdefault(rk, {"key": rk, "title": artist, "sortTitle": artist, "albums": 0, "thumb": "", "genre": ""})
        for a in albums.values():
            artists[a["artistKey"]]["albums"] += 1
        tracks.sort(key=lambda t: fold(t["title"]))
        return tracks, sorted(artists.values(), key=lambda a: fold(a["title"])), \
            sorted(albums.values(), key=lambda a: fold(a["title"]))

    def _rebuild(self):
        """Derives stems flags, groupings and the base sheets from the raw lists."""
        if not self.plex.signed_in or not self.cfg.get("musicSection"):
            self.tracks, self.artists, self.albums = self._stems_only()
            self.playlists = []
        for t in self.tracks:
            e = self._entry(t)
            t["stemKey"] = e["key"] if e else ""
            t["stems"] = len(e["stems"]) if e else 0
        self._by_album, self._by_artist = {}, {}
        for t in self.tracks:
            self._by_album.setdefault(t["albumKey"], []).append(t)
            self._by_artist.setdefault(t["artistKey"], []).append(t)
        for lst in self._by_album.values():
            lst.sort(key=lambda t: (t.get("disc") or 0, t.get("index") or 0))
        for a in self.artists:
            songs = self._by_artist.get(a["key"], [])
            a["songs"] = len(songs)
            a["stemSongs"] = sum(1 for t in songs if t["stems"])
        for a in self.albums:
            a["stemSongs"] = sum(1 for t in self._by_album.get(a["key"], []) if t["stems"])
        self._tabs[0].rows = self._song_rows(self.tracks)
        self._tabs[1].rows = [dict(a) for a in self.artists]
        self._tabs[2].rows = [dict(a) for a in self.albums]
        self._tabs[3].rows = self._playlist_rows()
        self._tabs[4].rows = self._song_rows([t for t in self.tracks if t["stems"]])
        for s in self._tabs[:5]:
            if s.sort:
                self._apply_sort(s)
        self.model.refresh()
        self.sheetChanged.emit()
        self.statusChanged.emit()

    def _entry(self, t):
        if t["key"].startswith("stem:"):
            return self.stems.index.by_key.get(t.get("stemKey"))
        return self.stems.find(t)

    def _playlist_rows(self):
        return [dict(p, kind="Smart" if p["smart"] else "Manual") for p in self.playlists]

    def _song_rows(self, tracks):
        rows = []
        for t in tracks:
            e = self._entry(t)
            rows.append(dict(t, stemKey=e["key"] if e else "", stems=len(e["stems"]) if e else 0))
        return rows

    # ---- tabs -----------------------------------------------------------------------
    def _get_tabs(self):
        return [{"title": s.title, "base": i < len(BASE_TABS)} for i, s in enumerate(self._tabs)]

    def _get_current(self):
        return self._current

    @Slot(int)
    def setCurrent(self, i):
        if 0 <= i < len(self._tabs) and i != self._current:
            self._current = i
            if self._tabs[i].layout == "queue":
                self.update_queue()
            self.sheetSwitched.emit()
            self.model.set_sheet(self._tabs[i])
            self.tabsChanged.emit()
            self.sheetChanged.emit()

    @Slot(int)
    def closeTab(self, i):
        if i >= len(BASE_TABS) and i < len(self._tabs):
            del self._tabs[i]
            cur = min(self._current, len(self._tabs) - 1) if self._current >= i else self._current
            self._current = -1
            self.tabsChanged.emit()
            self.setCurrent(cur if cur != i or cur < len(self._tabs) else i - 1)

    @Slot()
    def back(self):
        """Backspace: closes the drilled-in sheet and returns to the one before it."""
        if self._current >= len(BASE_TABS):
            self.closeTab(self._current)

    def _open(self, sheet):
        """Opens a drilled-in sheet after the current one, replacing what was to its right."""
        for i, s in enumerate(self._tabs):
            if s.sid == sheet.sid:
                self._tabs[i] = sheet
                self._current = -1
                self.setCurrent(i)
                return
        cut = max(len(BASE_TABS), self._current + 1)
        self._tabs = self._tabs[:cut] + [sheet]
        self.tabsChanged.emit()
        self.setCurrent(len(self._tabs) - 1)

    @property
    def sheet(self) -> Sheet:
        return self._tabs[self._current]

    # ---- rows -----------------------------------------------------------------------
    @Slot(int, int)
    def activate(self, row, mods=0):
        """Enter / double-click: play a song from here, or open an artist, album or playlist."""
        s = self.sheet
        if not (0 <= row < len(s.rows)):
            return
        kind, r = s.row_kind(row), s.rows[row]
        if kind == SONG:
            if s.layout == "queue":
                self.player.skipTo(row)
                return
            songs = [x for i, x in enumerate(s.rows) if s.row_kind(i) == SONG]
            self.player.play_tracks(songs, songs.index(r))
        elif kind == ARTIST:
            albums = sorted((a for a in self.albums if a["artistKey"] == r["key"]), key=lambda a: a.get("year") or 0)
            self._open(Sheet(r["title"], "albums", [dict(a) for a in albums], ALBUM, sid=f"artist:{r['key']}"))
        elif kind == ALBUM:
            self._open(Sheet(r["title"], "albumSongs", self._song_rows(self._by_album.get(r["key"], [])),
                             sid=f"album:{r['key']}"))
        elif kind == PLAYLIST:
            self._open(Sheet(r["title"], "songs", [], sid=f"playlist:{r['key']}", loading=True))
            self._work(f"playlist:{r['key']}", lambda: self.plex.playlist_tracks(r["key"]))

    def rows_for_play(self, r0, r1):
        s = self.sheet
        out = []
        for i in range(max(0, r0), min(len(s.rows), r1 + 1)):
            k, r = s.row_kind(i), s.rows[i]
            if k == SONG:
                out.append(r)
            elif k == ALBUM:
                out += self._song_rows(self._by_album.get(r["key"], []))
            elif k == ARTIST:
                for a in sorted((a for a in self.albums if a["artistKey"] == r["key"]), key=lambda a: a.get("year") or 0):
                    out += self._song_rows(self._by_album.get(a["key"], []))
        return out

    @Slot(int, int)
    def queueRows(self, r0, r1):
        tracks = self.rows_for_play(r0, r1)
        if tracks:
            self.player.add_to_queue(tracks)
            self.toast.emit(f"Added {len(tracks)} song{'s' if len(tracks) != 1 else ''} to the queue")

    @Slot(int, int)
    def playNextRows(self, r0, r1):
        tracks = self.rows_for_play(r0, r1)
        if tracks:
            self.player.play_next(tracks)
            self.toast.emit(f"{len(tracks)} song{'s' if len(tracks) != 1 else ''} will play next")

    @Slot(int, int)
    def playRows(self, r0, r1):
        tracks = self.rows_for_play(r0, r1)
        if tracks:
            self.player.play_tracks(tracks, 0)

    @Slot()
    def shuffleSheet(self):
        """AutoSum's spot on the ribbon: shuffle everything on this sheet."""
        tracks = self.rows_for_play(0, len(self.sheet.rows) - 1)
        if tracks:
            self.player.play_tracks(tracks, random.randrange(len(tracks)), shuffle=True)

    @Slot(int, int)
    def removeRows(self, r0, r1):
        s = self.sheet
        if s.layout == "queue":
            for i in range(min(r1, len(s.rows) - 1), r0 - 1, -1):
                self.player.removeAt(i)
        elif s.sid.startswith("playlist:"):
            key = s.sid.split(":", 1)[1]
            items = [s.rows[i] for i in range(r0, min(r1 + 1, len(s.rows)))]

            def remove():
                for it in items:
                    if it.get("playlistItemID"):
                        self.plex.remove_from_playlist(key, it["playlistItemID"])
                return self.plex.playlist_tracks(key)
            self._work(f"playlist:{key}", remove)

    @Slot(str, int, int)
    def newPlaylist(self, title, r0, r1):
        keys = [t["key"] for t in self.rows_for_play(r0, r1)]
        if not title.strip() or not keys:
            return

        def make():
            self.plex.create_playlist(title.strip(), keys)
            return self.plex.playlists()
        self._work("playlists", make)
        self.toast.emit(f"Created playlist “{title.strip()}”")

    @Slot(str, int, int)
    def addToPlaylist(self, title, r0, r1):
        keys = [t["key"] for t in self.rows_for_play(r0, r1)]
        p = next((p for p in self.playlists if p["title"] == title and not p["smart"]), None)
        if not keys or p is None:
            return

        def add():
            self.plex.add_to_playlist(p["key"], keys)
            return self.plex.playlists()
        self._work("playlists", add)
        self.toast.emit(f"Added {len(keys)} song{'s' if len(keys) != 1 else ''} to “{p['title']}”")

    def _get_playlist_names(self):
        return [p["title"] for p in self.playlists if not p["smart"]]

    # ---- queue sheet ------------------------------------------------------------------
    def update_queue(self):
        q = self._tabs[5]
        q.rows = [dict(self._song_rows([t])[0], pos=i + 1) for i, t in enumerate(self.player.queue)]
        if self._current == 5:
            self.model.refresh()
        self.model.set_playing(self.player.current_key(), self.player.index)

    # ---- search, sort ------------------------------------------------------------------
    @Slot(str)
    def search(self, text):
        q = fold(text.strip())
        if not q:
            return
        rows = [dict(a, _kind=ARTIST, type="Artist") for a in self.artists if q in fold(a["title"])][:50]
        rows += [dict(a, _kind=ALBUM, type="Album", album="") for a in self.albums if q in fold(a["title"])][:100]
        hits = [t for t in self.tracks if q in fold(t["title"])]
        hits += [t for t in self.tracks if q not in fold(t["title"]) and (q in fold(t["artist"]) or q in fold(t["album"]))]
        rows += [dict(r, type="Song") for r in self._song_rows(hits[:2000])]
        self._open(Sheet(f"Search “{text.strip()}”", "search", rows, SONG, sid="search"))

    @Slot(int)
    def sortBy(self, col):
        s = self.sheet
        desc = bool(s.sort and s.sort[0] == col and not s.sort[1])
        s.sort = (col, desc)
        self._apply_sort(s)
        self.model.refresh()
        self.sheetChanged.emit()

    @Slot(int, bool)
    def sortTo(self, col, desc):
        s = self.sheet
        if 0 <= col < len(s.columns):
            s.sort = (col, desc)
            self._apply_sort(s)
            self.model.refresh()
            self.sheetChanged.emit()

    @staticmethod
    def _apply_sort(s):
        col, desc = s.sort
        key, _, _, kind = s.columns[col]
        if kind == "text":
            s.rows.sort(key=lambda r: fold(str(r.get(key) or "")), reverse=desc)
        else:
            s.rows.sort(key=lambda r: r.get(key) or 0, reverse=desc)

    def _get_sort_col(self):
        return self.sheet.sort[0] if self.sheet.sort else -1

    def _get_sort_desc(self):
        return bool(self.sheet.sort and self.sheet.sort[1])

    # ---- formula bar, status bar --------------------------------------------------------
    @Slot(int, int, result=str)
    def formula(self, row, col):
        """What the formula bar shows for a cell: the value, or a formula for the row's action."""
        s = self.sheet
        if not (0 <= row < len(s.rows) and 0 <= col < len(s.columns)):
            return ""
        r = s.rows[row]
        key, _, _, kind = s.columns[col]
        if kind == "stems":
            return f"=STEMS(\"{r.get('title', '')}\")" if r.get(key) else "=FALSE"
        if kind == "time" and r.get(key):
            return f"=TIME(0,{int(r[key] / 60000)},{int(r[key] / 1000) % 60})"
        if key == "title":
            k = s.row_kind(row)
            fn = {SONG: "PLAY", ARTIST: "ARTIST", ALBUM: "ALBUM", PLAYLIST: "PLAYLIST"}[k]
            q = r["title"].replace('"', '""')
            return f'={fn}("{q}")'
        v = r.get(key)
        return "" if v in (None, "", 0) and kind != "int" else str(v)

    @Slot(int, int, int, int)
    def copyCells(self, r0, c0, r1, c1):
        """Ctrl+C: the selection as tab-separated text, like copying from Excel."""
        s = self.sheet
        r0, r1 = max(0, min(r0, r1)), min(len(s.rows) - 1, max(r0, r1))
        c0, c1 = max(0, min(c0, c1)), min(len(s.columns) - 1, max(c0, c1))
        lines = ["\t".join(fmt(s.rows[r].get(s.columns[c][0]), s.columns[c][3]) for c in range(c0, c1 + 1))
                 for r in range(r0, r1 + 1)]
        QGuiApplication.clipboard().setText("\n".join(lines))

    @Slot(int, int, int, int, result="QVariantMap")
    def stats(self, r0, c0, r1, c1):
        """Average / Count / Sum for a selection, like Excel's status bar."""
        s = self.sheet
        r0, r1 = max(0, min(r0, r1)), min(len(s.rows) - 1, max(r0, r1))
        c0, c1 = max(0, min(c0, c1)), min(len(s.columns) - 1, max(c0, c1))
        cells, nums, kinds = 0, [], set()
        for r in range(r0, r1 + 1):
            row = s.rows[r]
            for c in range(c0, c1 + 1):
                key, _, _, kind = s.columns[c]
                v = row.get(key)
                if v in (None, "") or (kind != "int" and not v):
                    continue
                cells += 1
                if kind in ("int", "time", "year") and isinstance(v, (int, float)):
                    nums.append(v)
                    kinds.add(kind)
        if cells < 2:
            return {}
        out = {"count": cells}
        if nums:
            is_time = kinds == {"time"}
            total = sum(nums)
            out["sum"] = fmt_time(total) if is_time else f"{total:,}"
            avg = total / len(nums)
            out["average"] = fmt_time(avg) if is_time else f"{avg:,.1f}".rstrip("0").rstrip(".")
        return out

    def _set_status(self, text, progress):
        self._status, self._progress = text, progress
        self.statusChanged.emit()

    def _get_status(self):
        return self._status

    def _get_progress(self):
        return self._progress

    def _get_sheet_title(self):
        return self.sheet.title

    def _get_loading(self):
        return self.sheet.loading

    def _get_sheet_id(self):
        return self.sheet.sid

    def _get_layout(self):
        return self.sheet.layout

    def _get_columns(self):
        return [{"title": c[1], "width": c[2], "kind": c[3]} for c in self.sheet.columns]

    def _get_counts(self):
        return {"songs": len(self.tracks), "stems": sum(1 for t in self.tracks if t.get("stems")),
                "stemIndex": len(self.stems.index), "stemOnline": self.stems.online}

    tabs = Property("QVariantList", _get_tabs, notify=tabsChanged)
    current = Property(int, _get_current, setCurrent, notify=tabsChanged)
    columns = Property("QVariantList", _get_columns, notify=sheetChanged)
    sheetTitle = Property(str, _get_sheet_title, notify=sheetChanged)
    layout = Property(str, _get_layout, notify=sheetChanged)
    sheetId = Property(str, _get_sheet_id, notify=sheetChanged)
    loading = Property(bool, _get_loading, notify=sheetChanged)
    sortColumn = Property(int, _get_sort_col, notify=sheetChanged)
    sortDescending = Property(bool, _get_sort_desc, notify=sheetChanged)
    status = Property(str, _get_status, notify=statusChanged)
    progress = Property(float, _get_progress, notify=statusChanged)
    counts = Property("QVariantMap", _get_counts, notify=statusChanged)
    playlistNames = Property("QVariantList", _get_playlist_names, notify=sheetChanged)

    # ---- Plex sign-in (File → Account) ---------------------------------------------------
    pinChanged = Signal()

    def _get_pin(self):
        return getattr(self, "_pin_code", "")

    pin = Property(str, _get_pin, notify=pinChanged)

    @Slot()
    def signIn(self):
        def start():
            return self.plex.create_pin()
        self._work("pin", start)

    def _poll_pin(self, pin):
        self._pin_code = pin.get("code", "")
        self.pinChanged.emit()
        pin_id = pin["id"]

        def wait():
            for _ in range(300):
                time.sleep(2)
                p = self.plex.check_pin(pin_id)
                if p.get("authToken"):
                    return p["authToken"]
                if getattr(self, "_pin_code", "") != pin.get("code"):
                    return None
            return None
        self._work("token", wait)

    def _signed_in(self, token):
        self._pin_code = ""
        self.pinChanged.emit()
        if not token:
            return
        url = self.cfg["plexUrl"]

        def finish():
            if not self.plex.probe(url, token):
                raise PlexError(f"Signed in, but the server at {url} didn't answer")
            self.cfg["plexToken"] = token
            self.cfg["plexUser"] = self.plex.username(token)
            secs = self.plex.music_sections()
            return secs
        self._work("sections", finish)

    def _sections(self, secs):
        if not secs:
            self.toast.emit("No music library on that Plex server")
            return
        # The phone uses the "Music" library; prefer the one at Flacs if there are several.
        pick = next((s for s in secs if any("Flacs" in l for l in s["locations"])), secs[0])
        self.cfg["musicSection"] = pick["key"]
        self.cfg["sectionLocations"] = pick["locations"]
        config.save(self.cfg)
        self.toast.emit(f"Connected to Plex — {pick['title']}")
        self.statusChanged.emit()
        self.refresh()

    @Slot()
    def cancelSignIn(self):
        self._pin_code = ""
        self.pinChanged.emit()

    def _get_signed_in(self):
        return self.plex.signed_in and bool(self.cfg.get("musicSection"))

    signedIn = Property(bool, _get_signed_in, notify=statusChanged)

