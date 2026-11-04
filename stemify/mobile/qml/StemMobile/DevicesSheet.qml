import QtQuick

// "Play On": this computer and every Stemify nearby (same Wi-Fi, or paired over Bluetooth).
// Picking one makes every screen control it, until you pick This Computer again.
Item {
    id: d
    anchors.fill: parent
    visible: shown > 0
    property real shown: 0
    property bool isOpen: false

    function open() { if (isOpen) return; isOpen = true; nearby.scan(true); inAnim.restart() }
    function close() { if (!isOpen) return; isOpen = false; nearby.scan(false); outAnim.restart() }
    NumberAnimation { id: inAnim; target: d; property: "shown"; from: 0; to: 1; duration: 260; easing.type: Easing.OutCubic }
    NumberAnimation { id: outAnim; target: d; property: "shown"; to: 0; duration: 180; easing.type: Easing.InCubic }

    function icon(app) { return app === "android" ? "phone" : "desktop" }
    function detail(dev) {
        if (dev.state === "connecting") return "Connecting…"
        if (dev.state === "waiting") return "Allow this computer on " + dev.name + " to finish"
        return dev.appLabel + " · " + (dev.transport === "bt" ? "Bluetooth" : "Wi-Fi")
    }

    Rectangle {
        anchors.fill: parent; color: Ios.scrim; opacity: d.shown
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: d.close(); onWheel: {} }
    }
    Rectangle {
        id: card
        width: Math.min(parent.width - 16, 520)
        height: Math.min(body.height, d.height - 40)
        anchors.horizontalCenter: parent.horizontalCenter
        y: parent.height - 8 - height + (1 - d.shown) * (height + 16)
        radius: 14
        color: Ios.elevated
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: {} }
        Flickable {
            anchors.fill: parent
            contentHeight: body.height
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: body
                width: card.width
                Item {
                    width: parent.width; height: 52
                    Txt { anchors.centerIn: parent; text: "Play On"; style: "headline" }
                    Txt { anchors.right: parent.right; anchors.rightMargin: 16; anchors.verticalCenter: parent.verticalCenter
                          text: "Done"; weight: 600; color: Ios.accent
                          MouseArea { anchors.fill: parent; anchors.margins: -8; cursorShape: Qt.PointingHandCursor; onClicked: d.close() } }
                    Hairline { anchors.bottom: parent.bottom; width: parent.width }
                }
                DeviceRow {
                    title: "This Computer"
                    subtitle: nearby.thisName
                    icon: "desktop"
                    checked: !nearby.active && !nearby.busy
                    tap: () => nearby.leave()
                }
                Repeater {
                    model: nearby.devices
                    DeviceRow {
                        title: modelData.name
                        subtitle: d.detail(modelData)
                        icon: d.icon(modelData.app)
                        checked: modelData.current && modelData.state === "connected"
                        busy: modelData.current && (modelData.state === "connecting" || modelData.state === "waiting")
                        tap: () => modelData.current ? null : nearby.connectTo(modelData.id)
                    }
                }
                DeviceRow {
                    visible: nearby.canTransfer
                    title: "Move “" + nearby.localTitle + "” Here"
                    subtitle: "Plays this computer's queue on " + nearby.targetName + ", from where it is now"
                    icon: "airplay"
                    accent: true
                    tap: () => { nearby.transfer(); d.close() }
                }
                Item {
                    width: parent.width
                    height: foot.height + 28
                    Row {
                        id: foot
                        x: 16; y: 14
                        width: parent.width - 32
                        spacing: 8
                        Spinner { visible: nearby.scanning; size: 14; anchors.verticalCenter: parent.verticalCenter }
                        Txt {
                            width: parent.width - (nearby.scanning ? 22 : 0)
                            style: "footnote"; color: Ios.label2; maximumLineCount: 4
                            text: nearby.devices.length === 0
                                  ? "Looking for Stemify on your Wi-Fi and on paired Bluetooth devices. Open Stemify on the other device; it needs “Allow Control from Nearby Devices” on (it is by default)."
                                  : nearby.scanning ? "Looking for more devices…" : "Everything here plays on the device you pick: the mixer, queue, EQ and every Play button."
                        }
                    }
                }
            }
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
        property var tap: null
        width: body.width
        height: visible ? 60 : 0
        Rectangle { anchors.fill: parent; color: ma.pressed ? Ios.cellPressed : ma.containsMouse ? Ios.alpha(Ios.cellPressed, 0.5) : "transparent" }
        Rectangle {
            x: 16; width: 36; height: 36; radius: 8; anchors.verticalCenter: parent.verticalCenter
            color: r.checked || r.accent ? Ios.accent : Ios.fill
            Sym { anchors.centerIn: parent; name: r.icon; size: 20; color: r.checked || r.accent ? "white" : Ios.label2 }
        }
        Column {
            x: 64; width: parent.width - 64 - 48
            anchors.verticalCenter: parent.verticalCenter
            Txt { width: parent.width; text: r.title; color: r.checked || r.accent ? Ios.accent : Ios.label; weight: r.checked ? 600 : 400 }
            Txt { width: parent.width; text: r.subtitle; style: "footnote"; color: Ios.label2; maximumLineCount: 2 }
        }
        Spinner { visible: r.busy; size: 18; anchors.right: parent.right; anchors.rightMargin: 18; anchors.verticalCenter: parent.verticalCenter }
        Sym { visible: r.checked; name: "check"; color: Ios.accent; size: 18; anchors.right: parent.right; anchors.rightMargin: 18; anchors.verticalCenter: parent.verticalCenter }
        Hairline { anchors.bottom: parent.bottom; x: 64; width: parent.width - 64 }
        MouseArea { id: ma; anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor; onClicked: if (r.tap) r.tap() }
    }
}
