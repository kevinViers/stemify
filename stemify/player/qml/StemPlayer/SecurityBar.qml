import QtQuick

// Excel's "SECURITY WARNING  Macros have been disabled. [Enable Content]" bar, asking whether a
// device on the network may control playback (Stemify Connect). Bluetooth-paired devices never ask.
Rectangle {
    id: sb
    readonly property var asking: receiver.pending.length ? receiver.pending[0] : null
    height: asking ? 32 : 0
    visible: asking !== null
    color: X.msg
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.msgLine }
    Behavior on height { NumberAnimation { duration: X.easeMs } }
    Row {
        x: 12
        height: parent.height
        spacing: 10
        Ico { anchors.verticalCenter: parent.verticalCenter; name: "info"; size: 16 }
        T { height: parent.height; text: "SECURITY WARNING"; font.bold: true }
        T { height: parent.height; text: sb.asking ? "“" + sb.asking.name + "” on your network wants to control playback." : "" }
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            width: en.implicitWidth + 20; height: 22
            color: ea.pressed ? X.pressed : ea.containsMouse ? X.msgHover : X.paper
            border.color: X.borderStrong
            T { id: en; anchors.centerIn: parent; text: "Enable Content" }
            MouseArea { id: ea; anchors.fill: parent; hoverEnabled: true; onClicked: receiver.approve(sb.asking.id, true) }
        }
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            width: dn.implicitWidth + 20; height: 22
            color: da.pressed ? X.pressed : da.containsMouse ? X.msgHover : X.paper
            border.color: X.borderStrong
            T { id: dn; anchors.centerIn: parent; text: "Don't Allow" }
            MouseArea { id: da; anchors.fill: parent; hoverEnabled: true; onClicked: receiver.deny(sb.asking.id) }
        }
    }
}
