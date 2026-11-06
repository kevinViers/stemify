import QtQuick

// Like Excel's "PROTECTED VIEW" bar: while this workbook controls a nearby device, says which,
// and offers to move this computer's song over or to stop controlling it.
Rectangle {
    id: rb
    signal openPicker()
    readonly property bool on: nearby.active || nearby.busy
    height: on ? 32 : 0
    visible: on
    color: X.msg
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.msgLine }
    Behavior on height { NumberAnimation { duration: X.easeMs } }
    Row {
        x: 12
        height: parent.height
        spacing: 10
        Ico { anchors.verticalCenter: parent.verticalCenter; name: "play_on"; size: 16 }
        T { height: parent.height; text: "PLAYING ON " + nearby.targetName.toUpperCase(); font.bold: true }
        T {
            height: parent.height
            text: nearby.state === "connecting" ? "Connecting…"
                : nearby.state === "waiting" ? "Waiting for you to allow this computer on " + nearby.targetName + "."
                : "Play, the mixer, the queue and every Play button control " + nearby.targetName + "."
        }
        BarButton { visible: nearby.canTransfer; text: "Move “" + nearby.localTitle + "” There"; onClicked: nearby.transfer() }
        BarButton { visible: nearby.active; text: "Change…"; onClicked: rb.openPicker() }
        BarButton { text: nearby.active ? "Disconnect" : "Cancel"; onClicked: nearby.leave() }
    }

    component BarButton: Rectangle {
        id: b
        property string text
        signal clicked()
        anchors.verticalCenter: parent.verticalCenter
        width: visible ? Math.min(260, bt.implicitWidth + 20) : 0
        height: 22
        color: bm.pressed ? X.pressed : bm.containsMouse ? X.msgHover : X.paper
        border.color: X.borderStrong
        T { id: bt; anchors.centerIn: parent; width: Math.min(implicitWidth, parent.width - 12); text: b.text }
        MouseArea { id: bm; anchors.fill: parent; hoverEnabled: true; onClicked: b.clicked() }
    }
}
