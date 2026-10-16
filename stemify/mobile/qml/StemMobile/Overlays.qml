import QtQuick

// App-wide modal UI: action sheets, alerts (optionally with a text field) and the HUD.
Item {
    id: o
    anchors.fill: parent
    property var sheet: null   // {title, actions}
    property var spec: null    // alert
    property string hudText: ""
    property string hudIcon: ""
    readonly property bool busy: sheetHost.visible || alertHost.visible

    function actions(title, list) { sheet = { title: title, actions: list }; sheetIn.restart() }
    function alert(s) { spec = s; field.text = s.field || ""; alertIn.restart(); if (s.hasField) focusTimer.restart() }
    function hud(text, icon) { hudText = text; hudIcon = icon || ""; hudAnim.restart() }
    function closeSheet(then) { sheetOut.then = then || null; sheetOut.restart() }
    function closeAlert() { spec = null; field.focus = false }
    function confirmAlert() {
        var s = spec
        if (!s || (s.hasField && !field.text.trim())) return
        closeAlert()
        if (s.run) s.run(field.text.trim())
    }
    // Esc: alerts, then sheets.
    function back() {
        if (spec) { closeAlert(); return true }
        if (sheet) { closeSheet(); return true }
        return false
    }

    // ---- action sheet (UIAlertController .actionSheet): grouped buttons over a scrim, Cancel apart ----
    Item {
        id: sheetHost
        anchors.fill: parent
        visible: o.sheet !== null
        property real shown: 0
        NumberAnimation { id: sheetIn; target: sheetHost; property: "shown"; from: 0; to: 1; duration: 260; easing.type: Easing.OutCubic }
        SequentialAnimation {
            id: sheetOut
            property var then: null
            NumberAnimation { target: sheetHost; property: "shown"; to: 0; duration: 180; easing.type: Easing.InCubic }
            ScriptAction { script: { var t = sheetOut.then; o.sheet = null; if (t) t() } }
        }
        Rectangle {
            anchors.fill: parent; color: Ios.scrim; opacity: sheetHost.shown
            MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: o.closeSheet(); onWheel: {} }
        }
        Column {
            id: sheetBody
            width: Math.min(parent.width - 16, 520)
            anchors.horizontalCenter: parent.horizontalCenter
            y: parent.height - 8 - height + (1 - sheetHost.shown) * (height + 16)
            spacing: 8
            Rectangle {
                width: parent.width
                height: Math.min(list.contentHeight, sheetHost.height - 57 - 40)
                radius: 14
                color: Ios.elevated
                clip: true
                Flickable {
                    id: list
                    anchors.fill: parent
                    contentHeight: listCol.height
                    boundsBehavior: Flickable.StopAtBounds
                    Column {
                        id: listCol
                        width: parent.width
                        Item {
                            visible: !!o.sheet && !!o.sheet.title
                            width: parent.width
                            height: visible ? sheetTitle.height + 28 : 0
                            Txt { id: sheetTitle; anchors.centerIn: parent; width: parent.width - 32; horizontalAlignment: Text.AlignHCenter
                                  text: o.sheet ? o.sheet.title || "" : ""; style: "footnote"; weight: 600; color: Ios.label2; maximumLineCount: 2 }
                            Hairline { anchors.bottom: parent.bottom; width: parent.width }
                        }
                        Repeater {
                            model: o.sheet ? o.sheet.actions : []
                            Item {
                                width: listCol.width
                                height: 57
                                Rectangle { anchors.fill: parent; color: ma.pressed ? Ios.cellPressed : ma.containsMouse ? Ios.alpha(Ios.cellPressed, 0.5) : "transparent" }
                                Hairline { visible: index > 0; width: parent.width }
                                Txt { anchors.centerIn: parent; width: parent.width - 24; horizontalAlignment: Text.AlignHCenter
                                      text: modelData.title; font.pixelSize: 20; color: modelData.destructive ? Ios.red : Ios.accent }
                                MouseArea { id: ma; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: o.closeSheet(modelData.run) }
                            }
                        }
                    }
                }
            }
            Rectangle {
                width: parent.width; height: 57; radius: 14
                color: cancel.pressed ? Ios.cellPressed : Ios.elevated
                Txt { anchors.centerIn: parent; text: "Cancel"; font.pixelSize: 20; weight: 600; color: Ios.accent }
                MouseArea { id: cancel; anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: o.closeSheet() }
            }
        }
    }

    // ---- alert (UIAlertController .alert) ----
    Item {
        id: alertHost
        anchors.fill: parent
        visible: o.spec !== null
        property real shown: 0
        NumberAnimation { id: alertIn; target: alertHost; property: "shown"; from: 0; to: 1; duration: 220; easing.type: Easing.OutBack }
        Timer { id: focusTimer; interval: 30; onTriggered: field.forceActiveFocus() }
        Rectangle {
            anchors.fill: parent; color: Ios.scrim; opacity: Math.min(1, alertHost.shown)
            MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: {} }
        }
        Rectangle {
            id: box
            width: 280
            height: alertCol.height + 44
            anchors.centerIn: parent
            radius: 14
            color: Ios.elevated
            opacity: Math.min(1, alertHost.shown)
            scale: 1.15 - 0.15 * alertHost.shown
            Column {
                id: alertCol
                x: 16; y: 18; width: parent.width - 32
                spacing: 4
                Txt { width: parent.width; horizontalAlignment: Text.AlignHCenter; text: o.spec ? o.spec.title : ""; style: "headline"; maximumLineCount: 3 }
                Txt { visible: !!o.spec && !!o.spec.message; width: parent.width; horizontalAlignment: Text.AlignHCenter
                      text: o.spec ? o.spec.message || "" : ""; style: "footnote"; maximumLineCount: 8 }
                Item { visible: !!o.spec && !!o.spec.hasField; width: 1; height: 10 }
                Rectangle {
                    visible: !!o.spec && !!o.spec.hasField
                    width: parent.width; height: 34; radius: 7
                    color: Ios.dark ? Ios.fill : "white"
                    border.width: 0.5; border.color: Ios.separator
                    Txt { x: 8; anchors.verticalCenter: parent.verticalCenter; visible: field.text === ""; text: o.spec ? o.spec.placeholder || "" : ""; style: "subhead"; color: Ios.label3 }
                    TextInput {
                        id: field
                        x: 8; width: parent.width - 16; anchors.verticalCenter: parent.verticalCenter
                        font.family: Ios.font; font.pixelSize: 15
                        color: Ios.label; selectionColor: Ios.accent; selectByMouse: true; clip: true
                        Keys.onReturnPressed: o.confirmAlert()
                        Keys.onEnterPressed: o.confirmAlert()
                        Keys.onEscapePressed: o.closeAlert()
                    }
                }
                Item { width: 1; height: 14 }
            }
            Hairline { y: box.height - 44; width: parent.width }
            Row {
                y: box.height - 44
                width: parent.width
                height: 44
                Item {
                    width: parent.width / 2; height: 44
                    Rectangle { anchors.fill: parent; color: c1.pressed ? Ios.cellPressed : "transparent"; bottomLeftRadius: 14 }
                    Txt { anchors.centerIn: parent; text: "Cancel"; color: Ios.accent }
                    MouseArea { id: c1; anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: o.closeAlert() }
                }
                Rectangle { width: 0.5; height: 44; color: Ios.separator }
                Item {
                    readonly property bool ok: !!o.spec && (!o.spec.hasField || field.text.trim() !== "")
                    width: parent.width / 2 - 0.5; height: 44
                    Rectangle { anchors.fill: parent; color: c2.pressed ? Ios.cellPressed : "transparent"; bottomRightRadius: 14 }
                    Txt { anchors.centerIn: parent; text: o.spec ? o.spec.confirm : ""; weight: 600
                          color: !parent.ok ? Ios.label3 : o.spec && o.spec.destructive ? Ios.red : Ios.accent }
                    MouseArea { id: c2; anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: o.confirmAlert() }
                }
            }
        }
    }

    // ---- HUD: the square "Added to Library" style message ----
    Rectangle {
        id: hudBox
        anchors.centerIn: parent
        width: Math.max(150, Math.min(270, hudCol.implicitWidth + 40))
        height: hudCol.height + 40
        radius: 16
        color: Ios.dark ? "#2C2C2E" : "#E5E5EA"
        opacity: 0
        scale: 0.9 + 0.1 * opacity
        visible: opacity > 0
        Column {
            id: hudCol
            anchors.centerIn: parent
            width: Math.min(230, Math.max(hudLabel.implicitWidth, 44))
            spacing: 10
            Sym { visible: o.hudIcon !== ""; anchors.horizontalCenter: parent.horizontalCenter; name: o.hudIcon; color: Ios.label2; size: 44 }
            Txt { id: hudLabel; width: parent.width; horizontalAlignment: Text.AlignHCenter; text: o.hudText; style: "headline"; color: Ios.label2; maximumLineCount: 4 }
        }
        SequentialAnimation {
            id: hudAnim
            NumberAnimation { target: hudBox; property: "opacity"; to: 1; duration: 150 }
            PauseAnimation { duration: o.hudText.length > 40 ? 2800 : 1400 }
            NumberAnimation { target: hudBox; property: "opacity"; to: 0; duration: 250 }
        }
    }
}
