import QtQuick

// Stemify Connect: who may control this computer, and controlling another device from here.
Page {
    id: page
    title: "Nearby Devices"
    grouped: true
    header: Column {
        width: page.width
        Section {
            header: "Control Another Device"
            footer: "Phones and computers running Stemify on the same Wi-Fi, or paired with this computer over Bluetooth. While one is picked, every screen here plays, mixes and queues music on it."
            Cell { title: "Play On"; icon: "airplay"; iconBg: "#007AFF"; chevron: true
                   value: nearby.active ? nearby.targetName : nearby.busy ? "Connecting…" : "This Computer"
                   tap: () => Ui.devices() }
        }
        Section {
            header: "This Computer"
            footer: "Other devices find it as “" + receiver.name + "”. The first time a device on your Wi-Fi tries, you're asked here; Bluetooth devices are trusted once paired."
            Cell { title: "Allow Control from Nearby Devices"
                   IosSwitch { checked: receiver.enabledOn; toggled: (v) => receiver.setEnabled(v) } }
            Cell { title: "Over Bluetooth"; visible: receiver.enabledOn
                   IosSwitch { checked: receiver.bluetoothOn; toggled: (v) => receiver.setBluetooth(v) } }
        }
        Section {
            visible: receiver.controllers.length > 0
            header: "Controlling This Computer Now"
            Repeater {
                model: receiver.controllers
                Cell { title: modelData.name; value: modelData.transport === "bt" ? "Bluetooth" : "Wi-Fi" }
            }
        }
        Section {
            visible: receiver.allowed.length > 0
            header: "Allowed on Wi-Fi"
            footer: "Remove a device to be asked again next time it connects."
            Repeater {
                model: receiver.allowed
                Cell { title: modelData.name; chevron: false
                       tap: () => Ui.confirm("Remove " + modelData.name + "?", "It will have to ask again to control this computer.", "Remove",
                                             () => receiver.forget(modelData.id)) }
            }
        }
    }
}
