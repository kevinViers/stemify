import QtQuick
import QtQuick.Controls.Basic

GlassPanel {
    id: tr
    property var win: Window.window
    readonly property bool ready: audio.loaded
    readonly property real dur: audio.duration
    bottomLeftRadius: win.radius
    bottomRightRadius: win.radius
    gloss: true

    Rectangle {
        anchors { left: parent.left; right: parent.right; top: parent.top }
        height: 1
        color: Theme.hairline
    }

    Row {
        id: left
        anchors { left: parent.left; leftMargin: 16; verticalCenter: parent.verticalCenter }
        spacing: 10
        IconButton {
            anchors.verticalCenter: parent.verticalCenter
            iconName: "skip_start"
            enabled: tr.ready
            tip: "Back to start  (Home)"
            onClicked: { audio.seek(0); tr.win.playPos = 0 }
        }
        AbstractButton {  // glossy round play/pause
            id: play
            anchors.verticalCenter: parent.verticalCenter
            width: 44
            height: 44
            enabled: tr.ready
            hoverEnabled: true
            opacity: enabled ? 1 : 0.4
            onClicked: audio.toggle()
            background: Item {
                Glow { radius: 22; opacity: play.hovered && play.enabled ? 0.6 : 0 }
                Rectangle {
                    anchors.fill: parent
                    radius: 22
                    gradient: Gradient {
                        GradientStop { position: 0; color: "#6CC4FF" }
                        GradientStop { position: 1; color: "#1F7FE0" }
                    }
                    border.color: Qt.rgba(1, 1, 1, 0.35)
                    Rectangle {
                        anchors { left: parent.left; right: parent.right; top: parent.top; margins: 2 }
                        height: 19
                        topLeftRadius: 20
                        topRightRadius: 20
                        gradient: Gradient {
                            GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 0.45) }
                            GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 0.08) }
                        }
                    }
                    Rectangle { anchors.fill: parent; radius: 22; color: play.down ? Qt.rgba(0, 0, 0, 0.2) : "transparent" }
                }
            }
            contentItem: Item {
                Icon { anchors.centerIn: parent; name: audio.playing ? "pause" : "play"; size: 24; color: "#FFFFFF" }
            }
        }
        Txt {
            anchors.verticalCenter: parent.verticalCenter
            width: 118
            text: Theme.time(tr.win.playPos, true) + "  /  " + Theme.time(tr.dur, false)
            font.features: { "tnum": 1 }
            font.pixelSize: Theme.fs14
            color: tr.ready ? Theme.textHi : Theme.textLo
        }
    }

    // scrub bar
    Item {
        id: scrub
        anchors { left: left.right; leftMargin: 16; right: volume.left; rightMargin: 24; verticalCenter: parent.verticalCenter }
        height: 24
        enabled: tr.ready
        readonly property real frac: tr.dur > 0 ? Math.min(1, tr.win.playPos / tr.dur) : 0
        Rectangle {
            id: track
            anchors.verticalCenter: parent.verticalCenter
            width: parent.width
            height: scrubArea.containsMouse || scrubArea.pressed ? 6 : 4
            radius: 3
            color: Qt.rgba(1, 1, 1, 0.10)
            Behavior on height { NumberAnimation { duration: Theme.fast } }
            Rectangle {
                width: parent.width * scrub.frac
                height: parent.height
                radius: 3
                gradient: Gradient {
                    orientation: Gradient.Horizontal
                    GradientStop { position: 0; color: Theme.primary }
                    GradientStop { position: 1; color: Theme.accentGlow }
                }
            }
        }
        Rectangle {
            visible: tr.ready
            x: scrub.width * scrub.frac - width / 2
            anchors.verticalCenter: parent.verticalCenter
            width: scrubArea.containsMouse || scrubArea.pressed ? 14 : 10
            height: width
            radius: width / 2
            color: "#FFFFFF"
            Behavior on width { NumberAnimation { duration: Theme.fast } }
            Glow { radius: 7; sigma: 4; opacity: scrubArea.containsMouse ? 0.7 : 0.3 }
        }
        MouseArea {
            id: scrubArea
            anchors.fill: parent
            hoverEnabled: true
            enabled: tr.ready
            cursorShape: Qt.PointingHandCursor
            function seekTo(x) {
                var t = Math.max(0, Math.min(1, x / width)) * tr.dur
                audio.seek(t)
                tr.win.playPos = t
            }
            onPressed: (e) => seekTo(e.x)
            onPositionChanged: (e) => { if (pressed) seekTo(e.x) }
        }
    }

    // master volume: monitoring only, never baked into exports
    Row {
        id: volume
        anchors { right: meter.left; rightMargin: 24; verticalCenter: parent.verticalCenter }
        spacing: 6
        IconButton {
            id: muteBtn
            property real restore: 1.0
            anchors.verticalCenter: parent.verticalCenter
            iconName: audio.volume <= 0 ? "volume_off" : "volume"
            tip: audio.volume <= 0 ? "Unmute" : "Mute"
            onClicked: {
                if (audio.volume > 0) {
                    restore = audio.volume
                    audio.volume = 0
                } else {
                    audio.volume = restore > 0 ? restore : 1.0
                }
                appSettings.masterVolume = audio.volume
            }
        }
        AeroSlider {
            anchors.verticalCenter: parent.verticalCenter
            width: 112
            from: 0
            to: 1
            value: audio.volume
            onMoved: audio.volume = value
            onPressedChanged: if (!pressed) appSettings.masterVolume = audio.volume
            WheelHandler {
                onWheel: (e) => {
                    audio.volume = Math.max(0, Math.min(1, audio.volume + e.angleDelta.y / 120 * 0.05))
                    appSettings.masterVolume = audio.volume
                }
            }
        }
        Txt {
            anchors.verticalCenter: parent.verticalCenter
            width: 36
            text: Math.round(audio.volume * 100) + "%"
            font.features: { "tnum": 1 }
            font.pixelSize: Theme.fs12
            color: Theme.textLo
        }
    }

    Row {
        id: meter
        anchors { right: parent.right; rightMargin: 20; verticalCenter: parent.verticalCenter }
        spacing: 10
        Column {
            anchors.verticalCenter: parent.verticalCenter
            spacing: 4
            Txt { text: "L"; font.pixelSize: 9; color: Theme.textLo; height: 9 }
            Txt { text: "R"; font.pixelSize: 9; color: Theme.textLo; height: 9 }
        }
        LevelMeter {
            anchors.verticalCenter: parent.verticalCenter
            width: 160
            levelLeft: tr.win.levelL
            levelRight: tr.win.levelR
        }
    }
}
