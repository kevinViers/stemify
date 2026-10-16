import QtQuick

Canvas {
    id: r
    property real progress: 0
    property real size: 18
    property color color: Ios.accent
    property color track: Ios.fillStrong
    width: size
    height: size
    onProgressChanged: requestPaint()
    onColorChanged: requestPaint()
    onPaint: {
        var c = getContext("2d"), w = 2, rad = size / 2 - w / 2
        c.reset()
        c.lineWidth = w
        c.strokeStyle = track
        c.beginPath(); c.arc(size / 2, size / 2, rad, 0, 2 * Math.PI); c.stroke()
        c.strokeStyle = color
        c.lineCap = "round"
        c.beginPath(); c.arc(size / 2, size / 2, rad, -Math.PI / 2, -Math.PI / 2 + 2 * Math.PI * Math.max(0, Math.min(1, progress))); c.stroke()
    }
}
