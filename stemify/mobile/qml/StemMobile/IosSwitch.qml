import QtQuick

// UISwitch: 51x31, green when on.
Rectangle {
    id: s
    property bool checked: false
    property var toggled: null
    property var pal: Ios
    width: 51
    height: 31
    radius: 15.5
    color: checked ? pal.green : pal.switchOff
    Behavior on color { ColorAnimation { duration: 200 } }
    Rectangle {
        x: 2 + (s.checked ? 20 : 0)
        y: 2
        width: 27
        height: 27
        radius: 13.5
        color: "white"
        border.width: s.pal.thumbBorder.a > 0 ? 0.5 : 0
        border.color: s.pal.thumbBorder
        Behavior on x { SpringAnimation { spring: 6; damping: 0.4 } }
    }
    Pressable { anchors.fill: parent; tap: () => { if (s.toggled) s.toggled(!s.checked) } }
}
