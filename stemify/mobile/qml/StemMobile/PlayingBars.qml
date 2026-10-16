import QtQuick

// Animated "now playing" bars, in place of a track number or over the art.
Item {
    id: b
    property bool playing: false
    property color color: Ios.accent
    width: 16
    height: 16
    property real phase: 0
    NumberAnimation on phase { from: 0; to: 6.2832; duration: 1100; loops: Animation.Infinite; running: b.playing && b.visible }
    Repeater {
        model: 3
        Rectangle {
            readonly property real w: b.width / 4.5
            readonly property real h: b.playing ? 0.35 + 0.65 * Math.abs(Math.sin(b.phase + index * 1.9)) : 0.3 + index * 0.15
            x: index * w * 1.6 + w * 0.3
            y: b.height * (1 - h)
            width: w
            height: b.height * h
            radius: w / 3
            color: b.color
        }
    }
}
