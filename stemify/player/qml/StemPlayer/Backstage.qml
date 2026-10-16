import QtQuick

// File: Excel's backstage view. Info, Account (Plex sign-in) and Options.
Rectangle {
    id: bs
    property var win: Window.window
    property string page: "info"
    color: X.paper

    Rectangle {
        id: side
        width: 190
        height: parent.height
        color: X.green
        Column {
            width: parent.width
            Item {
                width: parent.width
                height: 64
                Rectangle { anchors.fill: parent; color: bm.containsMouse ? X.greenHover : "transparent" }
                Ico { x: 22; anchors.verticalCenter: parent.verticalCenter; name: "back"; ink: "#FFFFFF"; size: 30 }
                MouseArea { id: bm; anchors.fill: parent; hoverEnabled: true; onClicked: bs.win.closeBackstage() }
            }
            Repeater {
                model: [["info", "Info"], ["account", "Account"], ["options", "Options"]]
                delegate: Item {
                    required property var modelData
                    width: side.width
                    height: 40
                    Rectangle {
                        anchors.fill: parent
                        color: bs.page === parent.modelData[0] ? X.greenDark : im.containsMouse ? X.greenHover : "transparent"
                    }
                    T { x: 26; height: parent.height; text: parent.modelData[1]; color: "#FFFFFF"; font.pixelSize: 15 }
                    MouseArea { id: im; anchors.fill: parent; hoverEnabled: true; onClicked: bs.page = parent.modelData[0] }
                }
            }
            Item { width: 1; height: 20 }
            Item {
                width: side.width
                height: 40
                Rectangle { anchors.fill: parent; color: qm.containsMouse ? X.greenHover : "transparent" }
                T { x: 26; height: parent.height; text: "Close"; color: "#FFFFFF"; font.pixelSize: 15 }
                MouseArea { id: qm; anchors.fill: parent; hoverEnabled: true; onClicked: bs.win.close() }
            }
        }
    }

    Item {
        x: side.width + 40
        y: 24
        width: parent.width - x - 40
        height: parent.height - y

        T {
            id: heading
            text: bs.page === "info" ? "Info" : bs.page === "account" ? "Account" : "Options"
            font.pixelSize: 38
            font.weight: Font.Light
            color: X.ink
            height: 56
        }

        // ---- Info ----
        Column {
            visible: bs.page === "info"
            y: heading.height + 20
            width: parent.width
            spacing: 18
            Row {
                spacing: 40
                Column {
                    width: 360
                    spacing: 8
                    T { text: "Now Playing"; font.pixelSize: 20; color: X.greenText; height: 30 }
                    Prop { k: "Title"; v: player.track.title || "—" }
                    Prop { k: "Artist"; v: player.track.artist || "—" }
                    Prop { k: "Album"; v: player.track.album || "—" }
                    Prop { k: "Plays from"; v: player.sourceKind === "stems" ? "Stems (" + player.stems.length + ")" : player.sourceKind === "original" ? "Original file" + (player.track.codec ? " (" + player.track.codec.toUpperCase() + ")" : "") : "—" }
                    Prop { k: "Mix"; v: player.presetLabel || "—" }
                    Prop { k: "Queue"; v: player.queueInfo.count ? (player.queueInfo.index + 1) + " of " + player.queueInfo.count : "Empty" }
                }
                Column {
                    width: 360
                    spacing: 8
                    T { text: "Properties"; font.pixelSize: 20; color: X.greenText; height: 30 }
                    Prop { k: "Songs"; v: library.counts.songs.toLocaleString(Qt.locale(), "f", 0) }
                    Prop { k: "With stems"; v: library.counts.stems.toLocaleString(Qt.locale(), "f", 0) }
                    Prop { k: "Stems index"; v: library.counts.stemIndex.toLocaleString(Qt.locale(), "f", 0) + " songs" }
                    Prop { k: "Stem server"; v: (library.counts.stemOnline === true ? "Online · " : library.counts.stemOnline === false ? "Offline · " : "Checking · ") + settings.get("stemUrl") }
                    Prop { k: "Plex"; v: (library.signedIn ? "Connected · " : "Not connected · ") + settings.get("plexUrl") }
                    Prop { k: "Media keys"; v: bs.win.mprisOk ? "On (MPRIS)" : "Off" }
                }
            }
        }

        // ---- Account ----
        Column {
            visible: bs.page === "account"
            y: heading.height + 20
            width: parent.width
            spacing: 14
            T { text: "Connected Services"; font.pixelSize: 20; color: X.greenText; height: 30 }
            Row {
                spacing: 14
                Ico { name: "plex"; size: 40 }
                Column {
                    spacing: 4
                    T { text: "Plex Media Server"; font.pixelSize: 15; font.bold: true }
                    T { text: library.signedIn ? "Connected to " + settings.get("plexUrl") : "Not connected"; color: X.inkSoft }
                }
            }
            Rectangle {
                visible: library.pin !== ""
                width: 420
                height: 150
                color: X.paperAlt
                border.color: X.ribbonLine
                Column {
                    anchors.centerIn: parent
                    spacing: 6
                    T { anchors.horizontalCenter: parent.horizontalCenter; text: "Go to plex.tv/link and enter"; font.pixelSize: 14 }
                    T {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: library.pin
                        font.family: cellFont
                        font.pixelSize: 54
                        font.bold: true
                        font.letterSpacing: 10
                        color: X.greenText
                    }
                    T { anchors.horizontalCenter: parent.horizontalCenter; text: "Waiting for Plex…"; color: X.inkSoft }
                }
            }
            Row {
                spacing: 10
                OfficeButton {
                    text: library.pin !== "" ? "Cancel" : library.signedIn ? "Sign in again" : "Sign in to Plex"
                    primary: library.pin === ""
                    onClicked: library.pin !== "" ? library.cancelSignIn() : library.signIn()
                }
            }
        }

        // ---- Options ----
        Column {
            visible: bs.page === "options"
            y: heading.height + 20
            width: Math.min(parent.width, 640)
            spacing: 10
            T { text: "Connections"; font.pixelSize: 20; color: X.greenText; height: 30 }
            Opt { id: oPlex; label: "Plex server"; key: "plexUrl" }
            Opt { id: oStem; label: "Stem server (stemserve)"; key: "stemUrl" }
            T { text: "Folders"; font.pixelSize: 20; color: X.greenText; height: 40; verticalAlignment: Text.AlignBottom }
            Opt { id: oStems; label: "Stems share (played directly when mounted)"; key: "stemsDir" }
            Opt { id: oMusic; label: "Music share"; key: "musicDir" }
            T { text: "Office Theme"; font.pixelSize: 20; color: X.greenText; height: 40; verticalAlignment: Text.AlignBottom }
            Row {
                spacing: 10
                OfficeButton { text: "Black"; primary: X.dark; onClicked: { X.dark = true; settings.set("officeTheme", "black") } }
                OfficeButton { text: "Colorful"; primary: !X.dark; onClicked: { X.dark = false; settings.set("officeTheme", "colorful") } }
            }
            Item { width: 1; height: 8 }
            Row {
                spacing: 10
                OfficeButton {
                    text: "Save"
                    primary: true
                    onClicked: {
                        oPlex.save(); oStem.save(); oStems.save(); oMusic.save()
                        library.refresh()
                        bs.win.closeBackstage()
                    }
                }
                OfficeButton { text: "Cancel"; onClicked: bs.win.closeBackstage() }
            }
        }
    }

    component Prop: Row {
        property string k
        property string v
        spacing: 10
        T { width: 110; text: parent.k; color: X.inkSoft }
        T { width: 240; text: parent.v }
    }
    component Opt: Column {
        id: o
        property string label
        property string key
        function save() { settings.set(key, f.text.trim()) }
        spacing: 3
        width: parent.width
        T { text: o.label; color: X.inkSoft }
        XField { id: f; width: o.width; text: settings.get(o.key) || "" }
        Connections { target: bs; function onVisibleChanged() { if (bs.visible) f.text = settings.get(o.key) || "" } }
    }
}
