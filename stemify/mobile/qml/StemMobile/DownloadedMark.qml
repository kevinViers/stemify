import QtQuick

// Downloaded marker: filled circle with a down arrow, as in Apple Music's lists.
Canvas {
    property real size: 14
    property color color: Ios.label2
    property color bg: Ios.bg
    width: size
    height: size
    onPaint: {
        var c = getContext("2d"), w = size
        c.reset()
        c.fillStyle = color
        c.beginPath(); c.arc(w / 2, w / 2, w / 2, 0, 2 * Math.PI); c.fill()
        c.strokeStyle = bg; c.lineWidth = w * 0.12; c.lineCap = "round"
        c.beginPath(); c.moveTo(w / 2, w * 0.26); c.lineTo(w / 2, w * 0.7)
        c.moveTo(w * 0.32, w * 0.52); c.lineTo(w / 2, w * 0.7); c.lineTo(w * 0.68, w * 0.52); c.stroke()
    }
}
