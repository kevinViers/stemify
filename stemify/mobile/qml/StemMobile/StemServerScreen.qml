import QtQuick

Page {
    id: page
    title: "Stem Server"
    grouped: true
    property bool busy: false
    readonly property var st: app.status
    Connections {
        target: app
        function onStatusChanged() {
            if (!page.busy) return
            page.busy = false
            Ui.hud(app.status.state === "online" ? "Connected" : "Couldn't connect", app.status.state === "online" ? "check" : "warning")
        }
    }
    header: Column {
        width: page.width
        Section {
            header: "stemserve"
            footer: "stemserve shares the Stems folder that stemd fills (port 8765). The app checks it for new stems every minute."
            FieldCell { id: url; label: "Address"; placeholder: "http://192.168.86.2:8765"; text: app.settings.stemUrl || "" }
            FieldCell { id: token; label: "Token"; placeholder: "Optional"; secret: true; text: app.settings.stemToken || "" }
            Cell { title: page.busy ? "Connecting…" : "Save & Connect"; titleColor: Ios.accent
                   tap: () => { page.busy = true; app.connectStems(url.text, token.text) } }
        }
        Section {
            header: "Status"
            Cell { title: "Connection"; value: page.st.state === "online" ? "Online" : page.st.state === "offline" ? "Offline" : "Checking" }
            Cell { title: "Songs with Stems"; value: page.st.state === "online" ? String(page.st.tracks) : page.st.state === "offline" ? page.st.tracks + " (cached)" : "—" }
            Cell { title: "Opus Transcoding"; value: page.st.state === "online" ? (page.st.transcode ? "Available" : "No ffmpeg") : "—" }
            Cell { visible: page.st.state === "offline" && !!page.st.reason; title: "Error"; subtitle: page.st.reason || "" }
        }
    }
}
