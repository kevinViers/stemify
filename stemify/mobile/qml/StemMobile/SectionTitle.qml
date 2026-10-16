import QtQuick

// Section title with an optional accent action, as in Apple Music ("Recently Added").
Item {
    id: s
    property string text
    property string action: ""
    property var tap: null
    width: parent ? parent.width : 0
    height: 22 + 28 + 8
    Txt { x: Ios.margin; anchors.bottom: parent.bottom; anchors.bottomMargin: 8; text: s.text; style: "title2"; width: parent.width - 2 * Ios.margin - (act.visible ? act.width : 0) }
    TextButton { id: act; visible: s.action !== ""; text: s.action; tap: s.tap; anchors.right: parent.right; anchors.rightMargin: 12; anchors.bottom: parent.bottom }
}
