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
        color: sb.pressed ? X.scrollPress : sb.hovered ? X.scrollHover : X.scrollThumb
    }
    background: Rectangle { color: X.scrollBg; border.color: X.scrollLine }
}
