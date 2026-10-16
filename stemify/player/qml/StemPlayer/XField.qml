import QtQuick
import QtQuick.Controls.Basic

TextField {
    font.family: uiFont
    font.pixelSize: X.fsUi
    color: X.ink
    selectByMouse: true
    selectionColor: X.selField
    selectedTextColor: X.cellText
    leftPadding: 5
    rightPadding: 5
    topPadding: 2
    bottomPadding: 2
    implicitHeight: 22
    background: Rectangle { color: X.paper; border.color: parent.activeFocus ? X.accent : X.borderStrong }
}
