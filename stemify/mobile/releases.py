"""When albums first came out, from MusicBrainz, for "On This Day".

Plex's own album dates aren't safe for this. Its metadata agent sometimes has the wrong date
(Madvillainy as 2002-11-01, not 2004), and when it only knows a year or month it stores the 1st
(1978 becomes 1978-01-01), which turns into a birthday that never happened.

Plex does keep the MusicBrainz release each album's files were tagged with. That release may be
a reissue, so we ask for its release group's first release date: the earliest any edition of
the album came out. MusicBrainz takes about one request a second, so up to 40 releases share
one search, and answers are saved on disk so each album is looked up once.
"""

import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

API = "https://musicbrainz.org/ws/2"
USER_AGENT = "Stemify/1.1 (https://github.com/kevinViers/stemify)"
BATCH = 40
GAP = 1.1             # seconds between requests (MusicBrainz asks for at most one a second)
FRESH = 90 * 86400    # look a found date up again after this long, in case MusicBrainz improved it
RETRY_MISSING = 7 * 86400


class ReleaseDates:
    def __init__(self, path):
        self.path = path
        self._lock = threading.Lock()
        self._last = 0.0
        self._known = {}  # release id -> {"date": "2004-03-19" | "1982-06" | "1971" | "" (none), "at": unix time}
        try:
            with open(path) as f:
                self._known = json.load(f)
        except (OSError, ValueError):
            pass

    def resolve(self, mbids):
        """{release id: first release date} for the ones MusicBrainz has answered for ("" when it
        has no date). Looks up what isn't saved; ids it couldn't reach are left out."""
        ids = list(dict.fromkeys(i for i in mbids if i))
        with self._lock:
            now = time.time()
            due = [i for i in ids if self._stale(self._known.get(i), now)]
            if due:
                try:
                    self._lookup(due)
                except (urllib.error.URLError, TimeoutError, OSError, ValueError):
                    pass  # offline or MusicBrainz is down: use what's saved, try again next time
                self._save()
            return {i: self._known[i]["date"] for i in ids if i in self._known}

    @staticmethod
    def _stale(entry, now):
        if entry is None:
            return True
        return now - entry["at"] > (FRESH if entry["date"] else RETRY_MISSING)

    def _lookup(self, ids):
        missed = []
        for n in range(0, len(ids), BATCH):
            chunk = set(ids[n:n + BATCH])
            query = " OR ".join("reid:" + i for i in chunk)
            found = {}
            for group in self._get("/release-group", query=query, limit=100).get("release-groups", []):
                for r in group.get("releases", []):
                    if r.get("id") in chunk:
                        found[r["id"]] = group.get("first-release-date") or ""
            for i, d in found.items():
                self._known[i] = {"date": d, "at": time.time()}
            missed += chunk - found.keys()
        # Search lists a big release group's releases only in part; ask for those one by one.
        for i in missed:
            try:
                group = self._get(f"/release/{i}", inc="release-groups").get("release-group") or {}
                d = group.get("first-release-date") or ""
            except urllib.error.HTTPError as e:
                if e.code not in (400, 404):
                    raise
                d = ""  # not (or no longer) on MusicBrainz
            self._known[i] = {"date": d, "at": time.time()}

    def _get(self, path, **params):
        params["fmt"] = "json"
        url = API + path + "?" + urllib.parse.urlencode(params)
        for attempt in range(5):
            wait = self._last + GAP - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    return json.load(r)
            except urllib.error.HTTPError as e:
                if e.code != 503 or attempt == 4:
                    raise
                time.sleep(2 * (attempt + 1))  # rate limited: back off

    def _save(self):
        try:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self._known, f)
            os.replace(tmp, self.path)
        except OSError:
            pass


def release_day(album, mb):
    """The day [album] first came out as "YYYY-MM-DD", or "" when nobody knows the day.

    [mb] is resolve()'s answer. MusicBrainz's date wins; when it gives only a year or month, the
    day is unknown. Without a MusicBrainz answer, Plex's date counts unless it's on the 1st,
    which is what Plex stores when it doesn't know the day.
    """
    mbid = album.get("mbid") or ""
    if mbid in mb:
        d = mb[mbid]
        return d if _full(d) else ""
    d = (album.get("released") or "")[:10]
    return d if _full(d) and not d.endswith("-01") else ""


def _full(d):
    try:
        date.fromisoformat(d)
        return len(d) == 10
    except ValueError:
        return False
