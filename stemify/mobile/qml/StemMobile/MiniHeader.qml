import QtQuick
import QtQuick.Layouts

RowLayout {
    id: h
    property var pal: Ios
    property var track: ({})
    property var more: null
    x: 24
    width: parent ? parent.width - 48 : 0
    height: 84
    spacing: 14
    Artwork { thumb: h.track.thumb || ""; size: 64; corner: 8; pal: h.pal }
    Column {
        Layout.fillWidth: true
        Txt { width: parent.width; text: h.track.title || ""; style: "headline"; pal: h.pal }
        Txt { width: parent.width; text: h.track.artist || ""; style: "subhead"; color: h.pal.label2 }
    }
    Rectangle {
        width: 32; height: 32; radius: 16; color: h.pal.fill
        Sym { anchors.centerIn: parent; name: "ellipsis"; color: h.pal.label; size: 18 }
        Pressable { anchors.fill: parent; fade: true; tap: h.more }
    }
}
