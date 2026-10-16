import QtQuick

Page {
    id: page
    title: "Stem Quality"
    grouped: true
    header: Section {
        footer: "For streaming and new downloads, when the stems aren't on a mounted share. Opus is made once on the server and kept there; 256 kbps is about an eighth of the FLAC size."
        Repeater {
            model: app.qualities
            Cell { title: modelData.label; subtitle: modelData.detail; checked: (app.settings.stemQuality || "high") === modelData.id
                   tap: () => app.set("stemQuality", modelData.id) }
        }
    }
}
