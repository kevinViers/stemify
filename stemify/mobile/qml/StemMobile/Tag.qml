import QtQuick

// Small outlined tag, e.g. NO STEMS next to a song.
Rectangle {
    property alias text: t.text
    property color tint: Ios.label2
    width: t.implicitWidth + 8
    height: 15
    radius: 4
    color: "transparent"
    border.width: 1
    border.color: tint
    Txt { id: t; anchors.centerIn: parent; style: "caption2"; weight: 700; color: parent.tint; lineHeight: 13 }
}
