import QtQuick

Rectangle {
    id: chip
    property string status: "queued"
    property real progress: 0
    readonly property color tone: status === "done" ? Theme.success
                                : status === "error" ? Theme.error
                                : status === "running" ? Theme.primary : Theme.textLo
    readonly property string label: status === "running" ? Math.floor(progress * 100) + "%"
                                  : status === "done" ? "Done"
                                  : status === "error" ? "Failed"
                                  : status === "cancelled" ? "Cancelled" : "Queued"
    implicitWidth: t.implicitWidth + 16
    implicitHeight: 20
    radius: 10
    color: Theme.alpha(tone, 0.16)
    border.color: Theme.alpha(tone, 0.35)
    Txt {
        id: t
        anchors.centerIn: parent
        text: chip.label
        color: chip.tone
        font.pixelSize: 11
        font.weight: Font.DemiBold
        font.features: { "tnum": 1 }
    }
}
