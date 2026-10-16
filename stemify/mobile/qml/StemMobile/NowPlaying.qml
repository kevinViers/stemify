import QtQuick
import QtQuick.Layouts

// The Now Playing card: big art (or the mixer, or the queue), the waveform seek bar, the
// transport with 10-second skips beside the track buttons, and the output / EQ line.
Rectangle {
    id: np
    property var close: null
    property var dragStart: null
    property var dragMove: null
    property var dragEnd: null
    property string panel: "art"  // art | mixer | queue
    readonly property var t: player.track
    readonly property bool hasTrack: !!t.key

    // A solid colour from the album art, pushed dark (or light for the White theme) so text stays
    // readable. One flat colour: no gradients.
    property string tint: ""
    function askColor() {
        tint = ""
        if (app.settings.albumColor && t.thumb) app.requestColor(t.thumb, Ios.dark)
    }
    Connections {
        target: app
        function onColorReady(thumb, color) { if (thumb === np.t.thumb && app.settings.albumColor) np.tint = color }
        function onSettingsChanged() { np.askColor() }
    }
    Connections { target: Ios; function onModeChanged() { np.askColor() } }
    onTChanged: askColor()

    QtObject {
        id: pal
        readonly property bool has: np.tint !== ""
        readonly property color tintColor: has ? np.tint : "black"
        readonly property bool dark: has ? (0.299 * tintColor.r + 0.587 * tintColor.g + 0.114 * tintColor.b) < 0.5 : Ios.dark
        readonly property color bg: has ? tintColor : Ios.bg
        readonly property color fill: has ? (dark ? Qt.rgba(1, 1, 1, 0.12) : Qt.rgba(0, 0, 0, 0.06)) : Ios.fill
        readonly property color fillStrong: has ? (dark ? Qt.rgba(1, 1, 1, 0.22) : Qt.rgba(0, 0, 0, 0.12)) : Ios.fillStrong
        readonly property color label: has ? (dark ? "white" : "black") : Ios.label
        readonly property color label2: has ? (dark ? Qt.rgba(1, 1, 1, 0.62) : Qt.rgba(0, 0, 0, 0.55)) : Ios.label2
        readonly property color label3: has ? (dark ? Qt.rgba(1, 1, 1, 0.25) : Qt.rgba(0, 0, 0, 0.2)) : Ios.label3
        readonly property color separator: has ? (dark ? Qt.rgba(1, 1, 1, 0.14) : Qt.rgba(0, 0, 0, 0.1)) : Ios.separator
        readonly property color accent: Ios.accent
        readonly property color red: Ios.red
        readonly property color green: Ios.green
        readonly property color placeholder: has ? fill : Ios.placeholder
        readonly property color thumbBorder: Ios.thumbBorder
        readonly property color switchOff: Ios.switchOff
    }

    color: pal.bg
    topLeftRadius: 14
    topRightRadius: 14
    Behavior on color { ColorAnimation { duration: 250 } }

    readonly property string badge: player.sourceKind === "stems" ? player.stems.length + " STEMS · " + player.format
                                  : player.sourceKind === "original" ? ("ORIGINAL · " + (t.codec || "").toUpperCase()).replace(/ · $/, "") : ""
    readonly property string eqLabel: {
        var p = eq.profile
        if (!eq.info.enabled || !p.name) return ""
        return p.name.toLowerCase() === eq.output.label.toLowerCase() ? " · EQ" : " · " + p.name
    }
    function deviceIcon(kind) {
        return { bluetooth: "headphones", wired: "headphones", usb: "usb", hdmi: "airplay", speaker: "desktop" }[kind] || "speakerWave"
    }

    // Playback position, refreshed every frame while playing.
    property real position: 0
    FrameAnimation { running: np.visible && player.playing; onTriggered: np.position = player.now() }
    Timer { interval: 200; repeat: true; running: np.visible && !player.playing; triggeredOnStart: true; onTriggered: np.position = player.now() }

    // Grabber; dragging here (or on the art) pulls the card down.
    MouseArea {
        id: grab
        width: parent.width
        height: 28
        cursorShape: Qt.OpenHandCursor
        property real sy: 0
        onPressed: (e) => { sy = mapToItem(null, e.x, e.y).y; if (np.dragStart) np.dragStart() }
        onPositionChanged: (e) => { if (pressed && np.dragMove) np.dragMove(mapToItem(null, e.x, e.y).y - sy) }
        onReleased: (e) => { if (np.dragEnd) np.dragEnd(mapToItem(null, e.x, e.y).y - sy) }
        Rectangle { anchors.centerIn: parent; width: 38; height: 5; radius: 2.5; color: pal.label3 }
    }

    Txt {
        visible: !np.hasTrack
        anchors.centerIn: parent
        text: "Nothing Playing"; style: "title3"; color: pal.label2
    }

    ColumnLayout {
        visible: np.hasTrack
        anchors.fill: parent
        anchors.topMargin: 28
        anchors.bottomMargin: 8
        spacing: 0

        Item {
            id: panels
            Layout.fillWidth: true
            Layout.fillHeight: true

            // Big art that eases back when paused, like Apple Music.
            Item {
                anchors.fill: parent
                opacity: np.panel === "art" ? 1 : 0
                visible: opacity > 0
                Behavior on opacity { NumberAnimation { duration: np.panel === "art" ? 180 : 120 } }
                Artwork {
                    id: art
                    anchors.centerIn: parent
                    size: Math.max(0, Math.min(parent.width - 56, parent.height - 16))
                    thumb: np.t.thumb || ""
                    corner: 12
                    placeholder: "note"
                    pal: pal
                    scale: player.playing ? 1 : 0.82
                    Behavior on scale { SpringAnimation { spring: 3; damping: 0.32; epsilon: 0.001 } }
                }
                MouseArea {
                    anchors.fill: art
                    property real sy: 0
                    onPressed: (e) => { sy = mapToItem(null, e.x, e.y).y; if (np.dragStart) np.dragStart() }
                    onPositionChanged: (e) => { if (pressed && np.dragMove) np.dragMove(mapToItem(null, e.x, e.y).y - sy) }
                    onReleased: (e) => { if (np.dragEnd) np.dragEnd(mapToItem(null, e.x, e.y).y - sy) }
                }
            }
            Column {
                anchors.fill: parent
                opacity: np.panel === "mixer" ? 1 : 0
                visible: opacity > 0
                Behavior on opacity { NumberAnimation { duration: np.panel === "mixer" ? 180 : 120 } }
                MiniHeader { pal: pal; track: np.t; more: () => Media.show(player.currentTrack()) }
                MixerPanel { width: parent.width; height: parent.height - 84; pal: pal }
            }
            Column {
                anchors.fill: parent
                opacity: np.panel === "queue" ? 1 : 0
                visible: opacity > 0
                Behavior on opacity { NumberAnimation { duration: np.panel === "queue" ? 180 : 120 } }
                MiniHeader { pal: pal; track: np.t; more: () => Media.show(player.currentTrack()) }
                QueuePanel { width: parent.width; height: parent.height - 84; pal: pal }
            }
        }

        Column {
            Layout.fillWidth: true
            Layout.leftMargin: 28
            Layout.rightMargin: 28
            RowLayout {
                visible: np.panel === "art"
                width: parent.width
                height: 64
                Column {
                    Layout.fillWidth: true
                    Txt { width: parent.width; text: np.t.title || ""; style: "title3"; pal: pal }
                    Txt {
                        width: Math.min(implicitWidth, parent.width)
                        text: np.t.artist || ""; style: "title3"; weight: 400; color: pal.label2
                        Pressable { anchors.fill: parent; tap: () => np.t.artistKey && Ui.showInLibrary("ArtistScreen", { artist: { key: np.t.artistKey, title: np.t.artist } }) }
                    }
                }
                Rectangle {
                    width: 32; height: 32; radius: 16; color: pal.fill
                    Sym { anchors.centerIn: parent; name: "ellipsis"; color: pal.label; size: 18 }
                    Pressable { anchors.fill: parent; fade: true; tap: () => Media.show(player.currentTrack()) }
                }
            }
            Item { width: 1; height: 18 }
            ProgressVolumeBar {
                width: parent.width
                envelope: player.envelope
                duration: player.duration
                position: np.position
                master: engine.volume
                played: pal.label
                unplayed: pal.label3
                centerLabel: np.badge
                pal: pal
            }
            // Transport, with 10-second skips outside the track buttons.
            RowLayout {
                width: parent.width
                height: 84
                spacing: 0
                Item { Layout.fillWidth: true }
                IconButton { icon: "gobackward10"; tint: pal.label; size: 30; box: 52; tap: () => player.skipBy(-10) }
                Item { Layout.fillWidth: true }
                IconButton { icon: "backward"; tint: pal.label; size: 38; box: 64; tap: () => player.previous() }
                Item { Layout.fillWidth: true }
                Item {
                    width: 80; height: 80
                    Spinner { visible: player.buffering && player.playing; anchors.centerIn: parent; size: 34; color: pal.label }
                    Sym { visible: !(player.buffering && player.playing); anchors.centerIn: parent; name: player.playing ? "pause" : "play"; color: pal.label; size: 52 }
                    Pressable { anchors.fill: parent; fade: true; tap: () => player.toggle() }
                }
                Item { Layout.fillWidth: true }
                IconButton { icon: "forward"; tint: pal.label; size: 38; box: 64; tap: () => player.next() }
                Item { Layout.fillWidth: true }
                IconButton { icon: "goforward10"; tint: pal.label; size: 30; box: 52; tap: () => player.skipBy(10) }
                Item { Layout.fillWidth: true }
            }
            // Bottom row: stems, output and EQ, queue.
            RowLayout {
                width: parent.width
                height: 48
                PanelToggle { icon: "sliders"; on: np.panel === "mixer"; pal: pal; tap: () => np.panel = np.panel === "mixer" ? "art" : "mixer" }
                Item {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Row {
                        anchors.centerIn: parent
                        spacing: 6
                        Sym { name: np.deviceIcon(eq.output.kind); color: pal.label2; size: 16; anchors.verticalCenter: parent.verticalCenter }
                        Txt { text: eq.output.label + np.eqLabel; style: "footnote"; weight: 500; color: pal.label2; anchors.verticalCenter: parent.verticalCenter }
                    }
                    Pressable { anchors.fill: parent; fade: true; tap: () => { if (np.close) np.close(); Ui.showInSettings("EqScreen") } }
                }
                PanelToggle { icon: "queue"; on: np.panel === "queue"; pal: pal; tap: () => np.panel = np.panel === "queue" ? "art" : "queue" }
            }
        }
    }
}
