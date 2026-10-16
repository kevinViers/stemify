import QtQuick

Rectangle {
    id: t
    property string icon
    property bool on: false
    property var tap: null
    property var pal: Ios
    width: 36
    height: 30
    radius: 8
    color: on ? pal.label : pal.fill
    Sym { anchors.centerIn: parent; name: t.icon; color: t.on ? t.pal.bg : t.pal.label2; size: 18 }
    Pressable { anchors.fill: parent; fade: true; tap: t.tap }
}
