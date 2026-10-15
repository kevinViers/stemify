"""MPRIS on the session bus: media keys, the desktop's media controls, and KDE Connect on the phone."""

import hashlib

from PySide6.QtCore import ClassInfo, Property, QObject, Slot
from PySide6.QtDBus import QDBusAbstractAdaptor, QDBusConnection, QDBusMessage, QDBusObjectPath

PATH = "/org/mpris/MediaPlayer2"
ROOT_IFACE = "org.mpris.MediaPlayer2"
PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"


@ClassInfo({"D-Bus Interface": ROOT_IFACE})
class _Root(QDBusAbstractAdaptor):
    def __init__(self, parent, window_raise, quit_):
        super().__init__(parent)
        self._raise, self._quit = window_raise, quit_

    @Slot()
    def Raise(self):
        self._raise()

    @Slot()
    def Quit(self):
        self._quit()

    CanQuit = Property(bool, lambda self: True)
    CanRaise = Property(bool, lambda self: True)
    HasTrackList = Property(bool, lambda self: False)
    Identity = Property(str, lambda self: "Stemify Player")
    DesktopEntry = Property(str, lambda self: "stemify-player")
    SupportedUriSchemes = Property("QStringList", lambda self: [])
    SupportedMimeTypes = Property("QStringList", lambda self: [])


@ClassInfo({"D-Bus Interface": PLAYER_IFACE})
class _Player(QDBusAbstractAdaptor):
    def __init__(self, parent, mpris):
        super().__init__(parent)
        self.m = mpris

    @Slot()
    def Next(self):
        self.m.player.next()

    @Slot()
    def Previous(self):
        self.m.player.previous()

    @Slot()
    def Pause(self):
        self.m.engine.pause()

    @Slot()
    def PlayPause(self):
        self.m.player.toggle()

    @Slot()
    def Stop(self):
        self.m.engine.pause()

    @Slot()
    def Play(self):
        if not self.m.engine.playing:
            self.m.player.toggle()

    @Slot("qlonglong")
    def Seek(self, offset_us):
        self.m.player.seek(self.m.engine.position() + offset_us / 1e6)

    @Slot(QDBusObjectPath, "qlonglong")
    def SetPosition(self, track_id, position_us):
        self.m.player.seek(position_us / 1e6)

    @Slot(str)
    def OpenUri(self, uri):
        pass

    def _status(self):
        s = self.m.player._get_status()
        return "Playing" if s in ("playing", "buffering") or (s == "loading" and self.m.engine.playing) else \
            "Paused" if self.m.player.current() else "Stopped"

    def _volume(self):
        return float(self.m.engine.volume)

    def _set_volume(self, v):
        self.m.engine.setVolume(max(0.0, min(1.0, v)))

    PlaybackStatus = Property(str, _status)
    LoopStatus = Property(str, lambda self: {"off": "None", "all": "Playlist", "one": "Track"}[self.m.player.repeat])
    Rate = Property(float, lambda self: 1.0)
    Shuffle = Property(bool, lambda self: self.m.player.shuffle)
    Metadata = Property("QVariantMap", lambda self: self.m.metadata())
    Volume = Property(float, _volume, _set_volume)
    Position = Property("qlonglong", lambda self: int(self.m.engine.position() * 1e6))
    MinimumRate = Property(float, lambda self: 1.0)
    MaximumRate = Property(float, lambda self: 1.0)
    CanGoNext = Property(bool, lambda self: bool(self.m.player.queue))
    CanGoPrevious = Property(bool, lambda self: bool(self.m.player.queue))
    CanPlay = Property(bool, lambda self: bool(self.m.player.queue))
    CanPause = Property(bool, lambda self: True)
    CanSeek = Property(bool, lambda self: True)
    CanControl = Property(bool, lambda self: True)


class Mpris(QObject):
    def __init__(self, player, engine, window_raise, quit_, parent=None):
        super().__init__(parent)
        self.player, self.engine = player, engine
        self._root = _Root(self, window_raise, quit_)
        self._adaptor = _Player(self, self)
        self.bus = QDBusConnection.sessionBus()
        self.ok = self.bus.isConnected() and self.bus.registerObject(PATH, self) and \
            self.bus.registerService("org.mpris.MediaPlayer2.stemify_player")
        self._last = {}
        player.changed.connect(self._changed)
        player.mixChanged.connect(self._changed)
        engine.volumeChanged.connect(self._changed)
        engine.playingChanged.connect(self._changed)

    def metadata(self):
        t = self.player.current()
        if not t:
            return {"mpris:trackid": QDBusObjectPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")}
        artist = t["artist"]
        label = self.player._get_preset_label()
        if label and label != "Full Mix":
            artist = f"{artist} · {label}"  # the mix rides on the artist line, as on the phone
        md = {
            "mpris:trackid": QDBusObjectPath("/stemify/track/t" + hashlib.sha1(t["key"].encode()).hexdigest()[:16]),
            "mpris:length": int((self.engine.duration or t.get("durationMs", 0) / 1000) * 1e6),
            "xesam:title": t["title"],
            "xesam:artist": [artist],
            "xesam:album": t["album"],
            "xesam:albumArtist": [t.get("albumArtist") or t["artist"]],
        }
        art = self.player.plex.art_url(t.get("thumb"), 600) or t.get("_cover", "")
        if art:
            md["mpris:artUrl"] = art
        if t.get("index"):
            md["xesam:trackNumber"] = t["index"]
        return md

    def _changed(self):
        if not self.ok:
            return
        now = {
            "PlaybackStatus": self._adaptor._status(),
            "LoopStatus": self._adaptor.LoopStatus,
            "Shuffle": self.player.shuffle,
            "Volume": float(self.engine.volume),
            "Metadata": self.metadata(),
            "CanGoNext": bool(self.player.queue),
            "CanGoPrevious": bool(self.player.queue),
            "CanPlay": bool(self.player.queue),
        }
        diff = {k: v for k, v in now.items() if k == "Metadata" and _md_key(v) != _md_key(self._last.get(k)) or
                k != "Metadata" and self._last.get(k) != v}
        self._last = now
        if not diff:
            return
        msg = QDBusMessage.createSignal(PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged")
        msg.setArguments([PLAYER_IFACE, diff, []])
        self.bus.send(msg)


def _md_key(md):
    if not md:
        return None
    return tuple((k, str(v.path()) if isinstance(v, QDBusObjectPath) else repr(v)) for k, v in sorted(md.items()))
