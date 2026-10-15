import QtQuick

// Office checkbox: square box, green tick.
Item {
    id: c
    property bool checked: false
    property string text: ""
    signal toggled()
    implicitWidth: 18 + lbl.implicitWidth + 4
    implicitHeight: 20
    opacity: enabled ? 1 : 0.4
    Rectangle {
        id: box
        width: 13
        height: 13
        y: (parent.height - height) / 2
        x: 2
        color: "#FFFFFF"
        border.color: ma.containsMouse ? X.green : "#7A7A7A"
        Ico { visible: c.checked; anchors.centerIn: parent; name: "check"; size: 12 }
    }
    T { id: lbl; x: 20; height: parent.height; text: c.text }
    MouseArea { id: ma; anchors.fill: parent; hoverEnabled: true; onClicked: c.toggled() }
}
