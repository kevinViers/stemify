import QtQuick
import QtQuick.Controls.Basic

// Tabs on the green strip, commands on the gray ribbon below.
Column {
    id: rib
    property var win: Window.window
    property int tab: 1  // 0 = File (backstage), 1 Home, 2 Insert, 3 Data, 4 View
    readonly property var tabs: ["File", "Home", "Insert", "Data", "View"]
    function focusSearch() { tell.startEditing() }

    Rectangle {
        width: parent.width
        height: 28
        color: X.green
        Row {
            x: 0
            height: parent.height
            Repeater {
                model: rib.tabs
                delegate: Item {
                    id: tabItem
                    required property int index
                    required property string modelData
                    readonly property bool file: index === 0
                    readonly property bool active: rib.tab === index && !file
                    width: tl.implicitWidth + (file ? 30 : 22)
                    height: 28
                    Rectangle {
                        anchors.fill: parent
                        anchors.topMargin: tabItem.active ? 2 : 0
                        color: tabItem.active ? X.ribbon : tabItem.file ? (tm.containsMouse ? "#0E4B2A" : X.greenDark)
                             : tm.containsMouse ? X.greenHover : "transparent"
                    }
                    T {
                        id: tl
                        anchors.centerIn: parent
                        text: tabItem.modelData
                        color: tabItem.active ? X.greenText : "#FFFFFF"
                    }
                    MouseArea {
                        id: tm
                        anchors.fill: parent
                        hoverEnabled: true
                        onClicked: tabItem.file ? rib.win.openBackstage("info") : rib.tab = tabItem.index
                    }
                }
            }
            // "Tell me what you want to do": here it searches the library.
            Item {
                id: tell
                width: 260
                height: 28
                function startEditing() { field.visible = true; field.forceActiveFocus(); field.selectAll() }
                Ico { x: 12; anchors.verticalCenter: parent.verticalCenter; name: "lightbulb"; ink: "#FFFFFF"; size: 16 }
                T {
                    x: 34
                    anchors.verticalCenter: parent.verticalCenter
                    visible: !field.visible
                    color: "#FFFFFF"
                    text: "Tell me what you want to listen to..."
                }
                MouseArea { anchors.fill: parent; cursorShape: Qt.IBeamCursor; onClicked: tell.startEditing() }
                XField {
                    id: field
                    visible: false
                    x: 32
                    width: 220
                    anchors.verticalCenter: parent.verticalCenter
                    placeholderText: "Song, artist or album"
                    onAccepted: { library.search(text); visible = false; rib.win.focusGrid() }
                    onActiveFocusChanged: if (!activeFocus && text === "") visible = false
                    Keys.onEscapePressed: { text = ""; visible = false; rib.win.focusGrid() }
                }
            }
        }
    }

    Rectangle {
        width: parent.width
        height: 94
        color: X.ribbon
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.ribbonLine }

        // ---- Home ----
        Row {
            visible: rib.tab === 1
            height: parent.height - 2
            RGroup {
                title: "Playback"
                RButton {
                    big: true
                    icon: player.status === "playing" ? "pause" : "play"
                    text: player.status === "playing" ? "Pause" : "Play"
                    tip: "Play or pause (Space)"
                    onClicked: player.toggle()
                }
                Column {
                    RButton { icon: "previous"; text: "Previous"; tip: "Previous song (Ctrl+Left)"; onClicked: player.previous() }
                    RButton { icon: "next"; text: "Next"; tip: "Next song (Ctrl+Right)"; onClicked: player.next() }
                    RButton { icon: "stop"; text: "Stop"; onClicked: { engine.pause(); player.seek(0) } }
                }
                Column {
                    RButton { icon: "shuffle"; text: "Shuffle"; checked: player.shuffleOn; onClicked: player.toggleShuffle() }
                    RButton {
                        icon: player.repeatMode === "one" ? "repeat_one" : "repeat"
                        text: player.repeatMode === "one" ? "Repeat One" : "Repeat"
                        checked: player.repeatMode !== "off"
                        onClicked: player.cycleRepeat()
                    }
                }
            }
            RGroup {
                title: "Karaoke"
                RButton {
                    big: true
                    enabled: player.sourceKind === "stems"
                    icon: player.presetLabel === "Karaoke" ? "vocals_off" : "vocals"
                    text: player.presetLabel === "Karaoke" ? "Vocals\nOff" : "Vocals\nOn"
                    checked: player.presetLabel === "Karaoke"
                    tip: "Mute the vocals (V)"
                    onClicked: player.toggleVocals()
                }
            }
            RGroup {
                title: "Mix Styles"
                Grid {
                    columns: 4
                    rowSpacing: 3
                    columnSpacing: 3
                    y: 4
                    Repeater {
                        model: player.presets
                        delegate: Rectangle {
                            id: sty
                            required property int index
                            required property var modelData
                            readonly property var s: X.styles[index]
                            width: 82
                            height: 28
                            opacity: modelData.usable ? 1 : 0.35
                            color: s.fill
                            border.width: modelData.active ? 2 : 1
                            border.color: modelData.active ? X.green : (sm.containsMouse ? "#8A8A8A" : s.border)
                            T {
                                anchors.fill: parent
                                anchors.leftMargin: 5
                                font.family: cellFont
                                font.pixelSize: 14
                                font.bold: sty.s.bold
                                color: sty.s.text
                                text: sty.modelData.label
                            }
                            MouseArea {
                                id: sm
                                anchors.fill: parent
                                hoverEnabled: true
                                enabled: sty.modelData.usable
                                onClicked: player.applyPreset(sty.index)
                            }
                            Tip { target: sm; text: "Style: " + sty.s.name + " — " + sty.modelData.label }
                        }
                    }
                }
            }
            RGroup {
                title: "Cells"
                RButton { big: true; icon: "play_next"; text: "Play\nNext"; tip: "Play the selection after this song"; onClicked: library.playNextRows(rib.win.selTop, rib.win.selBottom) }
                RButton { big: true; icon: "queue_add"; text: "Add to\nQueue"; tip: "Add the selection to the end of the queue"; onClicked: library.queueRows(rib.win.selTop, rib.win.selBottom) }
                RButton {
                    big: true
                    icon: "delete_rows"
                    text: "Delete\nRows"
                    enabled: library.layout === "queue" || library.sheetTitle !== "" && rib.win.inPlaylist
                    tip: "Remove the selected rows from the queue or playlist (Delete)"
                    onClicked: library.removeRows(rib.win.selTop, rib.win.selBottom)
                }
            }
            RGroup {
                title: "Editing"
                Column {
                    RButton { icon: "sigma"; text: "AutoShuffle"; tip: "Shuffle everything on this sheet"; onClicked: library.shuffleSheet() }
                    RButton { icon: "sort_az"; text: "Sort & Filter"; dropdown: true; onClicked: sortMenu.popup() }
                    RButton { icon: "search"; text: "Find & Select"; tip: "Search the library (Ctrl+F)"; onClicked: rib.focusSearch() }
                }
            }
        }

        // ---- Insert ----
        Row {
            visible: rib.tab === 2
            height: parent.height - 2
            RGroup {
                title: "Playlists"
                RButton { big: true; icon: "playlist_new"; text: "New\nPlaylist"; tip: "A Plex playlist from the selected rows"; onClicked: rib.win.newPlaylist() }
                RButton { big: true; icon: "playlist_add"; text: "Add to\nPlaylist"; dropdown: true; onClicked: plMenu.popup() }
            }
            RGroup {
                title: "Queue"
                RButton { big: true; icon: "play_next"; text: "Play\nNext"; onClicked: library.playNextRows(rib.win.selTop, rib.win.selBottom) }
                RButton { big: true; icon: "queue_add"; text: "Add to\nQueue"; onClicked: library.queueRows(rib.win.selTop, rib.win.selBottom) }
                RButton { big: true; icon: "play"; text: "Play\nSelection"; onClicked: library.playRows(rib.win.selTop, rib.win.selBottom) }
            }
            RGroup {
                title: "Charts"
                RButton { big: true; icon: "chart"; text: "Stem\nChart"; checked: rib.win.showMixer; tip: "Show the stem mixer pane"; onClicked: rib.win.toggleMixer() }
            }
        }

        // ---- Data ----
        Row {
            visible: rib.tab === 3
            height: parent.height - 2
            RGroup {
                title: "Get External Data"
                RButton { big: true; icon: "plex"; text: library.signedIn ? "Plex\nAccount" : "Connect\nto Plex"; onClicked: rib.win.openBackstage("account") }
                RButton { big: true; icon: "server"; text: "Connections"; tip: "Server addresses and folders"; onClicked: rib.win.openBackstage("options") }
            }
            RGroup {
                title: "Connections"
                RButton { big: true; icon: "refresh"; text: "Refresh\nAll"; tip: "Reload the Plex library and the stems index"; onClicked: library.refresh() }
                Column {
                    y: 4
                    spacing: 2
                    T { text: "Songs: " + library.counts.songs.toLocaleString(Qt.locale(), "f", 0); height: 20 }
                    T { text: "With stems: " + library.counts.stems.toLocaleString(Qt.locale(), "f", 0); height: 20 }
                    T {
                        text: "Stem server: " + (library.counts.stemOnline === true ? "Online" : library.counts.stemOnline === false ? "Offline" : "Checking…")
                        color: library.counts.stemOnline === true ? X.goodText : library.counts.stemOnline === false ? X.badText : X.inkSoft
                        height: 20
                    }
                }
            }
            RGroup {
                title: "Sort & Filter"
                Column {
                    RButton { icon: "sort_az"; text: "Sort A to Z"; onClicked: rib.win.sortCurrent(false) }
                    RButton { icon: "sort_za"; text: "Sort Z to A"; onClicked: rib.win.sortCurrent(true) }
                    RButton { icon: "filter"; text: "Stems Only"; tip: "Songs that have stems"; onClicked: library.current = 4 }
                }
            }
        }

        // ---- View ----
        Row {
            visible: rib.tab === 4
            height: parent.height - 2
            RGroup {
                title: "Show"
                Column {
                    y: 2
                    XCheck { text: "Gridlines"; checked: rib.win.gridlines; onToggled: rib.win.setOption("gridlines", !rib.win.gridlines) }
                    XCheck { text: "Headings"; checked: rib.win.headings; onToggled: rib.win.setOption("headings", !rib.win.headings) }
                    XCheck { text: "Mixer Pane"; checked: rib.win.showMixer; onToggled: rib.win.toggleMixer() }
                }
            }
            RGroup {
                title: "Zoom"
                RButton { big: true; icon: "zoom"; text: "Zoom"; tip: "Ctrl+scroll on the grid"; onClicked: rib.win.setZoom(rib.win.zoom >= 150 ? 100 : rib.win.zoom + 25) }
                RButton { big: true; icon: "grid"; text: "100%"; onClicked: rib.win.setZoom(100) }
            }
            RGroup {
                title: "Window"
                RButton { big: true; icon: "freeze"; text: "Freeze\nPanes"; checked: true; tip: "The header row is always frozen" }
            }
        }
    }

    XMenu {
        id: sortMenu
        Action { text: "Sort A to Z"; icon.name: "sort_az"; onTriggered: rib.win.sortCurrent(false) }
        Action { text: "Sort Z to A"; icon.name: "sort_za"; onTriggered: rib.win.sortCurrent(true) }
        MenuSeparator {}
        Action { text: "Filter: Stems Only"; icon.name: "filter"; onTriggered: library.current = 4 }
    }
    XMenu {
        id: plMenu
        Instantiator {
            model: library.playlistNames
            delegate: MenuItem {
                required property int index
                required property string modelData
                text: modelData
                onTriggered: rib.win.addToPlaylist(modelData)
            }
            onObjectAdded: (i, o) => plMenu.insertItem(i, o)
            onObjectRemoved: (i, o) => plMenu.removeItem(o)
        }
    }
}
