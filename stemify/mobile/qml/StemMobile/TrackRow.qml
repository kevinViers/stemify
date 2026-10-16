import QtQuick
import QtQuick.Layouts

// Apple Music list row: art (or track number), title, artist, badges, and a ••• menu.
Item {
    id: r
    property var track: ({})
    property var tap: null
    property var more: null
    property int number: 0
    property bool showArt: true
    property bool showSubtitle: true
    property string subtitle: track ? track.artist || "" : ""
    property alias leading: lead.data
    property alias trailing: trail.data
    property bool dragging: false
    width: ListView.view ? ListView.view.width : parent ? parent.width : 0
    height: showArt ? 64 : 48

    readonly property bool isCurrent: !!track && player.track.key === track.key
    readonly property bool noStems: app.indexVersion >= 0 && !!track && !!track.key && app.indexLoaded() && !app.hasStems(track)
    readonly property var dl: track ? downloads.states[track.key] : undefined
    readonly property var prog: track ? downloads.progressMap[track.key] : undefined
    readonly property real textStart: (lead.children.length ? 44 : 0) + (showArt ? 68 : number ? 52 : Ios.margin)

    Rectangle { anchors.fill: parent; color: area.pressed || r.dragging ? Ios.cellPressed : "transparent" }
    Pressable { id: area; anchors.fill: parent; tap: r.tap; hold: r.more }
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: lead.children.length ? 8 : Ios.margin
        anchors.rightMargin: 6
        spacing: 0
        Item { id: lead; visible: children.length > 0; Layout.preferredWidth: 36; Layout.preferredHeight: 36; Layout.rightMargin: r.showArt ? 8 : 0 }
        Item {
            visible: r.showArt
            Layout.preferredWidth: 48; Layout.preferredHeight: 48; Layout.rightMargin: 12
            Artwork { thumb: r.track ? r.track.thumb || "" : ""; size: 48; corner: 5 }
            Rectangle {
                anchors.fill: parent; radius: 5; visible: r.isCurrent; color: "#59000000"
                PlayingBars { anchors.centerIn: parent; playing: player.playing; color: "white" }
            }
        }
        Item {
            visible: !r.showArt && r.number > 0
            Layout.preferredWidth: 32; Layout.preferredHeight: 22
            Txt { visible: !r.isCurrent; text: r.number; color: Ios.label2; anchors.verticalCenter: parent.verticalCenter }
            PlayingBars { visible: r.isCurrent; playing: player.playing; anchors.verticalCenter: parent.verticalCenter }
        }
        Column {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignVCenter
            Txt { width: parent.width; text: r.track ? r.track.title || "" : ""; color: r.isCurrent ? Ios.accent : Ios.label }
            Txt { visible: r.showSubtitle && r.subtitle !== ""; width: parent.width; text: r.subtitle; style: "subhead"; color: Ios.label2 }
        }
        Tag { visible: r.noStems; text: "NO STEMS"; Layout.leftMargin: 6 }
        ProgressRing { visible: r.prog !== undefined; progress: r.prog || 0; size: 16; Layout.leftMargin: 8 }
        DownloadedMark { visible: r.prog === undefined && !!r.dl && r.dl.done; Layout.leftMargin: 8 }
        Sym { visible: r.prog === undefined && !!r.dl && r.dl.error !== ""; name: "warning"; size: 16; color: Ios.label2; Layout.leftMargin: 8 }
        Row { id: trail; Layout.alignment: Qt.AlignVCenter }
        IconButton { visible: !!r.more; icon: "ellipsis"; tint: Ios.label; size: 20; box: 40; tap: r.more }
        Item { visible: !r.more; Layout.preferredWidth: 14; Layout.preferredHeight: 1 }
    }
    Hairline { x: r.textStart; width: r.width - r.textStart; anchors.bottom: parent.bottom }
}
