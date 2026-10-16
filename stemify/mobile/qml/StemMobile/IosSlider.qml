import QtQuick

// UISlider: 4 px track, white thumb. `detent` snaps near a value (unity gain on stem faders).
// Drag the thumb, click to jump, or scroll.
Item {
    id: s
    property real value: 0
    property var moved: null      // (v) => …
    property var released: null
    property color fill: pal.accent
    property color track: pal.fillStrong
    property var pal: Ios
    property real detent: -1
    property real thumb: 28
    implicitHeight: Math.max(thumb, 28) + 8
    height: implicitHeight
    opacity: enabled ? 1 : 0.4

    readonly property real usable: width - thumb
    function snap(v) { return detent >= 0 && Math.abs(v - detent) < 0.025 ? detent : v }
    function set(v) { if (moved) moved(snap(Math.max(0, Math.min(1, v)))) }

    Rectangle {
        x: s.thumb / 2; width: s.usable; height: 4; radius: 2
        anchors.verticalCenter: parent.verticalCenter
        color: s.track
    }
    Rectangle {
        x: s.thumb / 2; width: s.usable * Math.max(0, Math.min(1, s.value)); height: 4; radius: 2
        anchors.verticalCenter: parent.verticalCenter
        color: s.fill
    }
    Rectangle {
        visible: s.detent >= 0
        x: s.thumb / 2 + s.usable * s.detent - 0.75; width: 1.5; height: 14; radius: 0.75
        anchors.verticalCenter: parent.verticalCenter
        color: s.pal.label3
    }
    Rectangle {
        id: knob
        x: s.usable * Math.max(0, Math.min(1, s.value))
        anchors.verticalCenter: parent.verticalCenter
        width: s.thumb; height: s.thumb; radius: s.thumb / 2
        color: "white"
        border.width: s.pal.thumbBorder.a > 0 ? 0.5 : 0
        border.color: s.pal.thumbBorder
    }
    MouseArea {
        anchors.fill: parent
        enabled: s.enabled
        cursorShape: Qt.PointingHandCursor
        property real startX: 0
        property real startV: 0
        property bool dragging: false
        onPressed: (e) => {
            startX = e.x; startV = s.value
            dragging = Math.abs(e.x - (knob.x + s.thumb / 2)) <= s.thumb / 2 + 4
        }
        onPositionChanged: (e) => {
            if (!pressed) return
            if (!dragging && Math.abs(e.x - startX) > 4) { dragging = true; startV = (startX - s.thumb / 2) / s.usable }
            if (dragging) s.set(startV + (e.x - startX) / s.usable)
        }
        onReleased: (e) => {
            if (!dragging) s.set((e.x - s.thumb / 2) / s.usable)
            dragging = false
            if (s.released) s.released()
        }
        onWheel: (w) => s.set(s.value + (w.angleDelta.y || -w.angleDelta.x) / 120 * 0.02)
    }
}
