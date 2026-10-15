import QtQuick
import QtQuick.Controls.Basic

ItemDelegate {
    id: item
    required property string jobId
    required property string fileName
    required property string modelLabel
    required property string status
    required property real progress
    required property string stage
    required property int index
    readonly property bool selected: app.selected.valid && app.selected.id === jobId
    readonly property bool active: status === "running" || status === "queued"

    width: ListView.view ? ListView.view.width : 200
    height: active ? 84 : 66
    hoverEnabled: true
    padding: 0
    onClicked: app.select(jobId)
    Behavior on height { NumberAnimation { duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

    background: Rectangle {
        radius: 8
        color: item.selected ? Theme.alpha(Theme.primary, 0.16) : (item.hovered ? Qt.rgba(1, 1, 1, 0.05) : "transparent")
        border.color: item.selected ? Theme.alpha(Theme.primary, 0.45) : "transparent"
        Behavior on color { ColorAnimation { duration: Theme.fast } }
        Rectangle {
            visible: item.selected
            x: 0
            anchors.verticalCenter: parent.verticalCenter
            width: 3
            height: parent.height - 20
            radius: 2
            color: Theme.accentGlow
        }
    }

    contentItem: Item {
        Column {
            anchors { left: parent.left; right: parent.right; top: parent.top; leftMargin: 12; rightMargin: 6; topMargin: 10 }
            spacing: 6
            Row {
                width: parent.width
                spacing: 8
                Icon {
                    anchors.verticalCenter: parent.verticalCenter
                    size: 16
                    name: item.status === "done" ? "check" : item.status === "error" ? "error" : "music"
                    color: item.status === "done" ? Theme.success : item.status === "error" ? Theme.error : Theme.textLo
                }
                Txt {
                    width: parent.width - 16 - 28 - 16
                    anchors.verticalCenter: parent.verticalCenter
                    text: item.fileName
                    font.weight: item.selected ? Font.DemiBold : Font.Medium
                }
                IconButton {
                    anchors.verticalCenter: parent.verticalCenter
                    size: 24
                    iconSize: 14
                    iconName: "close"
                    glow: false
                    tip: item.active ? "Cancel" : "Remove"
                    onClicked: item.active ? app.cancelJob(item.jobId) : app.removeJob(item.jobId)
                }
            }
            Row {
                width: parent.width
                spacing: 8
                Txt {
                    width: parent.width - chip.width - 14
                    anchors.verticalCenter: parent.verticalCenter
                    text: item.status === "running" ? item.stage : item.modelLabel
                    font.pixelSize: Theme.fs12
                    color: Theme.textLo
                }
                StatusChip { id: chip; status: item.status; progress: item.progress }
            }
            StripedProgress {
                visible: item.active
                width: parent.width - 6
                value: item.progress
                active: item.status === "running"
                opacity: item.status === "running" ? 1 : 0.35
            }
        }
    }
}
