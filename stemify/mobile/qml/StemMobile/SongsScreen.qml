import QtQuick

// Every song, a page at a time as you scroll.
Page {
    id: page
    title: "Songs"
    property var all: []
    property int total: -1
    property bool loading: false
    property string error: ""
    ListModel { id: rows }
    function loadMore() {
        if (loading || (total >= 0 && all.length >= total)) return
        loading = true
        Api.call("tracksPage", [all.length, 200], function (r) {
            page.all = page.all.concat(r.tracks)
            page.total = r.total; page.error = ""; page.loading = false
            for (var i = 0; i < r.tracks.length; i++) rows.append({})
        }, function (e) { page.error = e; page.loading = false })
    }
    Component.onCompleted: loadMore()
    Connections {
        target: page.view
        function onContentYChanged() {
            if (page.view.indexAt(10, page.view.contentY + page.view.height) > page.all.length - 40) page.loadMore()
        }
    }
    header: Column {
        width: page.width
        visible: page.all.length > 0
        Txt { visible: page.total > 0; x: Ios.margin; bottomPadding: 12; text: Api.songs(page.total); style: "subhead"; color: Ios.label2 }
        PlayShuffle {
            play: () => player.playTracks(page.all.slice(0, 1000), 0, false)
            shuffle: () => player.playTracks(page.all.slice(0, 1000), -1, true)
        }
        Item { width: 1; height: 12 }
    }
    model: rows
    delegate: TrackRow {
        track: page.all[index]
        tap: () => { var from = Math.max(0, index - 200); player.playTracks(page.all.slice(from, index + 800), index - from, false) }
        more: () => Media.show(page.all[index])
    }
    footer: Column {
        width: page.width
        Item { width: 1; height: 106; visible: page.loading; Spinner { anchors.centerIn: parent; size: 26 } }
        EmptyState { visible: page.error !== ""; icon: "warning"; title: "Couldn't Load"; message: page.error; action: "Try Again"; tap: () => page.loadMore() }
    }
}
