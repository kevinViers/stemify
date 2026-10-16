import QtQuick

Row {
    id: r
    property var play: null
    property var shuffle: null
    x: Ios.margin
    width: parent ? parent.width - 2 * Ios.margin : 0
    spacing: 14
    PillButton { width: (r.width - 14) / 2; text: "Play"; icon: "play"; tap: r.play }
    PillButton { width: (r.width - 14) / 2; text: "Shuffle"; icon: "shuffle"; tap: r.shuffle }
}
