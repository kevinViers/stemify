import QtQuick

// Aero glass for chrome. Shows the window's cached, pre-blurred backdrop
// (refreshed only on resize), so nothing is blurred per frame.
Item {
    id: root
    property real offsetX: 0
    property real offsetY: 0
    property int blur: 16
    property real topLeftRadius: 0
    property real topRightRadius: 0
    property real bottomLeftRadius: 0
    property real bottomRightRadius: 0
    property bool gloss: true
    property bool innerBorder: true
    clip: true

    readonly property var win: Window.window

    Image {
        x: -root.offsetX
        y: -root.offsetY
        width: root.win ? root.win.width : 0
        height: root.win ? root.win.height : 0
        source: root.win ? "image://fx/glass/" + root.win.fxW + "x" + root.win.fxH + "/" + root.win.radius + "/" + root.blur : ""
        asynchronous: true
        retainWhileLoading: true
        cache: true
        smooth: true
    }

    // Aero gloss: top highlight band
    Rectangle {
        visible: root.gloss
        anchors { left: parent.left; right: parent.right; top: parent.top }
        height: Math.min(parent.height * 0.5, 30)
        topLeftRadius: root.topLeftRadius
        topRightRadius: root.topRightRadius
        gradient: Gradient {
            GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 0.07) }
            GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 0.0) }
        }
    }

    // 1px inner white border at 8%
    Rectangle {
        visible: root.innerBorder
        anchors.fill: parent
        color: "transparent"
        border.color: Theme.outline
        border.width: 1
        topLeftRadius: root.topLeftRadius
        topRightRadius: root.topRightRadius
        bottomLeftRadius: root.bottomLeftRadius
        bottomRightRadius: root.bottomRightRadius
    }
}
