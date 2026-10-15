import QtQuick
import QtQuick.Controls.Basic

Slider {
    id: s
    property color tone: Theme.primary
    implicitHeight: 24
    hoverEnabled: true
    background: Rectangle {
        x: s.leftPadding
        y: s.topPadding + s.availableHeight / 2 - height / 2
        width: s.availableWidth
        height: 4
        radius: 2
        color: Qt.rgba(1, 1, 1, 0.10)
        Rectangle {
            width: s.visualPosition * parent.width
            height: parent.height
            radius: 2
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: Theme.alpha(s.tone, 0.7) }
                GradientStop { position: 1; color: s.tone }
            }
        }
    }
    handle: Item {
        x: s.leftPadding + s.visualPosition * (s.availableWidth - width)
        y: s.topPadding + s.availableHeight / 2 - height / 2
        width: 14
        height: 14
        Glow { radius: 7; sigma: 4; color: s.tone; opacity: s.hovered || s.pressed ? 0.6 : 0 }
        Rectangle {
            anchors.fill: parent
            radius: 7
            color: s.pressed ? Qt.lighter(s.tone, 1.2) : s.tone
            border.color: Qt.lighter(s.tone, 1.4)
            Rectangle {  // gloss
                anchors { left: parent.left; right: parent.right; top: parent.top; margins: 2 }
                height: 5
                radius: 3
                color: Qt.rgba(1, 1, 1, 0.45)
            }
        }
    }
}
