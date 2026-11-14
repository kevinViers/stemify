"""Stemify Connect's wire format: constants, and tracks between the desktop's dicts and the
Android field names every device speaks. The protocol itself is in docs/CONNECT.md."""

import hashlib
import hmac
import json
import secrets
import socket
import uuid

VERSION = 1
BEACON_PORT = 47809
TCP_PORTS = (47810, 47811, 47812)  # tried in turn (easy firewall rules), then any free port
BT_UUID = "5f3a7c2e-8d1b-4b6a-9c0e-57656d696679"
BEACON_EVERY_MS = 3000
DEVICE_TTL_S = 10.0
PING_EVERY_MS = 5000
SILENCE_LIMIT_S = 20.0
QUEUE_BEHIND, QUEUE_AHEAD = 50, 1000
MAX_LINE = 16 << 20  # a whole-library playTracks is a few MB; anything bigger is garbage
HELLO_LINE = 64 << 10  # all a device gets to send before it has been let in
MAX_STRANGERS = 16  # connections that haven't been let in yet, at once

# desktop track dict key -> wire (Android) key, where they differ
_TO_WIRE = {"key": "ratingKey", "playlistItemID": "playlistItemId"}
_FROM_WIRE = {v: k for k, v in _TO_WIRE.items()}
WIRE_FIELDS = ("ratingKey", "title", "artist", "album", "albumKey", "artistKey", "index", "disc", "durationMs", "thumb",
               "partKey", "partId", "partFile", "partSize", "codec", "year", "playlistItemId", "stemKey")
_INTS = {"index", "disc", "durationMs", "partId", "partSize", "year", "playlistItemId"}


def track_to_wire(t):
    """A desktop track dict as a wire track; empty fields are left out."""
    out = {}
    for k, v in t.items():
        w = _TO_WIRE.get(k, k)
        if w in WIRE_FIELDS and v not in (None, ""):
            if w in _INTS:
                try:
                    v = int(v)
                except (TypeError, ValueError):
                    continue
                if v == 0 and w not in ("index", "disc"):
                    continue
            out[w] = v
    out.setdefault("ratingKey", "")
    out.setdefault("title", "")
    out.setdefault("artist", "")
    out.setdefault("album", "")
    return out


_TEXT = {"title", "artist", "album", "albumKey", "artistKey", "thumb", "partKey", "partFile", "codec", "stemKey"}


def track_from_wire(w):
    """A wire track as the desktop's dict (the fields the player and screens read).

    It comes from another device, so nothing in it is taken on trust: fields have the types
    the player expects, and the two that end up in requests to Plex (the art and the file)
    must be paths on the Plex server. Anything else could point this app, and its Plex token,
    at a server of the sender's choosing."""
    t = {"key": "", "title": "", "artist": "", "album": "", "albumKey": "", "artistKey": "", "year": 0, "index": 0,
         "disc": 0, "durationMs": 0, "thumb": "", "partFile": "", "partKey": "", "codec": ""}
    for k, v in (w.items() if isinstance(w, dict) else ()):
        if k not in WIRE_FIELDS or v is None:
            continue
        if k in _INTS:
            try:
                v = int(v)
            except (TypeError, ValueError, OverflowError):
                continue
        elif k in _TEXT:
            if not isinstance(v, (str, int)):
                continue
            v = str(v)
        t[_FROM_WIRE.get(k, k)] = v
    t["key"] = str(t["key"])
    if not plex_path(t["partKey"]):
        t["partKey"] = ""
    # Art may also be a cover file (two Stemify apps on one computer see the same folders).
    if not plex_path(t["thumb"]) and not t["thumb"].startswith("file:///"):
        t["thumb"] = ""
    return t


def plex_path(p):
    """Whether p is a path on the Plex server ("/library/..."), not a URL or a way off it."""
    return isinstance(p, str) and p.startswith("/") and not p.startswith("//") and "\\" not in p and "://" not in p


# ---- pairing ---------------------------------------------------------------------------------
# A receiver gives each Wi-Fi controller it approves a key of its own (in that first welcome).
# From then on the controller proves it holds the key by answering a challenge; the key itself
# is never sent again. Ids alone prove nothing: every device broadcasts its own.

def new_key():
    return secrets.token_hex(32)


def new_nonce():
    return secrets.token_hex(16)


def auth_mac(key, nonce, controller_id, receiver_id):
    """The answer to a challenge: HMAC-SHA256 of "nonce|controller id|receiver id", in hex."""
    return hmac.new(str(key).encode(), f"{nonce}|{controller_id}|{receiver_id}".encode(), hashlib.sha256).hexdigest()


def mac_matches(a, b):
    return isinstance(a, str) and isinstance(b, str) and hmac.compare_digest(a.encode(), b.encode())


def encode(msg):
    return (json.dumps(msg, separators=(",", ":"), ensure_ascii=False) + "\n").encode()


def device_name(app_label):
    host = socket.gethostname().split(".")[0] or "Computer"
    return f"{host} · {app_label}"


def ensure_id(cfg):
    if not cfg.get("connectId"):
        cfg["connectId"] = uuid.uuid4().hex
    return cfg["connectId"]
