import QtQuick
import QtQuick.Controls.Basic

// Glossy pill, 40px. variant: "filled" | "tonal" | "text". Cyan glow on hover.
Button {
    id: b
    property string iconName: ""
    property string variant: "filled"
    property color tone: Theme.primary
    property int pillHeight: 40
    readonly property bool filled: variant === "filled"
    readonly property bool tonal: variant === "tonal"
    readonly property color fg: filled ? "#FFFFFF" : (variant === "text" ? tone : Theme.textHi)

    implicitHeight: pillHeight
    implicitWidth: Math.max(pillHeight, row.implicitWidth + leftPadding + rightPadding)
    leftPadding: text.length ? (iconName.length ? 14 : 18) : 0
    rightPadding: text.length ? 18 : 0
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fs14
    font.weight: Font.DemiBold
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    opacity: enabled ? 1 : 0.4

    contentItem: Item {
        implicitWidth: row.implicitWidth
        implicitHeight: row.implicitHeight
        Row {
            id: row
            anchors.centerIn: parent
            spacing: 8
            Icon {
                visible: b.iconName.length > 0
                name: b.iconName
                color: b.fg
                size: 18
                anchors.verticalCenter: parent.verticalCenter
            }
            Txt {
                visible: b.text.length > 0
                text: b.text
                font: b.font
                color: b.fg
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }

    background: Item {
        implicitHeight: b.pillHeight
        Glow {
            radius: Math.round(b.pillHeight / 2)
            opacity: (b.hovered || b.visualFocus) && b.enabled ? (b.filled ? 0.55 : 0.35) : 0
        }
        Rectangle {
            id: base
            anchors.fill: parent
            radius: height / 2
            color: b.filled ? b.tone : (b.tonal ? Theme.alpha(b.tone, 0.16) : "transparent")
            border.width: b.variant === "text" ? 0 : 1
            border.color: b.filled ? Qt.lighter(b.tone, 1.3) : Theme.alpha(b.tone, 0.35)

            // Aero gloss on the top half
            Rectangle {
                visible: b.variant !== "text"
                anchors { left: parent.left; right: parent.right; top: parent.top; margins: 1 }
                height: (parent.height - 2) / 2
                topLeftRadius: height
                topRightRadius: height
                gradient: Gradient {
                    GradientStop { position: 0; color: Qt.rgba(1, 1, 1, b.filled ? 0.32 : 0.10) }
                    GradientStop { position: 1; color: Qt.rgba(1, 1, 1, b.filled ? 0.06 : 0.0) }
                }
            }
            Rectangle {
                anchors.fill: parent
                radius: parent.radius
                color: b.down ? Qt.rgba(0, 0, 0, 0.18) : (b.hovered ? Qt.rgba(1, 1, 1, 0.07) : "transparent")
                Behavior on color { ColorAnimation { duration: Theme.fast } }
            }
        }
    }
}
