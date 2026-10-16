import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

Column {
    id: q
    property var pal: Ios
    readonly property var upcoming: player.upcoming
    readonly property int base: player.queueInfo.index + 1
    property int dragFrom: -1
    property real dragOffset: 0
    // One placeholder row per upcoming song; rows read their song by index. Reordering moves rows
    // instead of rebuilding the list, so the row under the pointer keeps its drag.
    ListModel { id: rows }
    function sync() {
        if (dragFrom >= 0) return
        while (rows.count > upcoming.length) rows.remove(rows.count - 1)
        while (rows.count < upcoming.length) rows.append({})
    }
    onUpcomingChanged: sync()
    Component.onCompleted: sync()

    RowLayout {
        x: 24
        width: q.width - 24 - 16
        height: 52
        spacing: 8
        Column {
            Layout.fillWidth: true
            Txt { text: "Playing Next"; style: "headline"; pal: q.pal }
            Txt { text: Api.songs(q.upcoming.length); style: "footnote"; color: q.pal.label2 }
        }
        QueueToggle { icon: "shuffle"; on: player.shuffleOn; pal: q.pal; tap: () => player.toggleShuffle() }
        QueueToggle { icon: player.repeatMode === "one" ? "repeatOne" : "repeat"; on: player.repeatMode !== "off"; pal: q.pal; tap: () => player.cycleRepeat() }
        TextButton { visible: q.upcoming.length > 0; text: "Clear"; color: q.pal.label2; tap: () => player.clearUpNext() }
    }
    ListView {
        id: list
        width: q.width
        height: q.height - 52
        clip: true
        model: rows
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: ScrollBar { width: 6; contentItem: Rectangle { implicitWidth: 3; radius: 1.5; color: q.pal.label3; opacity: parent.active ? 0.9 : 0 } }
        delegate: Item {
            id: row
            required property int index
            readonly property var modelData: q.upcoming[index] || ({})
            readonly property bool dragging: q.dragFrom === index
            width: list.width
            height: 60
            z: dragging ? 2 : 0
            transform: Translate { y: row.dragging ? q.dragOffset : 0 }
            Rectangle { anchors.fill: parent; color: row.dragging || area.pressed ? q.pal.fill : "transparent" }
            Pressable {
                id: area
                anchors.fill: parent
                tap: () => player.skipTo(q.base + row.index)
                hold: () => Ui.actions(row.modelData.title, [
                    { title: "Play Next", run: () => player.moveItem(q.base + row.index, q.base) },
                    { title: "Remove from Queue", destructive: true, run: () => player.removeAt(q.base + row.index) }])
            }
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 24
                anchors.rightMargin: 8
                spacing: 12
                Artwork { thumb: row.modelData.thumb || row.modelData._cover || ""; size: 44; corner: 5; pal: q.pal }
                Column {
                    Layout.fillWidth: true
                    Txt { width: parent.width; text: row.modelData.title || ""; pal: q.pal }
                    Txt { width: parent.width; text: row.modelData.artist || ""; style: "subhead"; color: q.pal.label2 }
                }
                Item {
                    width: 44; height: 44
                    Sym { anchors.centerIn: parent; name: "grip"; color: q.pal.label3; size: 22 }
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.SizeVerCursor
                        preventStealing: true
                        property real lastY: 0
                        onPressed: (e) => { q.dragFrom = row.index; q.dragOffset = 0; lastY = mapToItem(q, e.x, e.y).y }
                        onPositionChanged: (e) => {
                            if (!pressed) return
                            var y = mapToItem(q, e.x, e.y).y
                            q.dragOffset += y - lastY; lastY = y
                            var i = q.dragFrom
                            if (q.dragOffset > 30 && i < q.upcoming.length - 1) { rows.move(i, i + 1, 1); q.dragFrom = i + 1; q.dragOffset -= 60; player.moveItem(q.base + i, q.base + i + 1) }
                            else if (q.dragOffset < -30 && i > 0) { rows.move(i, i - 1, 1); q.dragFrom = i - 1; q.dragOffset += 60; player.moveItem(q.base + i, q.base + i - 1) }
                        }
                        onReleased: { q.dragFrom = -1; q.dragOffset = 0; q.sync() }
                    }
                }
            }
        }
        footer: Txt {
            visible: q.upcoming.length === 0
            x: 24; y: 24; width: list.width - 48
            text: "Nothing queued. Use Add to Front of Queue or Add to End of Queue on any song."
            style: "subhead"; color: q.pal.label2; maximumLineCount: 3
        }
    }
}
