import QtQuick

// Off, Smart (Short / Medium / Long), or Fixed seconds; as on the phone.
Page {
    id: page
    title: "Crossfade"
    grouped: true
    readonly property string mode: app.settings.crossfade || "off"
    readonly property int seconds: app.settings.crossfadeSeconds || 6
    header: Column {
        width: page.width
        Section {
            Cell { title: "Off"; subtitle: "Gapless: each song straight into the next"; checked: page.mode === "off"; tap: () => app.set("crossfade", "off") }
        }
        Section {
            header: "Smart"
            footer: "Listens to the end of one song and the start of the next: skips the silence between them, crosses a song that fades out over its fade, and lets a quiet intro start under a cold ending. The length is the most the two will overlap."
            Repeater {
                model: [["short", "Short", "Up to 4 seconds"], ["medium", "Medium", "Up to 7 seconds"], ["long", "Long", "Up to 12 seconds"]]
                Cell { title: modelData[1]; subtitle: modelData[2]; checked: page.mode === modelData[0]; tap: () => app.set("crossfade", modelData[0]) }
            }
        }
        Section {
            header: "Fixed"
            footer: "Always overlaps by this long, silence at either end trimmed."
            Cell { title: "Fixed"; value: page.seconds + " s"; checked: page.mode === "fixed"; tap: () => app.set("crossfade", "fixed") }
            CellItem {
                height: 48
                Txt { x: 16; anchors.verticalCenter: parent.verticalCenter; text: "1 s"; style: "footnote"; color: Ios.label2 }
                IosSlider {
                    x: 48; width: parent.width - 96; anchors.verticalCenter: parent.verticalCenter
                    value: (page.seconds - 1) / 11
                    moved: (v) => { app.set("crossfadeSeconds", 1 + Math.round(v * 11)); app.set("crossfade", "fixed") }
                }
                Txt { anchors.right: parent.right; anchors.rightMargin: 16; anchors.verticalCenter: parent.verticalCenter; text: "12 s"; style: "footnote"; color: Ios.label2 }
            }
        }
        Section {
            footer: "Off keeps an album's songs gapless when they play in order, as live and concept albums are meant to."
            Cell { title: "Crossfade Within Albums"
                   IosSwitch { checked: !!app.settings.crossfadeAlbums; toggled: (v) => app.set("crossfadeAlbums", v) } }
        }
    }
}
