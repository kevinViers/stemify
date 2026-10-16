import QtQuick

Rectangle {
    id: t
    property string text
    property bool on: false
    property color onColor: Ios.red
    property var tap: null
    property var pal: Ios
    width: 30
    height: 26
    radius: 7
    color: on ? onColor : pal.fill
    anchors.verticalCenter: parent ? parent.verticalCenter : undefined
    Txt { anchors.centerIn: parent; text: t.text; style: "footnote"; weight: 700; color: t.on ? "black" : t.pal.label2 }
    Pressable { anchors.fill: parent; fade: true; tap: t.tap }
}
