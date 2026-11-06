import QtQuick
import QtQuick.Controls.Basic

// "Play On", dropped down from the tab strip like Office's Share pane: this computer and every
// Stemify nearby (same Wi-Fi, or paired over Bluetooth). Picking one makes the whole workbook
// (ribbon, mixer pane, sheets, media keys) control it, until This Computer is picked again.
Popup {
    id: pane
    width: 360
    padding: 0
    onOpened: nearby.scan(true)
    onClosed: nearby.scan(false)
    background: Rectangle { color: X.raised; border.color: X.menuBorder }

    function icon(app) { return app === "android" ? "phone" : "desktop" }
    function detail(dev) {
        if (dev.state === "connecting") return "Connecting…"
        if (dev.state === "waiting") return "Allow this computer on " + dev.name + " to finish"
        return dev.appLabel + " · " + (dev.transport === "bt" ? "Bluetooth" : "Wi-Fi")
    }

    contentItem: Column {
        Item {
            width: pane.width
            height: 40
            T { x: 14; height: parent.height; text: "Play On"; font.pixelSize: 18; color: X.greenText }
            Ico {
                anchors.right: parent.right; anchors.rightMargin: 10; anchors.verticalCenter: parent.verticalCenter
                name: "close"; size: 18
                MouseArea { anchors.fill: parent; anchors.margins: -4; onClicked: pane.close() }
            }
        }
        Rectangle { width: pane.width; height: 1; color: X.borderSoft }

        DeviceRow {
            title: "This Computer"
            subtitle: nearby.thisName
            icon: "desktop"
            checked: !nearby.active && !nearby.busy
            onTap: nearby.leave()
        }
        Repeater {
            model: nearby.devices
            delegate: DeviceRow {
                required property var modelData
                title: modelData.name
                subtitle: pane.detail(modelData)
                icon: pane.icon(modelData.app)
                checked: modelData.current && modelData.state === "connected"
                busy: modelData.current && (modelData.state === "connecting" || modelData.state === "waiting")
                onTap: if (!modelData.current) nearby.connectTo(modelData.id)
            }
        }
        DeviceRow {
            visible: nearby.canTransfer
            title: "Move “" + nearby.localTitle + "” There"
            subtitle: "Plays this computer's queue on " + nearby.targetName + ", from where it is now"
            icon: "play_on"
            accent: true
            onTap: { nearby.transfer(); pane.close() }
        }

        Rectangle { width: pane.width; height: 1; color: X.borderSoft }
        T {
            x: 14
            width: pane.width - 28
            topPadding: 8
            bottomPadding: 10
            wrapMode: Text.WordWrap
            elide: Text.ElideNone
            color: X.inkSoft
            text: nearby.devices.length === 0
                  ? "Looking for Stemify on your Wi-Fi and on paired Bluetooth devices. Open Stemify on the other device; it needs “Allow Control from Nearby Devices” on (it is by default)."
                  : nearby.scanning ? "Looking for more devices…"
                  : "Everything in this workbook plays on the device you pick: the ribbon, the mixer pane, the Queue sheet and every Play button."
        }
    }

    component DeviceRow: Item {
        id: r
        property string title
        property string subtitle
        property string icon
        property bool checked: false
        property bool busy: false
        property bool accent: false
        signal tap()
        width: pane.width
        height: visible ? 48 : 0
        Rectangle {
            anchors.fill: parent
            color: ma.pressed ? X.pressed : ma.containsMouse ? X.hoverSoft : r.checked ? X.selFill : "transparent"
        }
        Rectangle { visible: r.checked; width: 3; height: parent.height; color: X.accent }
        Ico { x: 14; anchors.verticalCenter: parent.verticalCenter; name: r.icon; size: 24 }
        Column {
            x: 50
            width: parent.width - 50 - 36
            anchors.verticalCenter: parent.verticalCenter
            T { width: parent.width; text: r.title; font.bold: r.checked; color: r.accent ? X.greenText : X.ink }
            T { width: parent.width; text: r.subtitle; font.pixelSize: X.fsSmall; color: X.inkSoft }
        }
        T {
            visible: r.busy
            anchors.right: parent.right; anchors.rightMargin: 12; height: parent.height
            text: "…"; font.pixelSize: 18; color: X.inkSoft
        }
        Ico { visible: r.checked; name: "check"; size: 16; anchors.right: parent.right; anchors.rightMargin: 14; anchors.verticalCenter: parent.verticalCenter }
        MouseArea { id: ma; anchors.fill: parent; hoverEnabled: true; onClicked: r.tap() }
    }
}
