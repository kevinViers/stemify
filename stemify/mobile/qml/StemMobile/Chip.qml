import QtQuick

// Capsule chip, accent-filled when selected.
Rectangle {
    id: c
    property string text
    property bool selected: false
    property var tap: null
    property var pal: Ios
    height: 32
    width: t.implicitWidth + 28
    radius: 16
    color: selected ? pal.accent : pal.fill
    Txt { id: t; anchors.centerIn: parent; text: c.text; style: "footnote"; weight: 600; color: c.selected ? "white" : c.pal.label }
    Pressable { anchors.fill: parent; fade: true; tap: c.tap }
}
