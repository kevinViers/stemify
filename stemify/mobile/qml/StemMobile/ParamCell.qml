import QtQuick

// A filter parameter: its name, its value (click to type one), and a slider.
CellItem {
    id: p
    property string title
    property string valueText
    property var edit: null
    default property alias slider: box.data
    height: visible ? col.height + 10 : 0
    Column {
        id: col
        x: 16; y: 10; width: parent.width - 32
        Item {
            width: parent.width; height: 22
            Txt { text: p.title; style: "subhead"; color: Ios.label2; anchors.verticalCenter: parent.verticalCenter }
            Txt {
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                text: p.valueText; style: "subhead"; tnum: true; color: Ios.accent
                Pressable { anchors.fill: parent; anchors.margins: -6; tap: p.edit }
            }
        }
        Item { id: box; width: parent.width; height: childrenRect.height }
    }
}
