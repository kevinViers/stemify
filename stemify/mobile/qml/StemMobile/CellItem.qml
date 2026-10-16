import QtQuick

// One row of a grouped section: Section tells it whether it's first or last, so the pressed
// highlight follows the card's rounded corners and the separator stops before the last row.
Item {
    id: ci
    property bool first: true
    property bool last: true
    property real inset: 16
    property bool highlighted: false
    width: parent ? parent.width : 0
    Rectangle {
        anchors.fill: parent
        visible: ci.highlighted
        color: Ios.cellPressed
        topLeftRadius: ci.first ? 12 : 0
        topRightRadius: ci.first ? 12 : 0
        bottomLeftRadius: ci.last ? 12 : 0
        bottomRightRadius: ci.last ? 12 : 0
    }
    Hairline { visible: !ci.last; x: ci.inset; width: ci.width - ci.inset; anchors.bottom: parent.bottom; z: 5 }
}
