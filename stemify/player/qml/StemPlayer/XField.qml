import QtQuick
import QtQuick.Controls.Basic

TextField {
    font.family: uiFont
    font.pixelSize: X.fsUi
    color: X.ink
    selectByMouse: true
    selectionColor: "#B7D7C4"
    selectedTextColor: "#000000"
    leftPadding: 5
    rightPadding: 5
    topPadding: 2
    bottomPadding: 2
    implicitHeight: 22
    background: Rectangle { color: "#FFFFFF"; border.color: parent.activeFocus ? X.green : "#ABABAB" }
}
