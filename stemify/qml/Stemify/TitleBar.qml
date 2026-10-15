import QtQuick
import QtQuick.Controls.Basic

GlassPanel {
    id: bar
    property var win: Window.window
    signal openSettings()
    height: 52
    topLeftRadius: win.radius
    topRightRadius: win.radius

    // Drag to move / double-click to maximize via the compositor (Wayland-native).
    DragHandler {
        target: null
        grabPermissions: PointerHandler.CanTakeOverFromAnything
        onActiveChanged: if (active) bar.win.startSystemMove()
    }
    TapHandler {
        acceptedButtons: Qt.LeftButton
        onDoubleTapped: bar.win.toggleMaximized()
    }

    Row {
        anchors { left: parent.left; leftMargin: 16; verticalCenter: parent.verticalCenter }
        spacing: 10
        Rectangle {
            width: 28
            height: 28
            radius: 8
            anchors.verticalCenter: parent.verticalCenter
            gradient: Gradient {
                GradientStop { position: 0; color: "#5FC8FF" }
                GradientStop { position: 1; color: "#2A7BD8" }
            }
            border.color: Qt.rgba(1, 1, 1, 0.3)
            Rectangle {
                anchors { left: parent.left; right: parent.right; top: parent.top; margins: 1 }
                height: 12
                topLeftRadius: 7
                topRightRadius: 7
                color: Qt.rgba(1, 1, 1, 0.25)
            }
            Icon { anchors.centerIn: parent; name: "equalizer"; size: 18; color: "#FFFFFF" }
        }
        Txt {
            text: "Stemify"
            font.pixelSize: Theme.fs16
            font.weight: Font.DemiBold
            anchors.verticalCenter: parent.verticalCenter
        }
        Rectangle {  // engine status
            anchors.verticalCenter: parent.verticalCenter
            height: 22
            width: st.implicitWidth + 26
            radius: 11
            readonly property color tone: app.workerState === "ready" ? Theme.success
                                        : app.workerState === "busy" ? Theme.primary
                                        : app.workerState === "crashed" ? Theme.error : Theme.warning
            color: Theme.alpha(tone, 0.12)
            border.color: Theme.alpha(tone, 0.3)
            Rectangle {
                x: 9
                anchors.verticalCenter: parent.verticalCenter
                width: 7
                height: 7
                radius: 4
                color: parent.tone
            }
            Txt {
                id: st
                x: 20
                anchors.verticalCenter: parent.verticalCenter
                font.pixelSize: 11
                font.weight: Font.Medium
                color: parent.tone
                text: app.workerState === "ready" ? "Engine ready"
                    : app.workerState === "busy" ? "Separating"
                    : app.workerState === "crashed" ? "Engine restarting" : "Starting engine"
            }
        }
    }

    Row {
        anchors { right: parent.right; rightMargin: 10; verticalCenter: parent.verticalCenter }
        spacing: 8

        AeroComboBox {
            id: modelPicker
            width: 240
            caption: "MODEL"
            iconName: "layers"
            implicitHeight: 40
            subRole: "note"
            popupWidth: 300
            model: app.models
            currentIndex: app.models.findIndex(m => m.id === appSettings.modelId)
            onActivated: appSettings.modelId = currentValue
            anchors.verticalCenter: parent.verticalCenter
        }

        AbstractButton {  // 2-stem vocals toggle chip
            id: twoStem
            readonly property var model: app.models.find(m => m.id === appSettings.modelId)
            readonly property bool applicable: !!model && model.stems > 2
            anchors.verticalCenter: parent.verticalCenter
            checkable: true
            checked: appSettings.twoStems && applicable
            enabled: applicable
            onToggled: appSettings.twoStems = checked
            hoverEnabled: true
            implicitHeight: 32
            implicitWidth: twoLbl.implicitWidth + 40
            opacity: enabled ? 1 : 0.4
            background: Rectangle {
                radius: 16
                color: twoStem.checked ? Theme.alpha(Theme.stemColor("vocals"), 0.2) : (twoStem.hovered ? Qt.rgba(1, 1, 1, 0.08) : Qt.rgba(1, 1, 1, 0.04))
                border.color: twoStem.checked ? Theme.alpha(Theme.stemColor("vocals"), 0.6) : Qt.rgba(1, 1, 1, 0.10)
                Behavior on color { ColorAnimation { duration: Theme.fast } }
            }
            contentItem: Row {
                leftPadding: 12
                spacing: 6
                Icon {
                    name: twoStem.checked ? "check" : "music"
                    size: 16
                    color: twoStem.checked ? Theme.stemColor("vocals") : Theme.textLo
                    anchors.verticalCenter: parent.verticalCenter
                }
                Txt {
                    id: twoLbl
                    text: "Vocals only"
                    font.pixelSize: Theme.fs12
                    font.weight: Font.Medium
                    color: twoStem.checked ? Theme.textHi : Theme.textLo
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
            Tip { visible: twoStem.hovered; text: twoStem.applicable ? "2-stem mode: vocals + instrumental" : "This model is already 2-stem" }
        }

        AeroComboBox {
            id: devicePicker
            width: 250
            caption: app.vram.valid === true && appSettings.deviceId !== "cpu"
                     ? "DEVICE  ·  " + (app.vram.free / 1024).toFixed(1) + " GB FREE" : "DEVICE"
            iconName: "chip"
            implicitHeight: 40
            popupWidth: 300
            model: app.devices
            currentIndex: Math.max(0, app.devices.findIndex(d => d.id === appSettings.deviceId))
            onActivated: appSettings.deviceId = currentValue
            anchors.verticalCenter: parent.verticalCenter

            HoverHandler { id: devHover }
            Tip { visible: devHover.hovered && app.vram.valid === true; text: app.vram.text || "" }
        }

        IconButton {
            anchors.verticalCenter: parent.verticalCenter
            iconName: "settings"
            size: 36
            tip: "Settings  (Ctrl+,)"
            onClicked: bar.openSettings()
        }

        Rectangle { width: 1; height: 24; color: Theme.hairline; anchors.verticalCenter: parent.verticalCenter }

        Row {
            anchors.verticalCenter: parent.verticalCenter
            spacing: 2
            CaptionButton { iconName: "minimize"; onClicked: bar.win.showMinimized() }
            CaptionButton { iconName: bar.win.maximized ? "restore" : "maximize"; onClicked: bar.win.toggleMaximized() }
            CaptionButton { iconName: "close"; danger: true; onClicked: bar.win.close() }
        }
    }

    Rectangle {  // separator to content (elevation 1 edge)
        anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
        height: 1
        color: Theme.hairline
    }
}
