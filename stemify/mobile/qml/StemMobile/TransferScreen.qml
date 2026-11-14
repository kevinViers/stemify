import QtQuick
import QtQuick.Dialogs

// Moving to a new computer: this one saves a backup file (settings, EQ, queue and mix, and the
// list of downloads), it travels however files do, and the new one restores it and downloads
// the same music from the server.
Page {
    id: page
    title: "Transfer"
    grouped: true
    readonly property int songs: downloads.tracks.length

    FileDialog {
        id: saveDialog
        fileMode: FileDialog.SaveFile
        title: "Save Stemify Backup"
        nameFilters: ["Stemify backup (*.json)"]
        defaultSuffix: "json"
        onAccepted: {
            const err = transfer.save(selectedFile.toString())
            if (err) Ui.hud("Couldn't save the backup", "warning"); else Ui.hud("Saved")
        }
    }
    FileDialog {
        id: openDialog
        fileMode: FileDialog.OpenFile
        title: "Restore from a Backup"
        nameFilters: ["Stemify backup (*.json)", "All files (*)"]
        onAccepted: page.offer(selectedFile.toString())
    }

    // Reads a backup, says what's in it, and restores it once confirmed.
    function offer(path) {
        const r = transfer.open(path)
        if (!r.ok) { Ui.hud(r.error, "warning"); return }
        Ui.confirm(r.title, r.message, "Restore", () => { transfer.restore(); Ui.hud("Restored") }, false)
    }

    header: Column {
        width: page.width
        Section {
            header: "From This Computer"
            footer: "Settings, EQ profiles, the queue and its stem mix, and the list of what's downloaded (" + Api.songs(page.songs) + "). " +
                    "The new computer downloads the music itself from your server. " +
                    "The file includes your Plex and stem server sign-ins, so keep it to yourself."
            Cell { title: "Save as a File…"; titleColor: Ios.accent
                   tap: () => { saveDialog.currentFile = transfer.defaultFile(); saveDialog.open() } }
        }
        Section {
            header: "On the New Computer"
            footer: "Pick the backup file. Settings and EQ replace this computer's; downloads are added to what's here."
            Cell { title: "Restore from a Backup…"; titleColor: Ios.accent; tap: () => openDialog.open() }
        }
    }
}
