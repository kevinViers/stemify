import QtQuick

Page {
    id: page
    title: "Settings"
    grouped: true
    readonly property var s: app.settings
    readonly property var q: app.qualities.find(x => x.id === s.stemQuality) || app.qualities[1]
    header: Column {
        width: page.width
        Section {
            header: "Servers"; inset: 60
            Cell { title: "Plex"; icon: "server"; iconBg: "#E5A00D"; chevron: true
                   value: !page.s.token ? "Not Signed In" : page.s.musicSectionTitle || page.s.plexUser || ""
                   tap: () => Ui.push("PlexAccountScreen") }
            Cell { title: "Stem Server"; icon: "waveform"; iconBg: "#AF52DE"; chevron: true
                   value: app.status.state === "online" ? app.status.tracks + " songs" : app.status.state === "offline" ? "Offline" : "…"
                   tap: () => Ui.push("StemServerScreen") }
        }
        Section {
            header: "Sound"; inset: 60
            Cell { title: "Equalizer"; icon: "sliders"; iconBg: "#34C759"; chevron: true
                   value: !eq.info.enabled ? "Off" : eq.profile.name || "On"
                   tap: () => Ui.push("EqScreen") }
            Cell { title: "Stem Quality"; icon: "gauge"; iconBg: "#FF2D55"; chevron: true; value: page.q.label; tap: () => Ui.push("QualityScreen") }
            Cell { title: "Keep Stem Mix Between Songs"; icon: "speakerWave"; iconBg: "#5856D6"
                   IosSwitch { checked: !!page.s.keepMix; toggled: (v) => app.set("keepMix", v) } }
        }
        Section {
            header: "Storage"; inset: 60
            Cell { title: "Cache & Downloads"; icon: "externalDrive"; iconBg: "#8E8E93"; chevron: true
                   value: Api.gb(downloads.usage.pinned + downloads.usage.cached); tap: () => Ui.push("StorageScreen") }
            Cell { title: "Music & Stems Folders"; icon: "folder"; iconBg: "#30B0C7"; chevron: true; tap: () => Ui.push("FoldersScreen") }
        }
        Section {
            header: "Display"; inset: 60
            footer: "Stemify 1.1 for the desktop · stems by stemd on your server"
            Cell { title: "Appearance"; icon: "paintbrush"; iconBg: "#007AFF"; chevron: true
                   value: (Ios.modes.find(m => m[0] === Ios.mode) || ["", ""])[1]; tap: () => Ui.push("AppearanceScreen") }
        }
    }
}
