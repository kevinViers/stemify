import QtQuick

// A vertical UISlider for the EQ: centre-detented, value in -1..1. Double-click resets it.
Item {
    id: s
    property real value: 0
    property var moved: null
    property color fill: Ios.accent
    width: 32
    readonly property real t: 24
    readonly property real t0: t / 2
    readonly property real t1: height - t / 2
    readonly property real mid: (t0 + t1) / 2
    readonly property real y0: mid - (t1 - t0) / 2 * value
    function set(v) {
        v = Math.max(-1, Math.min(1, v))
        if (Math.abs(v) < 0.04) v = 0
        if (moved) moved(v)
    }
    Rectangle { x: s.width / 2 - 2; y: s.t0; width: 4; height: s.t1 - s.t0; radius: 2; color: Ios.fillStrong }
    Rectangle { x: s.width / 2 - 2; y: Math.min(s.y0, s.mid); width: 4; height: Math.abs(s.y0 - s.mid); radius: 2; color: s.fill }
    Rectangle { x: s.width / 2 - 8; y: s.mid - 0.5; width: 16; height: 1; color: Ios.label3 }
    Rectangle {
        x: s.width / 2 - s.t / 2; y: s.y0 - s.t / 2; width: s.t; height: s.t; radius: s.t / 2
        color: "white"; border.width: Ios.thumbBorder.a > 0 ? 0.5 : 0; border.color: Ios.thumbBorder
    }
    MouseArea {
        preventStealing: true  // a scrolling list around it must not take the drag
        anchors.fill: parent
        cursorShape: Qt.SizeVerCursor
        property real startY: 0
        property real startV: 0
        onPressed: (e) => { startY = e.y; startV = s.value }
        onPositionChanged: (e) => { if (pressed) s.set(startV - 2 * (e.y - startY) / (s.height - s.t)) }
        onDoubleClicked: s.set(0)
        onWheel: (w) => s.set(s.value + w.angleDelta.y / 120 * (0.5 / 12))
    }
}
