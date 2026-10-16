import QtQuick

Item {
    id: b
    property string icon
    property color tint: Ios.accent
    property real size: 24
    property real box: 44
    property var tap: null
    property var hold: null
    property string tip: ""
    width: box
    height: box
    opacity: enabled ? 1 : 0.35
    Sym { anchors.centerIn: parent; name: b.icon; color: b.tint; size: b.size }
    Pressable { anchors.fill: parent; fade: true; tap: b.tap; hold: b.hold }
}
