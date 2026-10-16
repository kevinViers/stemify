import QtQuick
import QtQuick.Layouts

Rectangle {
    id: mp
    property var open: null
    readonly property var t: player.track
    height: 64
    color: Ios.bar
    Hairline { width: parent.width; color: Ios.barBorder }
    Pressable { anchors.fill: parent; tap: mp.open }
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 8
        spacing: 0
        Artwork { thumb: mp.t.thumb || ""; size: 46; corner: 6 }
        Column {
            Layout.fillWidth: true
            Layout.leftMargin: 12
            Txt { width: parent.width; text: mp.t.title || ""; style: "subhead"; weight: 500 }
            Txt { width: parent.width; text: mp.t.artist || ""; style: "footnote"; color: Ios.label2 }
        }
        Item {
            width: 48; height: 48
            Spinner { visible: player.buffering && player.playing; anchors.centerIn: parent; size: 22; color: Ios.label }
            Sym { visible: !(player.buffering && player.playing); anchors.centerIn: parent; name: player.playing ? "pause" : "play"; color: Ios.label; size: 28 }
            Pressable { anchors.fill: parent; fade: true; tap: () => player.toggle() }
        }
        IconButton { icon: "forward"; tint: Ios.label; size: 28; box: 48; tap: () => player.next() }
    }
    Rectangle {
        id: prog
        anchors.bottom: parent.bottom
        height: 2
        color: Ios.accent
        property real f: 0
        width: parent.width * f
        FrameAnimation { running: mp.visible && player.playing; onTriggered: prog.f = player.duration > 0 ? Math.min(1, player.now() / player.duration) : 0 }
        Timer { interval: 250; repeat: true; running: mp.visible && !player.playing; onTriggered: prog.f = player.duration > 0 ? Math.min(1, player.now() / player.duration) : 0 }
    }
}
