import QtQuick

// Desktop only: the server's shares, when they're mounted here, play straight off the disk.
Page {
    id: page
    title: "Folders"
    grouped: true
    largeTitle: false
    header: Column {
        width: page.width
        Section {
            header: "Mounted Shares"
            footer: "When these folders are mounted, songs and stems play straight from them instead of being fetched from the server."
            FieldCell { id: stems; label: "Stems"; placeholder: "/mnt/Music/Stems"; text: app.settings.stemsDir || "" }
            FieldCell { id: music; label: "Music"; placeholder: "/mnt/Music"; text: app.settings.musicDir || "" }
            Cell { title: "Save"; titleColor: Ios.accent
                   tap: () => { app.set("stemsDir", stems.text.trim()); app.set("musicDir", music.text.trim()); Ui.hud("Saved") } }
        }
    }
}
