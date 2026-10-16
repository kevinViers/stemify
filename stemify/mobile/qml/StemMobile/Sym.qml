import QtQuick
import QtQuick.Window

// An SF Symbols-style glyph (icons.py), tinted.
Image {
    property string name
    property color color: Ios.label
    property real size: 24
    width: size
    height: size
    sourceSize.width: Math.ceil(size * Screen.devicePixelRatio)
    sourceSize.height: Math.ceil(size * Screen.devicePixelRatio)
    source: name ? "image://sym/" + name + "/" + Ios.hex(color) : ""
    opacity: color.a
    smooth: true
    cache: true
}
