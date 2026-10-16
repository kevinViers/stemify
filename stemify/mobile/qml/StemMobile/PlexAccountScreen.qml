import QtQuick

Page {
    id: page
    title: "Plex"
    grouped: true
    property bool testing: false
    readonly property bool signedIn: !!app.settings.token
    Fetch { id: sectionsFetch; name: "sections"; active: page.signedIn; deps: app.settings.token }
    header: Column {
        width: page.width
        Section {
            header: "Server"; footer: "Your server's LAN address (port 32400)."
            FieldCell { id: url; label: "Address"; placeholder: "http://192.168.86.2:32400"; text: app.settings.plexUrl || "" }
            Cell {
                title: page.testing ? "Testing…" : "Test Connection"; titleColor: Ios.accent
                tap: () => {
                    page.testing = true
                    Api.call("probe", [url.text, ""], function (ok) {
                        page.testing = false
                        if (ok) { app.set("plexUrl", url.text.trim().replace(/\/+$/, "")); Ui.hud("Connected") }
                        else Ui.hud(page.signedIn ? "Couldn't reach Plex at that address" : "Sign in first", "warning")
                    }, () => page.testing = false)
                }
            }
        }
        Section {
            visible: !page.signedIn
            header: "Sign In"
            footer: app.pinError || (app.pin === "" ? "Enter the code at plex.tv/link while signed in to Plex." : "")
            Cell {
                title: app.pin === "" ? "Sign In with plex.tv" : "Get a New Code"; titleColor: Ios.accent
                tap: () => { app.set("plexUrl", url.text.trim().replace(/\/+$/, "")); app.signIn() }
            }
            CellItem {
                visible: app.pin !== ""
                height: pinCol.height + 36
                Column {
                    id: pinCol
                    y: 18
                    width: parent.width
                    spacing: 10
                    Txt { anchors.horizontalCenter: parent.horizontalCenter; text: app.pin; style: "largeTitle"; font.pixelSize: 44; font.letterSpacing: 8; lineHeight: 52 }
                    Row {
                        anchors.horizontalCenter: parent.horizontalCenter
                        spacing: 8
                        Spinner { size: 16; anchors.verticalCenter: parent.verticalCenter }
                        Txt { text: "Waiting for plex.tv/link…"; style: "footnote"; color: Ios.label2 }
                    }
                    Rectangle {
                        anchors.horizontalCenter: parent.horizontalCenter
                        width: open.implicitWidth + 36; height: 38; radius: 19; color: Ios.accent
                        Txt { id: open; anchors.centerIn: parent; text: "Open plex.tv/link"; style: "headline"; color: "white" }
                        Pressable { anchors.fill: parent; fade: true; tap: () => app.openUrl("https://plex.tv/link") }
                    }
                }
            }
        }
        Section {
            visible: !page.signedIn
            header: "Or Paste an X-Plex-Token"
            FieldCell { id: token; label: "Token"; placeholder: "Required"; secret: true }
            Cell { title: "Use Token"; titleColor: token.text.trim() ? Ios.accent : Ios.label3
                   tap: () => token.text.trim() && app.useToken(url.text, token.text.trim()) }
        }
        Section {
            visible: page.signedIn
            header: "Account"
            Cell { title: app.settings.plexUser || "Signed In"; icon: "person"; iconBg: "#E5A00D" }
        }
        Section {
            visible: page.signedIn
            header: "Music Library"
            CellItem {
                visible: sectionsFetch.loading
                height: 52
                Spinner { anchors.centerIn: parent }
            }
            Cell { visible: !sectionsFetch.loading && !!sectionsFetch.value && sectionsFetch.value.length === 0; title: "No music libraries found" }
            Repeater {
                model: sectionsFetch.value || []
                Cell {
                    title: modelData.title
                    subtitle: modelData.locations.join(", ")
                    checked: modelData.key === app.settings.musicSection
                    tap: () => app.selectSection(modelData.key, modelData.title, modelData.locations)
                }
            }
        }
        Section {
            visible: page.signedIn
            Cell {
                title: "Sign Out"; titleColor: Ios.red; centered: true
                tap: () => Ui.confirm("Sign Out of Plex?", "Downloads stay on this computer.", "Sign Out", () => app.signOut())
            }
        }
    }
}
