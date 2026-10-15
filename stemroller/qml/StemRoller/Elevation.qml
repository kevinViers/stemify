import QtQuick

// Static 9-patch shadow: no runtime blur, one textured quad.
BorderImage {
    id: shadow
    property int level: 2
    property int radius: 8
    readonly property real sigma: Theme.shadowSigma[level]
    readonly property int m: Math.ceil(sigma * 3) + 1
    readonly property int dy: Theme.shadowDy[level]
    visible: level > 0
    z: -1
    anchors.fill: parent
    anchors.leftMargin: -m
    anchors.rightMargin: -m
    anchors.topMargin: -m + dy
    anchors.bottomMargin: -m - dy
    border { left: m + radius; right: m + radius; top: m + radius; bottom: m + radius }
    source: "image://fx/shadow/" + sigma + "/" + Theme.shadowAlpha[level] + "/" + radius
    cache: true
}
