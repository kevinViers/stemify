import QtQuick

// Dialog button: gray with a border; the default one outlined in green.
Rectangle {
    id: ob
    property string text
    property bool primary: false
    signal clicked()
    implicitWidth: Math.max(80, t.implicitWidth + 24)
    implicitHeight: 26
    color: m.pressed ? "#CCE4F7" : m.containsMouse ? "#E5F1FB" : "#FDFDFD"
    border.color: m.containsMouse ? "#0078D7" : primary ? X.green : "#ADADAD"
    border.width: primary ? 2 : 1
    T { id: t; anchors.centerIn: parent; text: ob.text }
    MouseArea { id: m; anchors.fill: parent; hoverEnabled: true; onClicked: ob.clicked() }
}
