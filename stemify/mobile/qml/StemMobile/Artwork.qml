import QtQuick
import QtQuick.Window

// Plex artwork through the photo transcoder, sized to the screen's pixels, corners rounded by
// the image provider. A glyph sits underneath until (or unless) the art arrives.
Rectangle {
    id: a
    property string thumb
    property real size: 48
    property real corner: 6
    property bool circle: false
    property string placeholder: "note"
    property var pal: Ios
    readonly property int px: Math.min(1440, Math.max(120, Math.ceil(size * Screen.devicePixelRatio / 120) * 120))
    width: size
    height: size
    radius: circle ? size / 2 : corner
    color: pal.placeholder
    Sym {
        anchors.centerIn: parent
        name: a.placeholder
        size: a.size * 0.38
        color: a.pal.label3
        visible: img.status !== Image.Ready
    }
    Image {
        id: img
        anchors.fill: parent
        source: a.thumb && a.size > 0 ? "image://art/" + a.px + "/" + Math.round((a.circle ? a.size / 2 : a.corner) / a.size * a.px) + "/" + encodeURIComponent(a.thumb) : ""
        sourceSize.width: a.px
        sourceSize.height: a.px
        asynchronous: true
        fillMode: Image.PreserveAspectCrop
        smooth: true
        mipmap: true
    }
    Rectangle {
        anchors.fill: parent
        radius: parent.radius
        color: "transparent"
        border.width: 0.5
        border.color: Ios.alpha(a.pal.separator, 0.6)
    }
}
