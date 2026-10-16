import QtQuick
import QtQuick.Layouts

// A Settings-style row with an inline text field on the right.
CellItem {
    id: f
    property string label
    property alias text: input.text
    property string placeholder: ""
    property bool secret: false
    height: 46
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        Txt { text: f.label; Layout.preferredWidth: 96 }
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Txt { anchors.fill: parent; visible: input.text === ""; text: f.placeholder; color: Ios.label3; horizontalAlignment: Text.AlignRight }
            TextInput {
                id: input
                anchors.fill: parent
                verticalAlignment: TextInput.AlignVCenter
                horizontalAlignment: TextInput.AlignRight
                font.family: Ios.font; font.pixelSize: 17
                color: Ios.label2
                selectionColor: Ios.accent
                selectByMouse: true
                clip: true
                echoMode: f.secret ? TextInput.Password : TextInput.Normal
            }
        }
    }
}
