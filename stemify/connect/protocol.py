"""Stemify Connect's wire format: constants, and tracks between the desktop's dicts and the
Android field names every device speaks. The protocol itself is in docs/CONNECT.md."""

import json
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


def track_from_wire(w):
    """A wire track as the desktop's dict (the fields the player and screens read)."""
    t = {"key": "", "title": "", "artist": "", "album": "", "albumKey": "", "artistKey": "", "year": 0, "index": 0,
         "disc": 0, "durationMs": 0, "thumb": "", "partFile": "", "partKey": "", "codec": ""}
    for k, v in (w or {}).items():
        if k in WIRE_FIELDS and v is not None:
            t[_FROM_WIRE.get(k, k)] = v
    t["key"] = str(t["key"])
    return t


def encode(msg):
    return (json.dumps(msg, separators=(",", ":"), ensure_ascii=False) + "\n").encode()


def device_name(app_label):
    host = socket.gethostname().split(".")[0] or "Computer"
    return f"{host} · {app_label}"


def ensure_id(cfg):
    if not cfg.get("connectId"):
        cfg["connectId"] = uuid.uuid4().hex
    return cfg["connectId"]
