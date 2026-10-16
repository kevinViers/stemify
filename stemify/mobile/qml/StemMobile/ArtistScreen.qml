import QtQuick

Page {
    id: page
    property var artist: ({})
    title: artist.title || ""
    Fetch { id: albumsFetch; name: "artistAlbums"; args: [page.artist.key] }
    function playAll(shuffle) {
        Api.call("artistTracks", [artist.key], t => player.playTracks(t, shuffle ? -1 : 0, shuffle), () => Ui.hud("Couldn't load songs", "warning"))
    }
    trailing: IconButton { icon: "ellipsis"; tap: () => Media.artist(page.artist) }
    header: Column {
        width: page.width
        Item { width: 1; height: 6 }
        PlayShuffle { play: () => page.playAll(false); shuffle: () => page.playAll(true) }
        Item { width: 1; height: 18 }
        LoadState { fetch: albumsFetch }
        AlbumGrid {
            albums: albumsFetch.value || []
            showArtist: false
            tap: (a) => Ui.push("AlbumScreen", { album: a })
            hold: (a) => Media.album(a, [], false)
        }
    }
}
