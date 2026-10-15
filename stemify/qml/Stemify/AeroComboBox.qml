import QtQuick
import QtQuick.Controls.Basic

// Tonal glossy dropdown; menu on surface-2 at elevation 3.
ComboBox {
    id: control
    property string iconName: ""
    property string subRole: "sub"
    property string caption: ""
    property int popupWidth: 280
    textRole: "label"
    valueRole: "id"
    implicitHeight: 36
    hoverEnabled: true
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fs14

    background: Item {
        Glow { radius: 18; sigma: 4; opacity: (control.hovered || control.visualFocus) ? 0.3 : 0 }
        Rectangle {
            anchors.fill: parent
            radius: height / 2
            color: control.down || control.popup.visible ? Qt.rgba(1, 1, 1, 0.11) : (control.hovered ? Qt.rgba(1, 1, 1, 0.08) : Qt.rgba(1, 1, 1, 0.05))
            border.color: control.popup.visible ? Theme.alpha(Theme.accentGlow, 0.6) : Qt.rgba(1, 1, 1, 0.10)
            Behavior on color { ColorAnimation { duration: Theme.fast } }
            Rectangle {
                anchors { left: parent.left; right: parent.right; top: parent.top; margins: 1 }
                height: (parent.height - 2) / 2
                topLeftRadius: height
                topRightRadius: height
                gradient: Gradient {
                    GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 0.08) }
                    GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 0.0) }
                }
            }
        }
    }

    contentItem: Row {
        leftPadding: control.iconName.length ? 12 : 16
        spacing: 8
        Icon {
            visible: control.iconName.length > 0
            name: control.iconName
            size: 18
            color: Theme.accentGlow
            anchors.verticalCenter: parent.verticalCenter
        }
        Column {
            anchors.verticalCenter: parent.verticalCenter
            Txt {
                visible: control.caption.length > 0
                text: control.caption
                font.pixelSize: 10
                font.weight: Font.DemiBold
                font.letterSpacing: 0.6
                color: Theme.textLo
            }
            Txt {
                text: control.displayText
                font: control.font
                width: control.width - 64
            }
        }
    }

    indicator: Icon {
        x: control.width - width - 10
        anchors.verticalCenter: control.verticalCenter
        name: "expand"
        size: 18
        color: Theme.textLo
        rotation: control.popup.visible ? 180 : 0
        Behavior on rotation { NumberAnimation { duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    }

    delegate: ItemDelegate {
        id: d
        required property var modelData
        required property int index
        width: ListView.view ? ListView.view.width : control.popupWidth
        height: 52
        highlighted: control.highlightedIndex === index
        hoverEnabled: true
        contentItem: Column {
            spacing: 2
            leftPadding: 4
            anchors.verticalCenter: parent ? parent.verticalCenter : undefined
            Txt {
                text: d.modelData[control.textRole]
                font.weight: control.currentIndex === d.index ? Font.DemiBold : Font.Normal
                color: control.currentIndex === d.index ? Theme.accentGlow : Theme.textHi
            }
            Txt {
                text: d.modelData[control.subRole] || ""
                visible: text.length > 0
                font.pixelSize: Theme.fs12
                color: Theme.textLo
            }
        }
        background: Rectangle {
            radius: 6
            color: d.highlighted ? Theme.alpha(Theme.primary, 0.18) : (d.hovered ? Qt.rgba(1, 1, 1, 0.05) : "transparent")
        }
    }

    popup: Popup {
        y: control.height + 6
        width: Math.max(control.width, control.popupWidth)
        padding: 6
        transformOrigin: Item.Top
        implicitHeight: Math.min(contentItem.implicitHeight + 12, 420)
        contentItem: ListView {
            clip: true
            implicitHeight: contentHeight
            model: control.popup.visible ? control.delegateModel : null
            currentIndex: control.highlightedIndex
            spacing: 2
            boundsBehavior: Flickable.StopAtBounds
        }
        background: Rectangle {
            color: Theme.surface2
            radius: 8
            border.color: Theme.outline
            Elevation { level: 3; radius: 8 }
        }
        enter: Transition {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast }
            NumberAnimation { property: "scale"; from: 0.96; to: 1; duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
        }
        exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast } }
    }
}
