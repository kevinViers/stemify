import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import StemRoller

Window {
    id: win
    width: 1440
    height: 900
    minimumWidth: 960
    minimumHeight: 600
    visible: true
    title: "StemRoller"
    color: "transparent"
    flags: Qt.Window | Qt.FramelessWindowHint

    readonly property bool maximized: visibility === Window.Maximized || visibility === Window.FullScreen
    readonly property int radius: maximized ? 0 : 10
    function toggleMaximized() { maximized ? showNormal() : showMaximized() }

    // Glass snapshot size: refreshed only after a resize settles.
    property int fxW: width
    property int fxH: height
    Timer { id: fxTimer; interval: 150; onTriggered: { win.fxW = win.width; win.fxH = win.height } }
    onWidthChanged: fxTimer.restart()
    onHeightChanged: fxTimer.restart()

    // Per-frame playback state, read from the audio ring buffer.
    property real playPos: 0
    property real levelL: 0
    property real levelR: 0
    FrameAnimation {
        running: audio.playing || win.levelL > 0.0005 || win.levelR > 0.0005
        onTriggered: {
            var s = audio.snapshot()
            if (s[3]) win.playPos = s[0]
            var k = Math.exp(-frameTime * 10)
            win.levelL = Math.max(s[1], win.levelL * k)
            win.levelR = Math.max(s[2], win.levelR * k)
            if (win.levelL < 0.0005) win.levelL = 0
            if (win.levelR < 0.0005) win.levelR = 0
        }
    }
    Connections {
        target: audio
        function onLoadedChanged() { win.playPos = 0 }
    }
    Connections {
        target: app
        function onToast(msg, kind, folder) { toast.show(msg, kind, folder) }
    }
    Component.onCompleted: Theme.fontFamily = uiFont

    FileDialog {
        id: fileDialog
        title: "Add audio files"
        fileMode: FileDialog.OpenFiles
        nameFilters: ["Audio (*.mp3 *.wav *.flac *.ogg *.oga *.opus *.m4a *.aac *.aif *.aiff *.wma *.mp4 *.mkv *.webm *.mka *.wv *.ape)", "All files (*)"]
        onAccepted: app.addUrls(selectedFiles)
    }

    Shortcut { sequences: ["Space"]; enabled: audio.loaded && !settings.opened; onActivated: audio.toggle() }
    Shortcut { sequences: ["Home"]; enabled: audio.loaded; onActivated: { audio.seek(0); win.playPos = 0 } }
    Shortcut { sequences: [StandardKey.Open]; onActivated: fileDialog.open() }
    Shortcut { sequences: ["Ctrl+,"]; onActivated: settings.open() }
    Shortcut { sequences: ["F12"]; onActivated: appSettings.frameOverlay = !appSettings.frameOverlay }
    Shortcut { sequences: ["F11"]; onActivated: win.toggleMaximized() }
    Shortcut { sequences: [StandardKey.Quit]; onActivated: win.close() }

    Item {
        id: frame
        anchors.fill: parent

        // window background: placeholder until the aurora texture arrives
        Rectangle {
            anchors.fill: parent
            radius: win.radius
            color: Theme.bg
        }
        Image {
            id: backdrop
            anchors.fill: parent
            source: "image://fx/backdrop/" + win.fxW + "x" + win.fxH + "/" + win.radius
            asynchronous: true
            retainWhileLoading: true
            cache: true
            opacity: status === Image.Ready ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: Theme.slow } }
        }

        TitleBar {
            id: titleBar
            anchors { left: parent.left; right: parent.right; top: parent.top }
            offsetX: 0
            offsetY: 0
            onOpenSettings: settings.open()
        }

        Sidebar {
            id: sidebar
            anchors { left: parent.left; top: titleBar.bottom; bottom: transport.top }
            width: 220
            offsetX: 0
            offsetY: titleBar.height
            onBrowse: fileDialog.open()
            Rectangle { anchors { right: parent.right; top: parent.top; bottom: parent.bottom } width: 1; color: Theme.hairline }
        }

        Item {
            id: mainPane
            anchors { left: sidebar.right; right: parent.right; top: titleBar.bottom; bottom: transport.top }
            clip: true

            Image {  // dither the dark gradients behind content
                anchors.fill: parent
                source: "image://fx/noise"
                fillMode: Image.Tile
                cache: true
            }

            DropZone {
                anchors.fill: parent
                visible: !app.selected.valid
                dragging: drop.containsDrag
                onBrowse: fileDialog.open()
            }
            TrackView {
                anchors.fill: parent
                visible: app.selected.valid
            }

            // drag-over affordance when a track is already showing
            Item {
                anchors.fill: parent
                visible: opacity > 0
                opacity: drop.containsDrag && app.selected.valid ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: Theme.med } }
                Rectangle { anchors.fill: parent; color: Theme.alpha(Theme.bg, 0.82) }
                DashedFrame { anchors.fill: parent; anchors.margins: 24; radius: 16 }
                Glow { anchors.margins: 24 - m; radius: 16; sigma: 8; opacity: 0.4 }
                Txt {
                    anchors.centerIn: parent
                    text: "Drop to add to the queue"
                    font.pixelSize: Theme.fs28
                    font.weight: Font.DemiBold
                }
            }

            FrameOverlay {
                visible: appSettings.frameOverlay || devOverlay
                anchors { right: parent.right; bottom: parent.bottom; margins: 12 }
                z: 100
            }
        }

        Transport {
            id: transport
            anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
            height: 64
            offsetX: 0
            offsetY: win.height - 64
        }

        Item {
            anchors { horizontalCenter: mainPane.horizontalCenter; bottom: transport.top; bottomMargin: 16 }
            width: toast.width
            height: 48
            Toast { id: toast; maxWidth: mainPane.width - 48 }
        }

        // window edge highlight
        Rectangle {
            anchors.fill: parent
            radius: win.radius
            color: "transparent"
            border.width: win.maximized ? 0 : 1
            border.color: Qt.rgba(1, 1, 1, 0.12)
        }

        DropArea {
            id: drop
            anchors.fill: parent
            keys: ["text/uri-list"]
            onDropped: (e) => {
                if (e.hasUrls) {
                    app.addUrls(e.urls)
                    e.acceptProposedAction()
                }
            }
        }

        // resize grips (compositor-driven)
        Item {
            anchors.fill: parent
            visible: !win.maximized
            readonly property int g: 6
            ResizeEdge { edges: Qt.LeftEdge; x: 0; y: 12; width: parent.g; height: parent.height - 24 }
            ResizeEdge { edges: Qt.RightEdge; x: parent.width - parent.g; y: 12; width: parent.g; height: parent.height - 24 }
            ResizeEdge { edges: Qt.TopEdge; x: 12; y: 0; width: parent.width - 24; height: parent.g - 2 }
            ResizeEdge { edges: Qt.BottomEdge; x: 12; y: parent.height - parent.g; width: parent.width - 24; height: parent.g }
            ResizeEdge { edges: Qt.LeftEdge | Qt.TopEdge; x: 0; y: 0; width: 12; height: 12 }
            ResizeEdge { edges: Qt.RightEdge | Qt.TopEdge; x: parent.width - 12; y: 0; width: 12; height: 12 }
            ResizeEdge { edges: Qt.LeftEdge | Qt.BottomEdge; x: 0; y: parent.height - 12; width: 12; height: 12 }
            ResizeEdge { edges: Qt.RightEdge | Qt.BottomEdge; x: parent.width - 12; y: parent.height - 12; width: 12; height: 12 }
        }
    }

    SettingsDialog { id: settings; parent: Overlay.overlay; win: win }
}
