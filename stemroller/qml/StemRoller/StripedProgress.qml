import QtQuick

// Win7-style green-to-cyan striped bar. Backend sends <= 30 updates/s;
// `shown` eases toward `value` and the stripes advance per frame by delta-time.
Item {
    id: root
    property real value: 0
    property bool active: true
    property real shown: 0
    property real phase: 0
    property int barHeight: 6
    implicitHeight: barHeight
    readonly property int tile: barHeight * 2

    onValueChanged: if (value < shown) shown = value

    Rectangle {
        anchors.fill: parent
        radius: height / 2
        color: Qt.rgba(1, 1, 1, 0.07)
        border.color: Qt.rgba(1, 1, 1, 0.05)
    }
    Item {
        id: fill
        height: parent.height
        width: Math.max(root.active ? root.barHeight * 2 : 0, parent.width * Math.min(1, root.shown))
        clip: true
        Rectangle {
            anchors.fill: parent
            radius: height / 2
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: Theme.success }
                GradientStop { position: 1; color: Theme.accentGlow }
            }
        }
        Image {
            visible: root.active
            x: -root.phase
            width: parent.width + root.tile
            height: parent.height
            fillMode: Image.Tile
            source: "image://fx/stripes/" + root.barHeight
            cache: true
        }
        Rectangle {  // gloss
            anchors { left: parent.left; right: parent.right; top: parent.top }
            height: parent.height / 2
            topLeftRadius: height
            topRightRadius: height
            color: Qt.rgba(1, 1, 1, 0.28)
        }
    }

    FrameAnimation {
        running: root.visible && (root.active || Math.abs(root.value - root.shown) > 0.0005)
        onTriggered: {
            root.shown += (root.value - root.shown) * (1 - Math.exp(-frameTime * 10))
            if (root.active)
                root.phase = (root.phase + frameTime * root.tile * 2.5) % root.tile
        }
    }
}
