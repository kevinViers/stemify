import QtQuick

// Stereo peak meter fed per frame from the audio ring buffer (via Main).
Item {
    id: m
    property real levelLeft: 0
    property real levelRight: 0
    property real holdL: 0
    property real holdR: 0
    implicitWidth: 150
    implicitHeight: 22

    function frac(v) {
        if (v <= 0.000001) return 0
        var db = 20 * Math.log(v) / Math.LN10
        return Math.max(0, Math.min(1, (db + 54) / 54))
    }
    onLevelLeftChanged: holdL = Math.max(holdL, frac(levelLeft))
    onLevelRightChanged: holdR = Math.max(holdR, frac(levelRight))
    FrameAnimation {
        running: m.holdL > 0.001 || m.holdR > 0.001
        onTriggered: {
            var d = frameTime * 0.35
            m.holdL = Math.max(m.frac(m.levelLeft), m.holdL - d)
            m.holdR = Math.max(m.frac(m.levelRight), m.holdR - d)
        }
    }

    Column {
        anchors.fill: parent
        spacing: 4
        Repeater {
            model: 2
            Item {
                required property int index
                readonly property real level: m.frac(index === 0 ? m.levelLeft : m.levelRight)
                readonly property real hold: index === 0 ? m.holdL : m.holdR
                width: m.width
                height: (m.height - 4) / 2
                Rectangle {
                    anchors.fill: parent
                    radius: 2
                    color: Qt.rgba(1, 1, 1, 0.07)
                }
                Item {
                    width: parent.width * parent.level
                    height: parent.height
                    clip: true
                    Rectangle {
                        width: m.width
                        height: parent.height
                        radius: 2
                        gradient: Gradient {
                            orientation: Gradient.Horizontal
                            GradientStop { position: 0.0; color: Theme.success }
                            GradientStop { position: 0.72; color: Theme.accentGlow }
                            GradientStop { position: 0.88; color: Theme.warning }
                            GradientStop { position: 1.0; color: Theme.error }
                        }
                    }
                }
                Rectangle {
                    visible: parent.hold > 0.01
                    x: Math.min(parent.width - 2, parent.width * parent.hold)
                    width: 2
                    height: parent.height
                    color: parent.hold > 0.95 ? Theme.error : Theme.textHi
                    opacity: 0.85
                }
            }
        }
    }
}
