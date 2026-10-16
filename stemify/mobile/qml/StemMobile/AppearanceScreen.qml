import QtQuick

Page {
    id: page
    title: "Appearance"
    grouped: true
    header: Column {
        width: page.width
        Section {
            header: "Theme"
            footer: "Black is true black for OLED. No gradients anywhere, by design."
            CellItem {
                height: 200
                Row {
                    anchors.centerIn: parent
                    spacing: (parent.width - 3 * 78) / 4
                    Repeater {
                        model: Ios.modes
                        Item {
                            id: tile
                            readonly property var p: Ios.palettes[modelData[0]]
                            readonly property bool on: modelData[0] === Ios.mode
                            width: 78; height: 170
                            Rectangle {
                                width: 78; height: 140; radius: 12; color: tile.p.groupedBg
                                border.width: tile.on ? 3 : 1; border.color: tile.on ? Ios.accent : Ios.separator
                                Column {
                                    x: 8; y: 8; width: parent.width - 16; spacing: 6
                                    Rectangle { width: 40; height: 6; radius: 3; color: tile.p.label }
                                    Repeater { model: 3; Rectangle { width: 62; height: 18; radius: 4; color: tile.p.cell } }
                                    Rectangle { width: 24; height: 6; radius: 3; color: Ios.accentFor(Ios.accentName, modelData[0] !== "white") }
                                }
                            }
                            Txt { y: 148; anchors.horizontalCenter: parent.horizontalCenter; text: modelData[1]; style: "subhead"; weight: tile.on ? 600 : -1 }
                            Pressable { anchors.fill: parent; tap: () => app.set("theme", modelData[0]) }
                        }
                    }
                }
            }
        }
        Section {
            header: "Accent"
            CellItem {
                height: 64
                Row {
                    anchors.centerIn: parent
                    spacing: Math.max(6, (parent.width - 24 - 9 * 32) / 8)
                    Repeater {
                        model: Ios.accentOrder
                        Rectangle {
                            width: 32; height: 32; radius: 16
                            color: Ios.accentFor(modelData, Ios.dark)
                            border.width: modelData === Ios.accentName ? 3 : 0
                            border.color: Ios.label
                            Pressable { anchors.fill: parent; tap: () => app.set("accent", modelData) }
                        }
                    }
                }
            }
        }
        Section {
            header: "Now Playing"
            footer: "Paints the Now Playing screen in one solid colour taken from the album art."
            Cell { title: "Album Colour Background"
                   IosSwitch { checked: !!app.settings.albumColor; toggled: (v) => app.set("albumColor", v) } }
        }
    }
}
