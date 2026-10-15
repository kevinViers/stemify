import QtQuick
import QtQuick.Controls.Basic

// Round icon button; checkable variant used for solo / mute.
AbstractButton {
    id: b
    property string iconName: "music"
    property color iconColor: Theme.textLo
    property color activeColor: Theme.primary
    property color hoverColor: Theme.alpha(Theme.textHi, 0.08)
    property int size: 32
    property int iconSize: 18
    property string tip: ""
    property bool glow: true
    implicitWidth: size
    implicitHeight: size
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    opacity: enabled ? 1 : 0.35

    background: Item {
        Glow {
            radius: Math.round(b.size / 2)
            sigma: 4
            color: b.checked ? b.activeColor : Theme.accentGlow
            opacity: b.glow && (b.hovered || b.visualFocus) && b.enabled ? 0.35 : 0
        }
        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: b.checked ? Theme.alpha(b.activeColor, b.down ? 0.34 : 0.22)
                             : (b.down ? Theme.alpha(Theme.textHi, 0.14) : (b.hovered ? b.hoverColor : "transparent"))
            border.width: b.checked ? 1 : 0
            border.color: Theme.alpha(b.activeColor, 0.6)
            Behavior on color { ColorAnimation { duration: Theme.fast } }
        }
    }
    contentItem: Item {
        Icon {
            anchors.centerIn: parent
            name: b.iconName
            size: b.iconSize
            color: b.checked ? b.activeColor : (b.hovered ? Theme.textHi : b.iconColor)
        }
    }
    Tip { visible: b.hovered && b.tip.length > 0; text: b.tip }
}
