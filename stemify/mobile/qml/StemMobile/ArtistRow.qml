import QtQuick
import QtQuick.Layouts

Item {
    id: r
    property var artist: ({})
    property var tap: null
    property var hold: null
    width: ListView.view ? ListView.view.width : parent ? parent.width : 0
    height: 60
    Rectangle { anchors.fill: parent; color: area.pressed ? Ios.cellPressed : "transparent" }
    Pressable { id: area; anchors.fill: parent; tap: r.tap; hold: r.hold }
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: Ios.margin
        anchors.rightMargin: 16
        spacing: 14
        Artwork { thumb: r.artist.thumb || ""; size: 44; circle: true; placeholder: "person" }
        Txt { text: r.artist.title || ""; Layout.fillWidth: true }
        StemFlag { cov: app.artistCoverage[r.artist.key] }
        Chevron {}
    }
    Hairline { x: 78; width: r.width - 78; anchors.bottom: parent.bottom }
}
