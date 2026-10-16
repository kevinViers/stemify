import QtQuick

Item {
    id: b
    property string text
    property bool bold: false
    property color color: Ios.accent
    property var tap: null
    implicitWidth: t.implicitWidth + 8
    height: 44
    opacity: enabled ? 1 : 0.35
    Txt { id: t; anchors.centerIn: parent; text: b.text; color: b.color; weight: b.bold ? 600 : -1 }
    Pressable { anchors.fill: parent; fade: true; tap: b.tap }
}
