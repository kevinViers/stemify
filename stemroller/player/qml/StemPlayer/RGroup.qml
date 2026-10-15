import QtQuick

// A ribbon group: its commands, a caption along the bottom, a rule on the right.
Item {
    id: g
    property string title: ""
    default property alias content: row.data
    implicitWidth: Math.max(row.implicitWidth, cap.implicitWidth) + 12
    height: parent ? parent.height : 92
    Row {
        id: row
        x: 6
        y: 3
        height: 68
        spacing: 2
    }
    T {
        id: cap
        text: g.title
        font.pixelSize: X.fsSmall
        color: X.inkSoft
        anchors.horizontalCenter: parent.horizontalCenter
        y: parent.height - 18
    }
    Rectangle {
        anchors { right: parent.right; top: parent.top; bottom: parent.bottom; topMargin: 4; bottomMargin: 4 }
        width: 1
        color: X.ribbonLine
    }
}
