import QtQuick

Rectangle {
    id: f
    property alias text: input.text
    property string placeholder: "Search"
    property alias input: input
    height: 38
    radius: 11
    color: Ios.fill
    Sym { id: glass; x: 8; anchors.verticalCenter: parent.verticalCenter; name: "search"; color: Ios.label2; size: 18 }
    Txt { x: 32; anchors.verticalCenter: parent.verticalCenter; visible: input.text === ""; text: f.placeholder; color: Ios.label2 }
    TextInput {
        id: input
        x: 32; width: parent.width - 32 - 30
        anchors.verticalCenter: parent.verticalCenter
        font.family: Ios.font; font.pixelSize: 17
        color: Ios.label
        selectionColor: Ios.accent
        selectByMouse: true
        clip: true
        Keys.onEscapePressed: { if (text) text = ""; else focus = false }
    }
    Rectangle {
        visible: input.text !== ""
        anchors.right: parent.right; anchors.rightMargin: 8; anchors.verticalCenter: parent.verticalCenter
        width: 18; height: 18; radius: 9; color: Ios.label3
        Sym { anchors.centerIn: parent; name: "xmark"; color: Ios.fill; size: 12 }
        Pressable { anchors.fill: parent; tap: () => input.text = "" }
    }
}
