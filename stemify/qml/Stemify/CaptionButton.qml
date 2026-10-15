import QtQuick
import QtQuick.Controls.Basic

// Win7-flavoured caption button; close turns glossy red on hover.
AbstractButton {
    id: b
    property string iconName: "minimize"
    property bool danger: false
    implicitWidth: 46
    implicitHeight: 30
    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    background: Rectangle {
        radius: 6
        gradient: Gradient {
            GradientStop { position: 0; color: b.danger && b.hovered ? "#F0645A" : (b.hovered ? Qt.rgba(1, 1, 1, 0.14) : "transparent") }
            GradientStop { position: 0.5; color: b.danger && b.hovered ? "#D8392B" : (b.hovered ? Qt.rgba(1, 1, 1, 0.07) : "transparent") }
            GradientStop { position: 1; color: b.danger && b.hovered ? "#C42B1C" : (b.hovered ? Qt.rgba(1, 1, 1, 0.05) : "transparent") }
        }
        border.color: b.hovered ? (b.danger ? "#FF8A80" : Qt.rgba(1, 1, 1, 0.14)) : "transparent"
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: b.down ? Qt.rgba(0, 0, 0, 0.2) : "transparent"
        }
        Glow { radius: 6; sigma: 5; color: "#FF5C4C"; opacity: b.danger && b.hovered ? 0.5 : 0 }
    }
    contentItem: Item {
        Icon { anchors.centerIn: parent; name: b.iconName; size: 16; color: b.hovered ? "#FFFFFF" : Theme.textHi }
    }
}
