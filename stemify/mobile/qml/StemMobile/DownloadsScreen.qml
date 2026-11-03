import QtQuick

Page {
    id: page
    title: "Downloads"
    readonly property var sorts: ["Download Order", "Artist", "Release Date"]
    readonly property int sort: app.settings.downloadSort || 0
    // Download order and release date list newest first and an artist's albums oldest first, unless reversed.
    function newestFirst(s, reversed) { return (s !== 1) !== !!reversed }
    readonly property bool newest: newestFirst(sort, app.settings.downloadReversed)
    // Release days come from the album list, checked against MusicBrainz (Plex has some wrong).
    Fetch { id: infoFetch; name: "albumDays"; deps: app.settings.musicSection; active: app.settings.signedIn }
    readonly property var infoByKey: { var m = {}; (infoFetch.value || []).forEach(a => m[a.key] = a); return m }

    // Downloaded songs gathered back into their albums.
    readonly property var albums: {
        var groups = {}, order = []
        downloads.tracks.forEach(function (t) {
            var k = t.albumKey || ("name:" + t.artist + "|" + t.album)
            if (!groups[k]) { groups[k] = []; order.push(k) }
            groups[k].push(t)
        })
        var out = order.map(function (k) {
            var rs = groups[k], t = rs[0], known = page.infoByKey[k]
            var artists = {}; rs.forEach(r => artists[r.artist] = 1)
            var album = known || { key: k, title: t.album || "Unknown Album", artist: Object.keys(artists).length > 1 ? "Various Artists" : t.artist,
                                   artistKey: t.artistKey, year: t.year, thumb: t.thumb }
            var tr = rs.slice().sort((a, b) => (a.disc || 0) - (b.disc || 0) || (a.index || 0) - (b.index || 0) || a.title.localeCompare(b.title))
            return { album: album, tracks: tr, added: Math.max.apply(null, rs.map(r => r.added)),
                     done: rs.filter(r => r.done).length, failed: rs.filter(r => r.error).length,
                     released: known && (known.firstRelease || known.released) ? (known.firstRelease || known.released) : String((known && known.year) || t.year || "") }
        })
        function artistKey(s) { return (s || "").trim().toLowerCase().replace(/^the /, "") }
        var dir = newest ? -1 : 1
        function date(a, b) { return dir * a.released.localeCompare(b.released) }
        if (sort === 1) out.sort((a, b) => artistKey(a.album.artist).localeCompare(artistKey(b.album.artist)) || date(a, b) || a.album.title.localeCompare(b.album.title))
        else if (sort === 2) out.sort((a, b) => date(a, b) || a.album.title.localeCompare(b.album.title))
        else out.sort((a, b) => dir * (a.added - b.added))
        return out
    }
    readonly property var byKey: { var m = {}; albums.forEach(d => m[d.album.key] = d); return m }
    readonly property var allDone: { var o = []; albums.forEach(d => d.tracks.forEach(t => { if (t.done) o.push(t) })); return o }

    trailing: [
        TextButton { visible: page.albums.length > 0; text: "Sort"; tap: () => Ui.actions("Sort By", page.sorts.map((s, i) => ({ title: (i === page.sort ? "✓ " : "") + s, run: () => app.set("downloadSort", i) }))
            .concat([true, false].map(n => ({ title: (n === page.newest ? "✓ " : "") + (n ? "Newest First" : "Oldest First"),
                                              run: () => app.set("downloadReversed", n !== page.newestFirst(page.sort, false)) })))) },
        IconButton {
            visible: page.albums.length > 0
            icon: "ellipsis"
            tap: () => {
                var acts = []
                if (page.albums.some(d => d.failed > 0)) acts.push({ title: "Retry Failed", run: () => downloads.retryFailed() })
                acts.push({ title: "Remove All Downloads", destructive: true, run: () => Ui.confirm("Remove All Downloads?",
                    "Frees " + Api.gb(downloads.usage.pinned) + ". Songs stay in your library.", "Remove All", () => downloads.remove(downloads.tracks)) })
                Ui.actions("Downloads", acts)
            }
        }
    ]
    header: Column {
        width: page.width
        EmptyState {
            visible: page.albums.length === 0
            icon: "download"; title: "Nothing Downloaded"
            message: "Hold or right-click any album, artist, playlist or song and choose Download to play it, stems and all, without your server."
        }
        Column {
            visible: page.albums.length > 0
            width: parent.width
            bottomPadding: 14
            Txt {
                x: Ios.margin; width: parent.width - 2 * Ios.margin; bottomPadding: 12
                text: page.albums.length + " album" + (page.albums.length === 1 ? "" : "s") + " · " + Api.songs(downloads.tracks.length) + " · "
                      + Api.gb(downloads.usage.pinned) + " · by " + page.sorts[page.sort].toLowerCase()
                style: "subhead"; color: Ios.label2
            }
            PlayShuffle { play: () => player.playTracks(page.allDone, 0, false); shuffle: () => player.playTracks(page.allDone, -1, true) }
        }
        AlbumGrid {
            albums: page.albums.map(d => d.album)
            tap: (a) => { var d = page.byKey[a.key]; if (String(a.key).startsWith("name:")) player.playTracks(d.tracks, 0, false); else Ui.push("AlbumScreen", { album: a }) }
            hold: (a) => {
                var d = page.byKey[a.key], extra = []
                if (d.failed > 0) extra.push({ title: "Retry Failed Songs", run: () => downloads.retryFailed() })
                if (d.done < d.tracks.length) extra.push({ title: "Cancel Download", destructive: true, run: () => downloads.remove(d.tracks.filter(t => !t.done)) })
                if (String(a.key).startsWith("name:")) Media.tracks(a.title + " — " + a.artist, d.tracks, extra); else Media.album(a, extra)
            }
            subtitle: (a) => {
                var d = page.byKey[a.key]
                if (d.failed > 0) return a.artist + " · " + d.failed + " failed"
                if (d.done < d.tracks.length) return a.artist + " · " + d.done + " of " + d.tracks.length
                var when = Api.releaseStamp(d.released)
                return when ? a.artist + " · " + when : a.artist
            }
            badge: (a) => {
                var d = page.byKey[a.key]
                if (d.done >= d.tracks.length) return null
                if (d.failed > 0) return { failed: true }
                var inFlight = 0, pm = downloads.progressMap
                d.tracks.forEach(t => inFlight += pm[t.key] || 0)
                return { progress: (d.done + inFlight) / d.tracks.length }
            }
        }
    }
}
