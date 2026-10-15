pragma Singleton
import QtQuick

// Design tokens: Material structure (8dp grid, type ramp, motion), Aero surface.
QtObject {
    // dark gray tokens
    readonly property color bg: "#1D1E21"
    readonly property color surface1: "#26272B"
    readonly property color surface2: "#2F3034"
    readonly property color glassTint: Qt.rgba(36 / 255, 37 / 255, 41 / 255, 0.70)
    readonly property color primary: "#3BA8FF"
    readonly property color accentGlow: "#5FE3FF"
    readonly property color success: "#4CD07D"
    readonly property color error: "#FF5C6C"
    readonly property color warning: "#FFB547"
    readonly property color textHi: "#ECEDEF"
    readonly property color textLo: "#9B9DA3"
    readonly property color outline: Qt.rgba(1, 1, 1, 0.08)
    readonly property color hairline: Qt.rgba(1, 1, 1, 0.06)

    // 8dp grid
    readonly property int u: 8

    // type ramp 12 / 14 / 16 / 20 / 28
    property string fontFamily: "Inter"
    readonly property int fs12: 12
    readonly property int fs14: 14
    readonly property int fs16: 16
    readonly property int fs20: 20
    readonly property int fs28: 28

    // motion: Material standard easing, nothing over 300 ms
    readonly property int fast: 100
    readonly property int med: 200
    readonly property int slow: 300
    readonly property var ease: [0.2, 0, 0, 1, 1, 1]

    // elevation: blur sigma, alpha, y offset per level
    readonly property var shadowSigma: [0, 2, 4, 8]
    readonly property var shadowAlpha: [0, 0.35, 0.45, 0.55]
    readonly property var shadowDy: [0, 1, 2, 4]

    readonly property var stemColors: ({
        "vocals": "#FF7AB6", "drums": "#FFB547", "bass": "#7C8CFF", "other": "#4CD07D",
        "guitar": "#FF8A5C", "piano": "#B48CFF", "instrumental": "#3DD6C6"
    })
    readonly property var stemIcons: ({
        "vocals": "music", "drums": "equalizer", "bass": "speed", "other": "layers",
        "guitar": "music", "piano": "music", "instrumental": "layers"
    })

    function alpha(c, a) { return Qt.rgba(c.r, c.g, c.b, a) }
    function hex(c) { return c.toString().substring(1, 7).toUpperCase() }
    function icon(name, color) { return "image://icon/" + name + "/" + hex(color) }
    function stemColor(name) { return stemColors[name] || "#9B9DA3" }

    function time(sec, tenths) {
        if (!isFinite(sec) || sec < 0) sec = 0
        var m = Math.floor(sec / 60)
        var s = sec - m * 60
        var ss = tenths ? s.toFixed(1) : Math.floor(s).toString()
        if ((tenths && s < 10) || (!tenths && s < 10)) ss = "0" + ss
        return m + ":" + ss
    }
}
