import QtQuick

// Static 9-patch Aero hover glow; animate `opacity` only.
BorderImage {
    property color color: Theme.accentGlow
    property int radius: 20
    property int sigma: 6
    readonly property int m: Math.ceil(sigma * 3) + 1
    anchors.fill: parent
    anchors.margins: -m
    border { left: m + radius; right: m + radius; top: m + radius; bottom: m + radius }
    source: "image://fx/glow/" + Theme.hex(color) + "/" + sigma + "/" + radius
    opacity: 0
    visible: opacity > 0.001
    z: -1
    cache: true
    Behavior on opacity { NumberAnimation { duration: Theme.fast; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
}
