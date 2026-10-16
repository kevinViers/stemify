import QtQuick

// UIActivityIndicatorView: eight spokes stepping round.
Item {
    id: s
    property real size: 20
    property color color: Ios.label2
    width: size
    height: size
    property int step: 0
    Timer { interval: 100; repeat: true; running: s.visible; onTriggered: s.step = (s.step + 1) % 8 }
    Repeater {
        model: 8
        Rectangle {
            readonly property real a: 1 - ((s.step - index + 8) % 8) / 8
            width: s.size * 0.09; height: s.size * 0.24; radius: width / 2
            x: s.size / 2 - width / 2; y: s.size * 0.04
            color: s.color
            opacity: 0.25 + 0.75 * a
            transform: Rotation { origin.x: width / 2; origin.y: s.size / 2 - s.size * 0.04; angle: index * 45 }
        }
    }
}
