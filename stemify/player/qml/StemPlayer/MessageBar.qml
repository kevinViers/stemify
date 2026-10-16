import QtQuick

// Excel's yellow message bar, for notices.
Rectangle {
    id: mb
    property string text: ""
    height: text ? 32 : 0
    visible: text !== ""
    color: X.msg
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.msgLine }
    Behavior on height { NumberAnimation { duration: X.easeMs } }
    function show(t) { text = t; hide.restart() }
    Timer { id: hide; interval: 6000; onTriggered: mb.text = "" }
    Row {
        x: 12
        height: parent.height
        spacing: 10
        Ico { anchors.verticalCenter: parent.verticalCenter; name: "info"; size: 16 }
        T { height: parent.height; text: "NOTICE"; font.bold: true }
        T { height: parent.height; text: mb.text }
    }
    Item {
        anchors { right: parent.right; rightMargin: 8; verticalCenter: parent.verticalCenter }
        width: 22
        height: 22
        Rectangle { anchors.fill: parent; color: xm.containsMouse ? X.msgHover : "transparent" }
        Ico { anchors.centerIn: parent; name: "close"; size: 18 }
        MouseArea { id: xm; anchors.fill: parent; hoverEnabled: true; onClicked: mb.text = "" }
    }
}
