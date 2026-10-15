import QtQuick
import QtQuick.Controls.Basic

// Office context menu: white, gray border, icon gutter on the left.
Menu {
    id: m
    padding: 2
    delegate: MenuItem {
        id: mi
        implicitWidth: Math.max(200, lab.implicitWidth + 56)
        implicitHeight: mi.text === "" ? 7 : 26
        enabled: mi.action ? mi.action.enabled : true
        contentItem: Item {
            T {
                id: lab
                x: 28
                anchors.verticalCenter: parent.verticalCenter
                text: mi.text
                color: mi.enabled ? X.ink : X.inkFaint
            }
            Ico {
                visible: mi.action && mi.action.icon.name !== ""
                name: mi.action ? mi.action.icon.name : ""
                size: 16
                x: 4
                anchors.verticalCenter: parent.verticalCenter
            }
            Ico {
                visible: mi.checkable && mi.checked
                name: "check"
                size: 14
                x: 5
                anchors.verticalCenter: parent.verticalCenter
            }
        }
        background: Rectangle {
            color: mi.highlighted && mi.enabled ? "#E0E0E0" : "transparent"
        }
        arrow: null
        indicator: null
    }
    background: Rectangle {
        implicitWidth: 200
        color: "#FFFFFF"
        border.color: X.menuBorder
        Rectangle { x: 25; y: 1; width: 1; height: parent.height - 2; color: "#E7E7E7" }
    }
}
