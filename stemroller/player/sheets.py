"""The workbook: every view is a sheet (columns + rows) shown in the Excel grid."""

import time
import unicodedata

from PySide6.QtCore import QAbstractTableModel, QByteArray, QModelIndex, Qt

# Column formats: how a value is shown, aligned and summed in the status bar.
#   text, int, year, time (ms -> m:ss), stems (count of stems, shown TRUE/FALSE-ish), date (unix s)
COLUMNS = {
    "songs": [
        ("title", "Title", 300, "text"), ("artist", "Artist", 190, "text"), ("album", "Album", 230, "text"),
        ("year", "Year", 64, "year"), ("durationMs", "Time", 64, "time"), ("stems", "Stems", 70, "stems"),
        ("plays", "Plays", 64, "int"),
    ],
    "albumSongs": [
        ("index", "#", 44, "int"), ("title", "Title", 320, "text"), ("artist", "Artist", 200, "text"),
        ("durationMs", "Time", 64, "time"), ("stems", "Stems", 70, "stems"), ("plays", "Plays", 64, "int"),
    ],
    "artists": [
        ("title", "Artist", 280, "text"), ("albums", "Albums", 70, "int"), ("songs", "Songs", 70, "int"),
        ("stemSongs", "With stems", 90, "int"), ("genre", "Genre", 200, "text"),
    ],
    "albums": [
        ("title", "Album", 300, "text"), ("artist", "Artist", 200, "text"), ("year", "Year", 64, "year"),
        ("tracks", "Songs", 64, "int"), ("stemSongs", "With stems", 90, "int"), ("genre", "Genre", 160, "text"),
        ("addedAt", "Added", 96, "date"),
    ],
    "playlists": [
        ("title", "Playlist", 300, "text"), ("tracks", "Songs", 70, "int"), ("durationMs", "Length", 80, "time"),
        ("kind", "Type", 80, "text"), ("updatedAt", "Updated", 96, "date"),
    ],
    "queue": [
        ("pos", "#", 44, "int"), ("title", "Title", 300, "text"), ("artist", "Artist", 190, "text"),
        ("album", "Album", 230, "text"), ("durationMs", "Time", 64, "time"), ("stems", "Stems", 70, "stems"),
    ],
    "search": [
        ("type", "Type", 70, "text"), ("title", "Name", 300, "text"), ("artist", "Artist", 190, "text"),
        ("album", "Album", 230, "text"), ("year", "Year", 64, "year"), ("durationMs", "Time", 64, "time"),
        ("stems", "Stems", 70, "stems"),
    ],
}

# Plex row kind -> what a double-click does
SONG, ARTIST, ALBUM, PLAYLIST = "song", "artist", "album", "playlist"


def fold(s: str) -> str:
    """Case- and accent-insensitive form for search and sorting."""
    s = unicodedata.normalize("NFKD", s or "")
    return "".join(c for c in s if not unicodedata.combining(c)).casefold()


def fmt_time(ms) -> str:
    s = int(round((ms or 0) / 1000))
    h, m = divmod(s // 60, 60)
    return f"{h}:{m:02d}:{s % 60:02d}" if h else f"{m}:{s % 60:02d}"


def fmt(value, kind) -> str:
    if kind == "time":
        return fmt_time(value) if value else ""
    if kind == "year":
        return str(value) if value else ""
    if kind == "int":
        return "" if value in (None, "") else str(value)
    if kind == "stems":
        return f"{value}" if value else ""
    if kind == "date":
        return time.strftime("%Y-%m-%d", time.localtime(value)) if value else ""
    return "" if value is None else str(value)


class Sheet:
    """A tab: its rows, columns and what the rows are."""

    def __init__(self, title, columns, rows, kind=SONG, sid=None, loading=False):
        self.title = title
        self.columns = COLUMNS[columns]
        self.layout = columns
        self.rows = rows
        self.kind = kind
        self.sid = sid or title
        self.loading = loading
        self.sort = None  # (column, descending)

    def row_kind(self, r):
        return self.rows[r].get("_kind", self.kind)


class SheetModel(QAbstractTableModel):
    """One sheet for the QML TableView. Roles: display, align, style, playing."""

    ROLES = {Qt.ItemDataRole.DisplayRole: b"display", Qt.ItemDataRole.UserRole + 1: b"align",
             Qt.ItemDataRole.UserRole + 2: b"cellStyle", Qt.ItemDataRole.UserRole + 3: b"playing",
             Qt.ItemDataRole.UserRole + 4: b"bold"}

    def __init__(self, parent=None):
        super().__init__(parent)
        self.sheet: Sheet | None = None
        self.playing_key = ""
        self.playing_pos = -1  # for the queue sheet, where the same song can appear twice

    def set_sheet(self, sheet):
        self.beginResetModel()
        self.sheet = sheet
        self.endResetModel()

    def refresh(self):
        self.beginResetModel()
        self.endResetModel()

    def set_playing(self, key, pos):
        self.playing_key, self.playing_pos = key, pos
        if self.sheet and self.sheet.rows:
            self.dataChanged.emit(self.index(0, 0), self.index(len(self.sheet.rows) - 1, len(self.sheet.columns) - 1),
                                  [Qt.ItemDataRole.UserRole + 3, Qt.ItemDataRole.UserRole + 4])

    def roleNames(self):
        return {k: QByteArray(v) for k, v in self.ROLES.items()}

    def rowCount(self, parent=QModelIndex()):
        return len(self.sheet.rows) if self.sheet else 0

    def columnCount(self, parent=QModelIndex()):
        return len(self.sheet.columns) if self.sheet else 0

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        s = self.sheet
        if s is None or not index.isValid():
            return None
        row = s.rows[index.row()]
        key, _, _, kind = s.columns[index.column()]
        if role == Qt.ItemDataRole.DisplayRole:
            return fmt(row.get(key), kind)
        if role == Qt.ItemDataRole.UserRole + 1:
            return "left" if kind in ("text", "stems") else "right"
        if role == Qt.ItemDataRole.UserRole + 2:
            # Conditional formatting: "Good" for songs with stems, a data bar for stem counts.
            if kind == "stems" and row.get(key):
                return "good"
            if key == "stemSongs" and row.get(key):
                return "bar:%.3f" % (row[key] / max(1, row.get("tracks") or row.get("songs") or row[key]))
            return ""
        if role in (Qt.ItemDataRole.UserRole + 3, Qt.ItemDataRole.UserRole + 4):
            if s.layout == "queue":
                return row.get("pos") == self.playing_pos + 1
            return s.row_kind(index.row()) == SONG and row.get("key") == self.playing_key and bool(self.playing_key)
        return None
