pragma Singleton
import QtQuick

// The long-press / right-click / ••• menu for anything playable: songs, albums, artists and
// playlists all offer the front or end of the queue, download, and add-to-playlist.
QtObject {
    function _loader(kind, key) {
        return function (then) {
            Api.call("tracksOf", [kind, key], function (list) {
                if (!list.length) Ui.hud("Nothing to add", "info"); else then(list)
            }, function (e) { Ui.hud("Couldn't load songs: " + e, "warning") })
        }
    }
    function _have(list) { return function (then) { then(list) } }

    function queueActions(load) {
        return [
            { title: "Add to Front of Queue", run: () => load(function (t) { player.playNextList(t); Ui.hud("Playing Next", "playNext") }) },
            { title: "Add to End of Queue", run: () => load(function (t) { player.addList(t); Ui.hud("Added to Queue", "addQueue") }) }
        ]
    }

    function stemNow(load) {
        return { title: "Stem Now", run: () => load(function (t) {
            Api.call("stemNow", [t], function (n) {
                if (n === 0) Ui.hud("Already has stems", "info")
                else Ui.hud(n === 1 ? "Stemming next" : "Stemming " + Api.songs(n) + " next", "waveform")
            }, function (e) { Ui.hud("Couldn't queue: " + e, "warning") })
        }) }
    }

    function download(t) { downloads.download(t); Ui.hud("Downloading", "download") }

    function show(track, extra, allowGoTo) {
        var go = allowGoTo === undefined ? true : allowGoTo
        var dl = downloads.states[track.key]
        var acts = queueActions(_have([track]))
        if (!dl) acts.push({ title: "Download", run: () => download([track]) })
        else acts.push({ title: "Remove Download", destructive: true, run: () => downloads.remove([track]) })
        acts.push({ title: "Add to Playlist…", run: () => addToPlaylist([track]) })
        if (track.partFile && app.indexLoaded() && !app.hasStems(track)) acts.push(stemNow(_have([track])))
        if (go && track.albumKey)
            acts.push({ title: "Go to Album", run: () => Ui.showInLibrary("AlbumScreen", { album: { key: track.albumKey, title: track.album, artist: track.artist,
                                                                                               artistKey: track.artistKey, year: track.year, thumb: track.thumb } }) })
        if (go && track.artistKey)
            acts.push({ title: "Go to Artist", run: () => Ui.showInLibrary("ArtistScreen", { artist: { key: track.artistKey, title: track.artist } }) })
        Ui.actions(track.title + " — " + track.artist, acts.concat(extra || []))
    }

    function _downloaded(field, key) {
        var out = [], list = downloads.tracks
        for (var i = 0; i < list.length; i++) if (list[i].done && list[i][field] === key) out.push(list[i])
        return out
    }
    function _stemmed(cov) { return app.coverageReady && !!cov && cov[1] >= cov[0] }

    function album(a, extra, allowGoTo) {
        var have = _downloaded("albumKey", a.key)
        var cov = app.albumCoverage[a.key]
        var total = cov ? cov[0] : a.tracks || 1e9
        var more = []
        if ((allowGoTo === undefined || allowGoTo) && a.artistKey)
            more.push({ title: "Go to Artist", run: () => Ui.showInLibrary("ArtistScreen", { artist: { key: a.artistKey, title: a.artist } }) })
        collection(a.title + " — " + a.artist, have, have.length >= total, _loader("album", a.key), "", more.concat(extra || []), false, _stemmed(cov))
    }

    function artist(a) {
        var have = _downloaded("artistKey", a.key)
        var cov = app.artistCoverage[a.key]
        collection(a.title, have, have.length >= (cov ? cov[0] : 1e9), _loader("artist", a.key), "", [], true, _stemmed(cov))
    }

    function playlist(p, extra) {
        collection(p.title, [], false, _loader("playlist", p.key), p.key, extra || [], (p.tracks || 0) > 50, false)
    }

    // For songs already in hand (downloads with no Plex album behind them).
    function tracks(title, list, extra) {
        var have = list.filter(t => downloads.isDownloaded(t.key))
        var stemmed = app.indexLoaded() && list.every(t => !t.partFile || app.hasStems(t))
        collection(title, have, have.length === list.length, _have(list), "", extra || [], false, stemmed)
    }

    function collection(title, downloaded, allDownloaded, load, playlistKey, extra, confirmDownload, stemmed) {
        var acts = queueActions(load)
        if (!allDownloaded) acts.push({ title: downloaded.length ? "Download the Rest" : "Download", run: () => load(function (t) {
            if (confirmDownload && t.length > 20)
                Ui.confirm("Download " + Api.songs(t.length) + "?", "“" + title + "” will play without your server, stems and all.", "Download", () => download(t), false)
            else download(t)
        }) })
        if (downloaded.length) acts.push({ title: "Remove Download", destructive: true, run: () =>
            Ui.confirm("Remove Download?", "“" + title + "” will no longer be available offline.", "Remove", () => downloads.remove(downloaded)) })
        acts.push({ title: "Add to Playlist…", run: () => load(function (t) { addToPlaylist(t, playlistKey) }) })
        if (!stemmed) acts.push(stemNow(load))
        Ui.actions(title, acts.concat(extra))
    }

    function addToPlaylist(list, skip) {
        Api.call("playlists", [], function (lists) {
            var keys = list.filter(t => !String(t.key).startsWith("stem:"))
            if (!keys.length) { Ui.hud("These songs aren't in Plex, so they can't go in a playlist", "info"); return }
            var acts = [{ title: "New Playlist…", run: () => newPlaylist(list) }]
            lists.filter(p => !p.smart && p.key !== skip).forEach(function (p) {
                acts.push({ title: p.title, run: () => Api.call("addToPlaylist", [p.key, keys],
                    () => Ui.hud("Added to " + p.title), e => Ui.hud("Couldn't add: " + e, "warning")) })
            })
            Ui.actions("Add " + Api.songs(keys.length) + " to Playlist", acts)
        }, () => Ui.hud("Couldn't load playlists", "warning"))
    }

    function newPlaylist(list, onCreated) {
        Ui.prompt("New Playlist", "Name it; you can add more songs later.", "", "Create", function (name) {
            Api.call("createPlaylist", [name, list], function () { Ui.hud("Created “" + name + "”"); if (onCreated) onCreated() },
                     e => Ui.hud("Couldn't create: " + e, "warning"))
        }, "Playlist name")
    }
}
