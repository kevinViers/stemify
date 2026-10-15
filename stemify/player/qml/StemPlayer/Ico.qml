import QtQuick

// image://icon/<name>[/<hex ink>], rendered at device pixels.
Image {
    property string name: "grid"
    property string ink: ""
    property int size: 16
    width: size
    height: size
    source: "image://icon/" + name + (ink ? "/" + ink.replace("#", "") : "")
    sourceSize.width: size * Screen.devicePixelRatio
    sourceSize.height: size * Screen.devicePixelRatio
    smooth: true
    asynchronous: false
}
