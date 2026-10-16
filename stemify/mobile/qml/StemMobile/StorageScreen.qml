import QtQuick

Page {
    id: page
    title: "Storage"
    grouped: true
    readonly property var limits: [2, 5, 10, 20, 50, 100]
    Component.onCompleted: downloads.refreshUsage()
    header: Column {
        width: page.width
        Section {
            header: "On This Computer"
            Cell { title: "Downloads"; value: Api.gb(downloads.usage.pinned); chevron: true; tap: () => Ui.selectTab("downloads") }
            Cell { title: "Streaming Cache"; value: Api.gb(downloads.usage.cached) }
        }
        Section {
            header: "Streaming Cache Limit"
            footer: "Streamed songs stay for replay and offline use, oldest first out. Downloads don't count."
            CellItem {
                height: 52
                SegmentedControl {
                    x: 16; width: parent.width - 32; anchors.verticalCenter: parent.verticalCenter
                    options: page.limits.map(l => l + " GB")
                    selected: Math.max(0, page.limits.indexOf(app.settings.cacheLimitGb || 10))
                    picked: (i) => app.set("cacheLimitGb", page.limits[i])
                }
            }
        }
        Section {
            Cell { title: "Clear Streaming Cache"; titleColor: Ios.red; centered: true
                   tap: () => Ui.confirm("Clear Cache?", "Downloads are kept.", "Clear", () => downloads.clearCache()) }
        }
    }
}
