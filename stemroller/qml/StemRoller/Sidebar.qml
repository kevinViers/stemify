import QtQuick
import QtQuick.Controls.Basic

GlassPanel {
    id: side
    signal browse()

    Item {
        id: header
        anchors { left: parent.left; right: parent.right; top: parent.top }
        height: 52
        Txt {
            anchors { left: parent.left; leftMargin: 16; verticalCenter: parent.verticalCenter }
            text: "Queue"
            font.pixelSize: Theme.fs16
            font.weight: Font.DemiBold
        }
        Rectangle {
            visible: list.count > 0
            anchors { left: parent.left; leftMargin: 76; verticalCenter: parent.verticalCenter }
            width: cnt.implicitWidth + 12
            height: 18
            radius: 9
            color: Qt.rgba(1, 1, 1, 0.08)
            Txt { id: cnt; anchors.centerIn: parent; text: list.count; font.pixelSize: 11; color: Theme.textLo }
        }
        IconButton {
            anchors { right: parent.right; rightMargin: 10; verticalCenter: parent.verticalCenter }
            iconName: "add"
            tip: "Add files  (Ctrl+O)"
            onClicked: side.browse()
        }
    }

    ListView {
        id: list
        anchors { left: parent.left; right: parent.right; top: header.bottom; bottom: parent.bottom; margins: 8; topMargin: 0 }
        model: jobsModel
        spacing: 4
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        delegate: QueueItem {}
        add: Transition {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.med }
            NumberAnimation { property: "x"; from: -16; duration: Theme.slow; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
        }
        displaced: Transition {
            NumberAnimation { properties: "y"; duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
        }
        ScrollBar.vertical: ScrollBar {
            policy: ScrollBar.AsNeeded
            contentItem: Rectangle { implicitWidth: 4; radius: 2; color: Qt.rgba(1, 1, 1, 0.18); visible: parent.size < 1 }
                background: Item {}
        }

        Column {
            visible: list.count === 0
            anchors.centerIn: parent
            spacing: 8
            width: parent.width - 32
            Icon { anchors.horizontalCenter: parent.horizontalCenter; name: "upload"; size: 28; color: Theme.alpha(Theme.textLo, 0.7) }
            Txt {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: "Drop audio anywhere to queue it"
                color: Theme.textLo
                font.pixelSize: Theme.fs12
            }
        }
    }
}
