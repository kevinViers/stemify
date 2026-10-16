import QtQuick

Column {
    id: e
    property string icon
    property string title
    property string message
    property string action: ""
    property var tap: null
    width: parent ? parent.width : 0
    topPadding: 70
    bottomPadding: 70
    spacing: 0
    Sym { anchors.horizontalCenter: parent.horizontalCenter; name: e.icon; color: Ios.label3; size: 56 }
    Item { width: 1; height: 14 }
    Txt { x: 40; width: parent.width - 80; horizontalAlignment: Text.AlignHCenter; text: e.title; style: "title3"; maximumLineCount: 2 }
    Item { width: 1; height: 6 }
    Txt { x: 40; width: parent.width - 80; horizontalAlignment: Text.AlignHCenter; text: e.message; style: "subhead"; color: Ios.label2; maximumLineCount: 6 }
    Item { width: 1; height: e.action ? 16 : 0 }
    Rectangle {
        visible: e.action !== ""
        anchors.horizontalCenter: parent.horizontalCenter
        width: at.implicitWidth + 40; height: 42; radius: 21
        color: Ios.accent
        Txt { id: at; anchors.centerIn: parent; text: e.action; style: "headline"; color: "white" }
        Pressable { anchors.fill: parent; fade: true; tap: e.tap }
    }
}
