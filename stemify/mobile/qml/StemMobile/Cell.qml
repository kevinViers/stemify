import QtQuick
import QtQuick.Layouts

// A table cell: optional icon, title/subtitle, grey value, then chevron / check / custom accessory.
CellItem {
    id: c
    property string title
    property string subtitle: ""
    property string value: ""
    property string icon: ""
    property color iconBg: Ios.accent
    property color titleColor: Ios.label
    property bool chevron: false
    property bool checked: false
    property bool centered: false
    property var tap: null
    property var hold: null
    default property alias accessory: acc.data
    height: Math.max(subtitle ? 56 : 44, row.implicitHeight + 16)
    highlighted: area.pressed && area.pressedButtons & Qt.LeftButton && !!tap

    Pressable { id: area; anchors.fill: parent; tap: c.tap; hold: c.hold; enabled: !!c.tap || !!c.hold }
    RowLayout {
        id: row
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        spacing: 0
        SettingsIcon { visible: c.icon !== ""; icon: c.icon; color: c.iconBg; Layout.rightMargin: 14 }
        Item { visible: c.centered; Layout.fillWidth: true }
        Column {
            Layout.fillWidth: !c.centered
            Layout.preferredWidth: c.centered ? titleText.implicitWidth : -1
            Layout.alignment: Qt.AlignVCenter
            Txt { id: titleText; width: c.centered ? implicitWidth : Math.min(implicitWidth, parent.width); text: c.title; color: c.titleColor }
            Txt { visible: c.subtitle !== ""; width: parent.width; text: c.subtitle; style: "footnote"; color: Ios.label2; maximumLineCount: 2 }
        }
        Item { visible: c.centered; Layout.fillWidth: true }
        Txt {
            visible: c.value !== ""
            text: c.value; color: Ios.label2
            Layout.leftMargin: 8
            Layout.maximumWidth: 220
        }
        Row { id: acc; Layout.leftMargin: children.length ? 8 : 0; Layout.alignment: Qt.AlignVCenter }
        Sym { visible: c.checked; name: "check"; color: Ios.accent; size: 18; Layout.leftMargin: 8 }
        Chevron { visible: c.chevron; Layout.leftMargin: 8 }
    }
}
