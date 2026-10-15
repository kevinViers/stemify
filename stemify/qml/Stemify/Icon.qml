import QtQuick

// Material icon rendered once per (name, color, size) by the icon provider.
Image {
    property string name: "music"
    property color color: Theme.textHi
    property int size: 20
    width: size
    height: size
    sourceSize: Qt.size(size * Screen.devicePixelRatio, size * Screen.devicePixelRatio)
    source: Theme.icon(name, color)
    smooth: true
    asynchronous: false
}
