import QtQuick

Page {
    id: page
    property var playlist: ({})
    property string name: playlist.title || ""
    Fetch { id: tracksFetch; name: "playlistTracks"; args: [page.playlist.key] }
    readonly property var list: tracksFetch.value || []
    property bool editing: false
    property bool saving: false
    property var items: []
    property var removed: []
    property int dragFrom: -1
    property real dragOffset: 0
    title: name
    largeTitle: false
    titleAfter: 300

    function commit() {
        saving = true
        Api.call("savePlaylist", [playlist.key, list, items, removed], function () {
            tracksFetch.refresh(false); page.saving = false; page.editing = false
        }, function (e) { Ui.hud("Couldn't save: " + e, "warning"); tracksFetch.refresh(false); page.saving = false; page.editing = false })
    }
    trailing: [
        Item { visible: page.saving; width: 44; height: 44; Spinner { anchors.centerIn: parent } },
        TextButton {
            visible: !page.playlist.smart && page.list.length > 0 && !page.saving
            text: page.editing ? "Done" : "Edit"; bold: page.editing
            tap: () => { if (page.editing) page.commit(); else { page.items = page.list.slice(); page.removed = []; page.editing = true } }
        },
        IconButton {
            visible: !page.editing
            icon: "ellipsis"
            tap: () => {
                var extra = []
                if (!page.playlist.smart) extra.push({ title: "Rename…", run: () => Ui.prompt("Rename Playlist", "", page.name, "Save", function (n) {
                    Api.call("renamePlaylist", [page.playlist.key, n], () => page.name = n, e => Ui.hud("Couldn't rename: " + e, "warning"))
                }) })
                extra.push({ title: "Delete Playlist", destructive: true, run: () => Ui.confirm("Delete “" + page.name + "”?", "This removes it from Plex for every app.", "Delete", function () {
                    Api.call("deletePlaylist", [page.playlist.key], () => Ui.pop(), e => Ui.hud("Couldn't delete: " + e, "warning"))
                }) })
                Media.playlist(Object.assign({}, page.playlist, { title: page.name, tracks: page.list.length }), extra)
            }
        }
    ]
    header: Column {
        width: page.width
        topPadding: 8
        bottomPadding: 16
        Artwork { anchors.horizontalCenter: parent.horizontalCenter; thumb: page.playlist.thumb || ""; size: Math.min(230, page.width - 80); corner: 10; placeholder: "noteList" }
        Item { width: 1; height: 14 }
        Txt { x: 32; width: parent.width - 64; horizontalAlignment: Text.AlignHCenter; text: page.name; style: "title2"; maximumLineCount: 2 }
        Txt {
            visible: page.list.length > 0
            width: parent.width; horizontalAlignment: Text.AlignHCenter
            text: Api.songs(page.list.length) + " · " + Api.minutes(Api.sum(page.list, "durationMs")); style: "subhead"; color: Ios.label2
        }
        Item { width: 1; height: 14 }
        PlayShuffle { visible: !page.editing; play: () => player.playTracks(page.list, 0, false); shuffle: () => player.playTracks(page.list, -1, true) }
        LoadState { fetch: tracksFetch }
    }
    model: editing ? items : list
    delegate: TrackRow {
        id: row
        required property var modelData
        required property int index
        track: modelData
        z: page.editing && page.dragFrom === index ? 2 : 0
        dragging: page.editing && page.dragFrom === index
        transform: Translate { y: row.dragging ? page.dragOffset : 0 }
        tap: page.editing ? null : () => player.playTracks(page.list, index, false)
        more: page.editing ? null : () => Media.show(modelData, page.playlist.smart ? [] : [{ title: "Remove from Playlist", destructive: true, run: () =>
            Api.call("removeFromPlaylist", [page.playlist.key, modelData.playlistItemID], () => tracksFetch.refresh(false), e => Ui.hud("Couldn't remove: " + e, "warning")) }])
        leading: Rectangle {
            visible: page.editing
            anchors.centerIn: parent
            width: 24; height: 24; radius: 12; color: Ios.red
            Rectangle { anchors.centerIn: parent; width: 11; height: 2; color: "white" }
            Pressable { anchors.fill: parent; tap: () => {
                var it = page.items.slice(); var t = it.splice(row.index, 1)[0]
                page.removed = page.removed.concat([t]); page.items = it
            } }
        }
        trailing: Item {
            visible: page.editing
            width: 44; height: 44
            Sym { anchors.centerIn: parent; name: "grip"; color: Ios.label3; size: 22 }
            MouseArea {
                anchors.fill: parent
                cursorShape: Qt.SizeVerCursor
                property real lastY: 0
                onPressed: (e) => { page.dragFrom = row.index; page.dragOffset = 0; lastY = mapToItem(page, e.x, e.y).y }
                onPositionChanged: (e) => {
                    if (!pressed) return
                    var y = mapToItem(page, e.x, e.y).y
                    page.dragOffset += y - lastY; lastY = y
                    var it = page.items, i = page.dragFrom, h = row.height
                    if (page.dragOffset > h / 2 && i < it.length - 1) { it = it.slice(); it.splice(i + 1, 0, it.splice(i, 1)[0]); page.dragFrom = i + 1; page.dragOffset -= h; page.items = it }
                    else if (page.dragOffset < -h / 2 && i > 0) { it = it.slice(); it.splice(i - 1, 0, it.splice(i, 1)[0]); page.dragFrom = i - 1; page.dragOffset += h; page.items = it }
                }
                onReleased: { page.dragFrom = -1; page.dragOffset = 0 }
            }
        }
    }
}
