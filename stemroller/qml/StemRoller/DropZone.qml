import QtQuick

// Empty state: card filling the main pane; dashed border glows on drag-over.
Item {
    id: dz
    property bool dragging: false
    signal browse()

    Card {
        id: card
        anchors.fill: parent
        anchors.margins: 24
        radius: 16
        color: dz.dragging ? Theme.alpha(Theme.primary, 0.08) : Theme.alpha(Theme.surface1, 0.72)
        Behavior on color { ColorAnimation { duration: Theme.med } }

        Glow { radius: 16; sigma: 8; opacity: dz.dragging ? 0.55 : 0 }
        DashedFrame {
            anchors.fill: parent
            anchors.margins: 10
            radius: 12
            color: dz.dragging ? Theme.accentGlow : Theme.alpha(Theme.textLo, 0.35)
        }

        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            hoverEnabled: true
            onClicked: dz.browse()
        }

        Column {
            anchors.centerIn: parent
            spacing: 16
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: 88
                height: 88
                radius: 44
                color: Theme.alpha(Theme.primary, dz.dragging ? 0.28 : 0.14)
                border.color: Theme.alpha(Theme.accentGlow, 0.5)
                scale: dz.dragging ? 1.08 : 1
                Behavior on scale { NumberAnimation { duration: Theme.slow; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
                Behavior on color { ColorAnimation { duration: Theme.med } }
                Glow { radius: 44; sigma: 8; opacity: 0.35 }
                Rectangle {
                    anchors { left: parent.left; right: parent.right; top: parent.top; margins: 3 }
                    height: 40
                    topLeftRadius: 41
                    topRightRadius: 41
                    gradient: Gradient {
                        GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 0.18) }
                        GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 0.0) }
                    }
                }
                Icon { anchors.centerIn: parent; name: "upload"; size: 40; color: Theme.accentGlow }
            }
            Txt {
                anchors.horizontalCenter: parent.horizontalCenter
                text: dz.dragging ? "Release to separate" : "Drop audio files here"
                font.pixelSize: Theme.fs28
                font.weight: Font.DemiBold
            }
            Txt {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "or click to browse  ·  MP3, WAV, FLAC, M4A, OGG, OPUS and more"
                color: Theme.textLo
                font.pixelSize: Theme.fs14
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 8
                topPadding: 8
                Repeater {
                    model: ["vocals", "drums", "bass", "other"]
                    Rectangle {
                        required property string modelData
                        height: 26
                        width: chipText.implicitWidth + 28
                        radius: 13
                        color: Theme.alpha(Theme.stemColor(modelData), 0.14)
                        border.color: Theme.alpha(Theme.stemColor(modelData), 0.4)
                        Rectangle { x: 10; anchors.verticalCenter: parent.verticalCenter; width: 6; height: 6; radius: 3; color: Theme.stemColor(parent.modelData) }
                        Txt { id: chipText; x: 21; anchors.verticalCenter: parent.verticalCenter; text: parent.modelData; font.pixelSize: Theme.fs12; color: Theme.textHi }
                    }
                }
            }
            Txt {
                anchors.horizontalCenter: parent.horizontalCenter
                topPadding: 4
                text: "Separates with " + (app.models.find(m => m.id === appSettings.modelId) || {label: ""}).label
                      + " on " + ((app.devices.find(d => d.id === appSettings.deviceId) || {label: "Auto"}).label)
                color: Theme.alpha(Theme.textLo, 0.8)
                font.pixelSize: Theme.fs12
            }
        }
    }
}
