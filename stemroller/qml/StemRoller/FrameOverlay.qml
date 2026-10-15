import QtQuick

// Dev overlay: p50/p99 frame interval (and render-thread work with --render-hooks).
// While visible, the heartbeat bar changes every frame so the window renders at
// full refresh and the numbers describe real 240Hz cadence, not idle frames.
Rectangle {
    id: o
    property var s: ({})
    property real acc: 0
    property var batch: []
    property real beat: 0
    width: 236
    height: col.implicitHeight + 24
    radius: 8
    color: Theme.alpha(Theme.surface1, 0.92)
    border.color: Theme.outline
    readonly property color good: Theme.success
    readonly property color bad: Theme.error

    onVisibleChanged: frameStats.setHooks(visible)
    FrameAnimation {
        running: o.visible
        onTriggered: {
            o.batch.push(frameTime * 1000)
            if (frameTime * 1000 > 1.5 * (o.s.budget_ms || 4.17)) frameStats.miss(frameTime * 1000)
            o.beat = (o.beat + frameTime) % 1
            o.acc += frameTime
            if (o.acc > 0.25) {
                o.acc = 0
                frameStats.push(o.batch)
                o.batch = []
                o.s = frameStats.summary()
            }
        }
    }
    Rectangle {  // heartbeat: moves every frame
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 3
        x: 8 + (parent.width - 16 - width) * o.beat
        width: 24
        height: 2
        radius: 1
        color: Theme.accentGlow
        opacity: 0.6
    }
    Column {
        id: col
        anchors { left: parent.left; top: parent.top; margins: 10 }
        spacing: 3
        Txt {
            text: (o.s.refresh_hz || 0).toFixed(0) + " Hz panel  ·  " + (o.s.fps || 0).toFixed(1) + " fps"
            font.pixelSize: Theme.fs12
            font.weight: Font.DemiBold
            font.features: { "tnum": 1 }
            color: (o.s.fps || 0) >= (o.s.refresh_hz || 240) * 0.97 ? o.good : Theme.warning
        }
        Txt {
            text: o.s.work_p50 === null || o.s.work_p50 === undefined ? "frame time  measuring…"
                  : "frame time  p50 " + o.s.work_p50.toFixed(2) + "  p99 " + o.s.work_p99.toFixed(2) + " ms"
            font.pixelSize: 11; font.features: { "tnum": 1 }
            color: (o.s.work_p99 || 0) < (o.s.budget_ms || 4.17) ? Theme.textHi : o.bad
        }
        Txt {
            text: "interval  p50 " + (o.s.interval_p50 || 0).toFixed(2) + "  p99 " + (o.s.interval_p99 || 0).toFixed(2) + " ms"
            font.pixelSize: 11; font.features: { "tnum": 1 }
            color: Theme.textLo
        }
        Txt {
            text: "budget " + (o.s.budget_ms || 0).toFixed(2) + " ms  ·  missed " + (o.s.missed || 0) + "/" + (o.s.frames || 0)
            font.pixelSize: 11; font.features: { "tnum": 1 }
            color: (o.s.missed || 0) === 0 ? Theme.textLo : o.bad
        }
    }
}
