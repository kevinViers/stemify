import QtQuick

Rectangle {
    id: t
    property string icon
    property bool on: false
    property var tap: null
    property var pal: Ios
    width: 40
    height: 40
    radius: 10
    color: on ? pal.label : "transparent"
    Sym { anchors.centerIn: parent; name: t.icon; color: t.on ? t.pal.bg : t.pal.label2; size: 22 }
    Pressable { anchors.fill: parent; fade: true; tap: t.tap }
}
