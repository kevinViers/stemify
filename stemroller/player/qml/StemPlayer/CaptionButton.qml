import QtQuick

Item {
    id: c
    property string icon
    property bool danger: false
    property string tip: ""
    signal clicked()
    width: icon === "pane" ? 40 : 46
    height: parent.height
    Rectangle {
        anchors.fill: parent
        color: cm.pressed ? (c.danger ? "#F1707A" : X.greenDark) : cm.containsMouse ? (c.danger ? "#E81123" : X.greenHover) : "transparent"
    }
    Ico { anchors.centerIn: parent; name: c.icon; ink: "#FFFFFF"; size: c.icon === "pane" ? 16 : 24 }
    MouseArea { id: cm; anchors.fill: parent; hoverEnabled: true; onClicked: c.clicked() }
    Tip { target: cm; text: c.tip }
}
