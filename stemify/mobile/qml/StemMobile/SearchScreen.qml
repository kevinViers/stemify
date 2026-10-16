import QtQuick

Page {
    id: page
    title: "Search"
    property string query: ""
    property var results: null
    property bool loading: false
    property string error: ""
    property int seq: 0
    function focusField() { if (view.headerItem) view.positionViewAtBeginning(); fieldFocus.restart() }
    Timer { id: fieldFocus; interval: 50; onTriggered: if (page.field) page.field.input.forceActiveFocus() }
    property var field: null
    Timer {
        id: debounce
        interval: 280
        onTriggered: {
            var q = page.query.trim()
            if (q.length < 2) { page.results = null; page.loading = false; return }
            page.loading = true
            var s = ++page.seq
            Api.call("search", [q], function (r) { if (s === page.seq) { page.results = r; page.loading = false; page.error = "" } },
                     function (e) { if (s === page.seq) { page.error = e; page.loading = false } })
        }
    }
    onQueryChanged: { if (query.trim().length < 2) { results = null; loading = false } debounce.restart() }
    readonly property bool empty: !!results && !results.artists.length && !results.albums.length && !results.tracks.length && !results.playlists.length

    header: Column {
        width: page.width
        SearchField {
            id: sf
            x: 16; width: parent.width - 32
            placeholder: "Artists, Albums, Songs"
            Component.onCompleted: page.field = sf
            onTextChanged: page.query = text
        }
        Item { width: 1; height: 8 }
        EmptyState { visible: page.query.trim().length < 2; icon: "search"; title: "Search Your Library"; message: "Find artists, albums, songs and playlists on your Plex server." }
        Item { visible: page.loading; width: parent.width; height: 106; Spinner { anchors.centerIn: parent; size: 26 } }
        EmptyState { visible: !page.loading && page.error !== "" && page.query.trim().length >= 2; icon: "warning"; title: "Couldn't Load"; message: page.error; action: "Try Again"; tap: () => debounce.restart() }
        EmptyState { visible: !page.loading && page.empty; icon: "search"; title: "No Results"; message: "Nothing matches “" + page.query.trim() + "”." }
        Column {
            width: parent.width
            visible: !page.loading && !!page.results
            SectionTitle { visible: !!page.results && page.results.artists.length > 0; text: "Artists" }
            Repeater {
                model: page.results ? page.results.artists : []
                ArtistRow { artist: modelData; tap: () => Ui.push("ArtistScreen", { artist: modelData }); hold: () => Media.artist(modelData) }
            }
            SectionTitle { visible: !!page.results && page.results.albums.length > 0; text: "Albums" }
            AlbumGrid { albums: page.results ? page.results.albums : []; tap: (a) => Ui.push("AlbumScreen", { album: a }); hold: (a) => Media.album(a) }
            SectionTitle { visible: !!page.results && page.results.tracks.length > 0; text: "Songs" }
            Repeater {
                model: page.results ? page.results.tracks : []
                TrackRow {
                    track: modelData
                    subtitle: modelData.artist + " — " + modelData.album
                    tap: () => player.playTracks(page.results.tracks, index, false)
                    more: () => Media.show(modelData)
                }
            }
            SectionTitle { visible: !!page.results && page.results.playlists.length > 0; text: "Playlists" }
            Repeater {
                model: page.results ? page.results.playlists : []
                PlaylistRow { playlist: modelData; tap: () => Ui.push("PlaylistScreen", { playlist: modelData }); hold: () => Media.playlist(modelData) }
            }
        }
    }
}
