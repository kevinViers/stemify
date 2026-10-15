import QtQuick
import QtQuick.Controls.Basic

// One lane per stem: color strip, solo / mute / gain / export, waveform texture.
Item {
    id: lane
    required property int index
    required property var modelData
    property string jobId: ""
    property real viewStart: 0
    property real viewEnd: 1
    property int waveW: 100
    property int waveH: 60
    property real waveX: 0
    property bool anySolo: false
    property bool solo: false
    property bool mute: false
    property real gainDb: 0
    signal soloToggled(bool on)
    readonly property string stem: modelData.name
    readonly property color tone: modelData.color
    readonly property bool audible: anySolo ? solo : !mute
    height: 92

    Card {
        anchors.fill: parent
        clip: false

        // color strip with soft glow
        Rectangle {
            x: 0
            width: 4
            height: parent.height
            topLeftRadius: 8
            bottomLeftRadius: 8
            color: lane.tone
        }
        Rectangle {
            x: 4
            width: 24
            height: parent.height
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: Theme.alpha(lane.tone, 0.14) }
                GradientStop { position: 1; color: Theme.alpha(lane.tone, 0.0) }
            }
        }

        Column {
            id: controls
            x: 18
            width: lane.waveX - 30
            anchors.verticalCenter: parent.verticalCenter
            spacing: 6
            Row {
                spacing: 6
                width: parent.width
                Txt {
                    width: parent.width - 3 * 30 - 12
                    text: lane.stem.charAt(0).toUpperCase() + lane.stem.slice(1)
                    font.pixelSize: Theme.fs16
                    font.weight: Font.DemiBold
                    anchors.verticalCenter: parent.verticalCenter
                }
                IconButton {
                    size: 30; iconSize: 16
                    iconName: "headphones"
                    checkable: true
                    checked: lane.solo
                    activeColor: Theme.warning
                    tip: "Solo"
                    onClicked: lane.soloToggled(checked)
                }
                IconButton {
                    size: 30; iconSize: 16
                    iconName: lane.mute ? "volume_off" : "volume"
                    checkable: true
                    checked: lane.mute
                    activeColor: Theme.error
                    tip: "Mute"
                    onClicked: { lane.mute = checked; audio.setMute(lane.index, checked) }
                }
                IconButton {
                    size: 30; iconSize: 16
                    iconName: "download"
                    tip: "Export " + lane.stem
                    onClicked: app.exportStem(lane.stem)
                }
            }
            Row {
                spacing: 8
                width: parent.width
                AeroSlider {
                    id: gain
                    width: parent.width - 52
                    from: -24
                    to: 12
                    stepSize: 0.5
                    value: lane.gainDb
                    tone: lane.tone
                    onMoved: { lane.gainDb = value; audio.setGain(lane.index, value) }
                    // double-click resets to unity
                    TapHandler { onDoubleTapped: { lane.gainDb = 0; audio.setGain(lane.index, 0) } }
                }
                Txt {
                    width: 44
                    anchors.verticalCenter: parent.verticalCenter
                    horizontalAlignment: Text.AlignRight
                    text: (lane.gainDb > 0 ? "+" : "") + lane.gainDb.toFixed(1) + " dB"
                    font.pixelSize: 11
                    font.features: { "tnum": 1 }
                    color: lane.gainDb === 0 ? Theme.textLo : Theme.textHi
                }
            }
        }

        Rectangle {  // waveform well
            x: lane.waveX
            y: 8
            width: lane.waveW
            height: parent.height - 16
            radius: 6
            color: Qt.rgba(0, 0, 0, 0.22)
            border.color: Qt.rgba(1, 1, 1, 0.04)
            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width
                height: 1
                color: Theme.alpha(lane.tone, 0.18)
            }
            Image {
                anchors.fill: parent
                asynchronous: true
                retainWhileLoading: true
                cache: false
                smooth: true
                opacity: lane.audible ? 1 : 0.28
                Behavior on opacity { NumberAnimation { duration: Theme.med } }
                source: lane.jobId && lane.waveW > 4
                        ? "image://wave/" + lane.jobId + "/" + lane.stem + "/"
                          + Math.round(lane.waveW * Screen.devicePixelRatio) + "x" + Math.round((lane.height - 16) * Screen.devicePixelRatio)
                          + "/" + lane.viewStart.toFixed(3) + "/" + lane.viewEnd.toFixed(3) + "/" + Theme.hex(lane.tone)
                        : ""
            }
        }
    }
}
