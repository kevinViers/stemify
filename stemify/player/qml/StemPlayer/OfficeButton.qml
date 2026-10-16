import QtQuick

// Dialog button: gray with a border; the default one outlined in green.
Rectangle {
    id: ob
    property string text
    property bool primary: false
    signal clicked()
    implicitWidth: Math.max(80, t.implicitWidth + 24)
    implicitHeight: 26
    color: m.pressed ? X.btnPressed : m.containsMouse ? X.btnHover : X.btn
    border.color: m.containsMouse ? X.btnFocus : primary ? X.accent : X.btnBorder
    border.width: primary ? 2 : 1
    T { id: t; anchors.centerIn: parent; text: ob.text }
    MouseArea { id: m; anchors.fill: parent; hoverEnabled: true; onClicked: ob.clicked() }
}
