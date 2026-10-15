import QtQuick
import QtQuick.Controls.Basic

// Office scrollbar: flat gray thumb on a light track.
ScrollBar {
    id: sb
    policy: ScrollBar.AlwaysOn
    minimumSize: 0.04
    padding: 2
    implicitWidth: 15
    implicitHeight: 15
    contentItem: Rectangle {
        implicitWidth: 9
        implicitHeight: 9
        color: sb.pressed ? "#7A7A7A" : sb.hovered ? "#A6A6A6" : "#C2C2C2"
    }
    background: Rectangle { color: "#F0F0F0"; border.color: "#E0E0E0" }
}
