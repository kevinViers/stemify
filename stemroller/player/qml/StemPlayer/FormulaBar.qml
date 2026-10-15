import QtQuick

// Name box, the ✕ ✓ fx buttons, and the formula of the active cell.
Rectangle {
    id: fb
    property var win: Window.window
    height: 30
    color: "#FFFFFF"
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.ribbonLine }

    Rectangle {
        id: nameBox
        x: 4
        y: 4
        width: 110
        height: 22
        border.color: "#C6C6C6"
        T {
            x: 5
            height: parent.height
            width: parent.width - 20
            text: fb.win.multiSel ? (fb.win.selBottom - fb.win.selTop + 1) + "R x " + (Math.abs(fb.win.curCol - fb.win.anchorCol) + 1) + "C"
                                  : X.colName(fb.win.curCol) + (fb.win.curRow + 2)
        }
        Ico { anchors.right: parent.right; anchors.rightMargin: 4; anchors.verticalCenter: parent.verticalCenter; name: "dropdown"; size: 10 }
    }
    Row {
        x: nameBox.x + nameBox.width + 10
        height: parent.height
        spacing: 12
        Ico { anchors.verticalCenter: parent.verticalCenter; name: "cancel"; size: 14; opacity: 0.35 }
        Ico { anchors.verticalCenter: parent.verticalCenter; name: "check"; size: 14; opacity: 0.35 }
        Ico { anchors.verticalCenter: parent.verticalCenter; name: "function"; size: 18 }
    }
    Rectangle {
        x: nameBox.x + nameBox.width + 92
        y: 4
        width: parent.width - x - 6
        height: 22
        border.color: "#C6C6C6"
        T {
            x: 6
            width: parent.width - 12
            height: parent.height
            font.family: cellFont
            font.pixelSize: 14
            color: "#000000"
            text: fb.win.formulaText
        }
    }
}
