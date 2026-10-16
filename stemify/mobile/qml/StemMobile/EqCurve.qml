import QtQuick

// The EQ's response, log frequency across, dB up. Left and right are drawn separately when some
// filter applies to only one of them (right in orange). `nodes` are numbered handles;
// `highlight` gets its own shaded curve.
Canvas {
    id: c
    property var chain: []
    property real preamp: 0
    property bool active: true
    property real range: 18
    property bool bandMarkers: false
    property var nodes: []
    property var highlight: null
    property bool labels: false
    readonly property var resp: eq.curve(chain, 200)
    readonly property var own: highlight ? eq.curve([highlight], 200).left : null
    onRespChanged: requestPaint()
    onOwnChanged: requestPaint()
    onNodesChanged: requestPaint()
    onActiveChanged: requestPaint()
    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()
    Connections { target: Ios; function onModeChanged() { c.requestPaint() } function onAccentChanged() { c.requestPaint() } }

    function py(db) { return height / 2 - (db / range) * (height / 2) }
    function px(f) { return Math.log(f / 20) / Math.log(1000) * width }

    onPaint: {
        var g = getContext("2d")
        g.reset()
        g.lineWidth = 1
        g.strokeStyle = Ios.separator
        for (var d = 6; d < range; d += 6) {
            g.beginPath(); g.moveTo(0, py(d)); g.lineTo(width, py(d)); g.moveTo(0, py(-d)); g.lineTo(width, py(-d)); g.stroke()
        }
        var grid = bandMarkers ? [31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000] : [50, 100, 200, 500, 1000, 2000, 5000, 10000]
        g.beginPath()
        grid.forEach(f => { g.moveTo(px(f), 0); g.lineTo(px(f), height) })
        g.stroke()
        g.strokeStyle = Ios.label3; g.lineWidth = 1.5
        g.beginPath(); g.moveTo(0, py(0)); g.lineTo(width, py(0)); g.stroke()
        if (labels) {
            g.fillStyle = Ios.label3
            g.font = "11px '" + Ios.font + "'"
            g.fillText("100", px(100) + 3, height - 4); g.fillText("1k", px(1000) + 3, height - 4); g.fillText("10k", px(10000) + 3, height - 4)
            g.fillText("+" + Math.round(range), 3, 12); g.fillText("−" + Math.round(range), 3, height - 4)
        }
        var main = active ? Ios.accent : Ios.label3
        function curve(v, color, w, dashed, fill) {
            g.beginPath()
            for (var i = 0; i < v.length; i++) {
                var ax = i / (v.length - 1) * width, ay = py(v[i])
                if (i === 0) g.moveTo(ax, ay); else g.lineTo(ax, ay)
            }
            if (fill !== undefined) {
                g.save(); g.lineTo(width, fill); g.lineTo(0, fill); g.closePath()
                g.fillStyle = Qt.rgba(color.r, color.g, color.b, color.a * 0.4); g.fill(); g.restore()
                g.beginPath()
                for (var k = 0; k < v.length; k++) { var qx = k / (v.length - 1) * width; if (k === 0) g.moveTo(qx, py(v[k])); else g.lineTo(qx, py(v[k])) }
            }
            g.strokeStyle = color; g.lineWidth = w; g.lineCap = "round"; g.lineJoin = "round"
            g.setLineDash(dashed ? [6, 6] : [])
            g.stroke()
            g.setLineDash([])
        }
        if (own) curve(own, active ? Ios.alpha(main, 0.35) : Ios.label3, 1.5, false, py(0))
        curve(resp.left, highlight ? Ios.label : main, 2.5, false)
        if (resp.right.length) curve(resp.right, active ? Ios.rightChannel : Ios.label3, 2, false)
        if (preamp < 0 && !highlight) curve(resp.left.map(v => v + preamp), Ios.label2, 1.2, true)
        for (var n = 0; n < nodes.length; n++) {
            var f = nodes[n], sel = highlight && f.id === highlight.id
            var info = eq.typeInfo(f.type)
            var cx = px(Math.max(20, Math.min(20000, f.freq)))
            var cy = info.hasGain ? py(Math.max(-range, Math.min(range, f.gain))) : py(0)
            var r = sel ? 11 : 9
            var col = !f.on ? Ios.label3 : f.channel === "right" ? Ios.rightChannel : main
            g.beginPath(); g.arc(cx, cy, r, 0, 2 * Math.PI)
            g.fillStyle = sel ? "white" : col; g.fill()
            if (sel) { g.strokeStyle = col; g.lineWidth = 2.5; g.stroke() }
            g.fillStyle = sel ? col : "white"
            g.font = "bold 11px '" + Ios.font + "'"
            var label = String(n + 1), tw = g.measureText(label).width
            g.fillText(label, cx - tw / 2, cy + 4)
        }
    }
}
