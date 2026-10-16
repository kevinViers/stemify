import QtQuick
import QtQuick.Layouts

Item {
    id: r
    property var playlist: ({})
    property var tap: null
    property var hold: null
    width: ListView.view ? ListView.view.width : parent ? parent.width : 0
    height: 76
    Rectangle { anchors.fill: parent; color: area.pressed ? Ios.cellPressed : "transparent" }
    Pressable { id: area; anchors.fill: parent; tap: r.tap; hold: r.hold }
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: Ios.margin
        anchors.rightMargin: 16
        spacing: 14
        Artwork { thumb: r.playlist.thumb || ""; size: 60; corner: 6; placeholder: "noteList" }
        Column {
            Layout.fillWidth: true
            Txt { width: parent.width; text: r.playlist.title || "" }
            Txt { width: parent.width; text: Api.songs(r.playlist.tracks || 0); style: "subhead"; color: Ios.label2 }
        }
        Tag { visible: !!r.playlist.smart; text: "SMART" }
        Chevron {}
    }
    Hairline { x: 94; width: r.width - 94; anchors.bottom: parent.bottom }
}
