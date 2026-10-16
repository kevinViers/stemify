import QtQuick

// Apple Music's Play / Shuffle buttons: filled rounded rect, accent glyph and label.
Rectangle {
    id: b
    property string text
    property string icon
    property var tap: null
    height: 50
    radius: 12
    color: Ios.fill
    Row {
        anchors.centerIn: parent
        spacing: 8
        Sym { name: b.icon; color: Ios.accent; size: 20; anchors.verticalCenter: parent.verticalCenter }
        Txt { text: b.text; style: "headline"; color: Ios.accent; anchors.verticalCenter: parent.verticalCenter }
    }
    Pressable { anchors.fill: parent; fade: true; tap: b.tap }
}
