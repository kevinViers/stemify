"""Stemify Connect, end to end, on one computer: a Stemify Mobile player as the receiver and the
Nearby controller finding it on the beacon, getting approved, and driving every feature over TCP.
Plays silence, with its own throwaway settings. Run: uv run python scripts/connect_selftest.py"""

import os
import sys
import tempfile
import time
import wave

S = tempfile.mkdtemp(prefix="stemify-connect-")
for k in ("XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME"):
    os.environ[k] = os.path.join(S, "xdg", k)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
silent = os.path.join(S, "silent.wav")
with wave.open(silent, "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100); w.writeframes(b"\0" * 44100 * 4 * 40)

from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlEngine
app = QGuiApplication(sys.argv)
from stemify.player import config
config.use("mobile", {"keepMix": True, "crossfade": "off"})
from stemify.player.engine import PlayerEngine
from stemify.mobile.playback import MobilePlayer
from stemify.mobile.eq import EqController
from stemify.connect.receiver import Receiver
from stemify.connect.controller import Nearby
from stemify.connect.discovery import Beacon

class FakePlex:
    def timeline(self, *a): pass
    def scrobble(self, *a): pass
    def art_url(self, *a): return ""
    def loudness(self, *a): return None
class Index: by_key = {}
class FakeStems:
    index = Index()
    def find(self, t): return {"key": "K/" + t["key"], "stems": [{"name": "vocals", "file": "v.flac"}, {"name": "drums", "file": "d.flac"}]}
    def peaks(self, entry, n): return {"vocals": [0.5] * n, "drums": [0.25] * n}
    def local_path(self, e, s): return silent
    def prepare(self, *a): pass
class FakeDownloads:
    def files(self, k): return None
    def peaks(self, k): return None

cfg = config.load(); cfg["keepMix"] = True
engine = PlayerEngine(); engine.setVolume(0.0)
rx = MobilePlayer(cfg, FakePlex(), FakeStems(), engine, FakeDownloads(), lambda: 0)
eq = EqController(os.path.join(S, "eq.json"), engine)
A = {"id": "AAA", "name": "test-receiver", "app": "mobile"}
receiver = Receiver(cfg, A, rx, engine, eq=eq, save=lambda: None)

qe = QQmlEngine(); ctx = qe.rootContext()
lengine = PlayerEngine(); lengine.setVolume(0.0)
local = MobilePlayer(dict(cfg), FakePlex(), FakeStems(), lengine, FakeDownloads(), lambda: 0)
leq = EqController(os.path.join(S, "eq2.json"), lengine)
ctx.setContextProperty("player", local)
B = {"id": "BBB", "name": "test-controller", "app": "mobile"}
nb = Nearby({"connectBluetooth": False}, B, Beacon(B), ctx, {"player": local, "engine": lengine, "eq": leq})

def wait(cond, secs=6.0, what=""):
    end = time.monotonic() + secs
    while time.monotonic() < end:
        app.processEvents(); time.sleep(0.01)
        if cond(): return True
    print(f"FAIL waiting for {what}")
    os._exit(1)

def ok(cond, what):
    print(("ok   " if cond else "FAIL ") + what, flush=True)
    if not cond:
        os._exit(1)

T = lambda i: {"key": str(100 + i), "title": f"Song {i}", "artist": "Artist", "album": "Album", "durationMs": 40000, "partFile": f"/m/{i}.flac", "partKey": f"/p/{i}"}
nb.scan(True)
wait(lambda: any(d["id"] == "AAA" for d in nb._get_devices()), what="discovery"); ok(True, "receiver discovered over the beacon")
nb.connectTo("AAA")
wait(lambda: nb.status == "waiting", what="pending"); ok(receiver._get_pending()[0]["id"] == "BBB", "Wi-Fi controller waits for approval")
receiver.approve("BBB", True)
wait(lambda: nb.status == "connected", what="connected")
rp, re_, req = nb.remote
ok(ctx.contextProperty("player") is rp and ctx.contextProperty("eq") is req, "screens now see the remote player and EQ")
rp.playTracks([T(i) for i in range(5)], 0, False)
wait(lambda: rp.st["playing"] and rp.st["source"] == "stems" and not rp.st["buffering"], what="remote playing")
ok(rp.track["title"] == "Song 0" and len(rp.upcoming) == 4, "playTracks: remote plays song 0 with 4 queued")
ok(rp.names == ["vocals", "drums"], "stem names arrive")
wait(lambda: rp._wave is not None, what="wave"); ok(len(rp._get_envelope()) > 0, "waveform arrives")
rp.setLevel(0, 0.5); wait(lambda: rx.mix.get("vocals", {}).get("level") == 0.5, what="level"); ok(True, "fader sets the receiver's vocals level")
rp.applyPreset(1); wait(lambda: rx.mix.get("vocals", {}).get("muted"), what="karaoke"); ok(rp._get_preset_label() == "Karaoke", "Karaoke preset")
rp.toggleSolo(1); wait(lambda: rx.mix.get("drums", {}).get("solo"), what="solo"); ok(True, "solo drums")
rp.seek(20.0); wait(lambda: abs(engine.position() - 20) < 1.5, what="seek"); ok(abs(rp.now() - 20) < 1.5, "seek to 0:20, and the controller agrees")
rp.skipBy(-10); wait(lambda: abs(engine.position() - 10) < 1.5, what="skipBy"); ok(True, "skip back 10 s")
rp.moveItem(4, 1); wait(lambda: [t["title"] for t in rx.queue][1] == "Song 4", what="move"); ok([t["title"] for t in rp.upcoming][0] == "Song 4", "drag Song 4 up to next")
rp.removeAt(2); wait(lambda: len(rx.queue) == 4, what="remove"); ok(len(rp.upcoming) == 3, "remove from queue")
rp.skipTo(1); wait(lambda: rx.index == 1 and rp.track["title"] == "Song 4", what="skipTo"); ok(True, "skipTo plays the tapped song")
rp.toggle(); wait(lambda: not engine.playing, what="pause"); ok(not rp.playing, "pause")
rp.toggle(); wait(lambda: engine.playing, what="play"); ok(rp.playing, "play")
rp.toggleShuffle(); wait(lambda: rx.shuffle, what="shuffle"); rp.cycleRepeat(); wait(lambda: rx.repeat == "all", what="repeat"); ok(True, "shuffle and repeat")
re_.setVolume(0.02); wait(lambda: abs(engine.volume - 0.02) < 1e-3, what="volume"); ok(abs(re_.volume - 0.02) < 1e-3, "master volume")
rp.setCrossfade("fixed", 8); wait(lambda: cfg.get("crossfade") == "fixed" and cfg.get("crossfadeSeconds") == 8, what="xfade"); ok(rp.crossfade == "fixed", "crossfade fixed 8 s")
rp.setKeepMix(False); wait(lambda: cfg.get("keepMix") is False, what="keepMix"); ok(True, "keep mix off")
pid = req.state["profiles"][0]["id"]
req.setGain(pid, 0, 4.5); req.putFilter(pid, {"id": "f1", "type": "lowshelf", "freq": 90, "gain": 3, "q": 0.7, "slope": 12, "channel": "left", "on": True})
wait(lambda: eq._by_id(pid)["gains"][0] == 4.5 and eq._by_id(pid)["filters"], what="eq"); ok(eq._by_id(pid)["filters"][0]["channel"] == "left", "EQ gain and a parametric filter reach the receiver")
req.setEnabled(False); wait(lambda: not eq.state["enabled"], what="eq off"); ok(True, "EQ off")
ok(re_.levels() is not None, "meters stream")
rp.clearUpNext(); wait(lambda: len(rx.queue) == rx.index + 1, what="clear"); ok(rp.upcoming == [], "clear up next")
# moving local playback over
local.play_tracks([T(7), T(8)], 0)
wait(lambda: local.engine.duration > 0, what="local load"); local.seek(15.0); wait(lambda: abs(local.engine.position() - 15) < 1.5, what="local seek")
nb.transfer(); wait(lambda: rx.current()["title"] == "Song 7" and abs(engine.position() - 15) < 2, what="transfer")
ok(len(rx.queue) == 2, "Move Here: local queue now plays on the receiver at 0:15")
nb.leave(); wait(lambda: not receiver.sessions, what="leave"); ok(ctx.contextProperty("player") is local, "leaving restores the local player")
nb.connectTo("AAA"); wait(lambda: nb.status == "connected", what="reconnect"); ok(True, "an allowed device reconnects without asking")
receiver.forget("BBB"); wait(lambda: nb.status == "", what="forget"); ok(ctx.contextProperty("player") is local, "forgetting a device drops it")
receiver.setEnabled(False)
nb.scan(True)
for _ in range(1200):
    app.processEvents()
    time.sleep(0.01)
ok(not any(d["id"] == "AAA" for d in nb._get_devices()), "turning control off hides the receiver")
print("ALL OK")
os._exit(0)
