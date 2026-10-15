import QtQuick

// A ribbon command: large (icon over a label) or small (icon beside a label).
Item {
    id: b
    property string icon: ""
    property string text: ""
    property bool big: false
    property bool checkable: false
    property bool checked: false
    property bool dropdown: false
    property string tip: ""
    signal clicked()
    implicitWidth: big ? Math.max(44, lbl.implicitWidth + 12) : (icon ? 22 : 6) + (text ? lbl.implicitWidth + 8 : 0) + (dropdown ? 12 : 0)
    implicitHeight: big ? 66 : 22
    opacity: enabled ? 1 : 0.4

    Rectangle {
        anchors.fill: parent
        color: b.checked ? X.checked : ma.pressed ? X.pressed : ma.containsMouse ? X.hover : "transparent"
        border.color: b.checked ? X.checkedBorder : "transparent"
    }
    Ico {
        id: ic
        visible: b.icon !== ""
        name: b.icon
        size: b.big ? 32 : 16
        x: b.big ? (parent.width - width) / 2 : 3
        y: b.big ? 4 : 3
    }
    T {
        id: lbl
        text: b.text
        font.pixelSize: X.fsUi
        x: b.big ? (parent.width - width) / 2 : (b.icon ? 23 : 4)
        y: b.big ? 38 : 0
        height: b.big ? implicitHeight : parent.height
        width: b.big ? Math.min(implicitWidth, 90) : implicitWidth
        horizontalAlignment: Text.AlignHCenter
        wrapMode: b.big ? Text.WordWrap : Text.NoWrap
        maximumLineCount: 2
        elide: Text.ElideNone
        lineHeight: 0.95
    }
    Ico {
        visible: b.dropdown
        name: "dropdown"
        size: 10
        x: b.big ? (parent.width - width) / 2 : parent.width - 12
        y: b.big ? parent.height - 13 : 6
    }
    MouseArea {
        id: ma
        anchors.fill: parent
        hoverEnabled: true
        onClicked: b.clicked()
    }
    Tip { target: ma; text: b.tip || b.text }
}
