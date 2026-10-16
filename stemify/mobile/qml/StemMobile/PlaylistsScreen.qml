import QtQuick

Page {
    id: page
    title: "Playlists"
    Fetch { id: listsFetch; name: "playlists"; deps: app.settings.musicSection }
    onVisibleChanged: if (visible && listsFetch.value !== null) listsFetch.refresh(false)
    trailing: IconButton {
        icon: "plus"
        // Plex can't hold an empty playlist, so new ones start from songs you have.
        tap: () => {
            var acts = [], q = player.upcoming, now = player.track
            var all = now.key ? [now].concat(q) : q
            if (all.length) acts.push({ title: "New Playlist from Queue (" + all.length + ")", run: () => Media.newPlaylist(all, () => listsFetch.refresh(false)) })
            if (now.key) acts.push({ title: "New Playlist with “" + now.title + "”", run: () => Media.newPlaylist([now], () => listsFetch.refresh(false)) })
            if (!acts.length) Ui.hud("Play something first, or use “Add to Playlist” on any song.", "info")
            else Ui.actions("New Playlist", acts)
        }
    }
    header: Column {
        width: page.width
        LoadState { fetch: listsFetch }
        EmptyState {
            visible: listsFetch.value !== null && listsFetch.value.length === 0
            icon: "noteList"; title: "No Playlists"
            message: "Hold or right-click any song, album, artist or playlist and choose “Add to Playlist”, or + to make one from your queue. They sync with Plexamp."
        }
    }
    model: listsFetch.value || []
    delegate: PlaylistRow {
        required property var modelData
        playlist: modelData
        tap: () => Ui.push("PlaylistScreen", { playlist: modelData })
        hold: () => Media.playlist(modelData)
    }
}
