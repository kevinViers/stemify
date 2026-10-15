import QtQuick

// Excel's green title bar: Quick Access Toolbar, workbook name, account, caption buttons.
Rectangle {
    id: bar
    property var win: Window.window
    height: 32
    color: X.green

    DragHandler {
        target: null
        grabPermissions: PointerHandler.CanTakeOverFromAnything
        onActiveChanged: if (active) bar.win.startSystemMove()
    }
    TapHandler { onDoubleTapped: bar.win.toggleMaximized() }

    Row {
        x: 6
        height: parent.height
        spacing: 0
        Ico { name: "excel"; size: 20; anchors.verticalCenter: parent.verticalCenter }
        Item { width: 10; height: 1 }
        QatButton { icon: player.status === "playing" ? "pause" : "play"; tip: player.status === "playing" ? "Pause (Space)" : "Play (Space)"; onClicked: player.toggle() }
        QatButton { icon: "undo"; tip: "Undo — previous song (Ctrl+Left)"; onClicked: player.previous() }
        QatButton { icon: "redo"; tip: "Redo — next song (Ctrl+Right)"; onClicked: player.next() }
        QatButton { icon: "dropdown"; tip: "Customize Quick Access Toolbar"; width: 16 }
    }

    T {
        anchors.centerIn: parent
        width: Math.min(implicitWidth, parent.width - 560)
        color: "#FFFFFF"
        font.pixelSize: X.fsUi
        text: (player.track.title ? player.track.title + " - " + player.track.artist : "Book1") + ".xlsx  -  Excel"
    }

    Row {
        anchors.right: parent.right
        height: parent.height
        T {
            anchors.verticalCenter: parent.verticalCenter
            color: "#FFFFFF"
            text: library.signedIn ? (settings.get("plexUser") || "Plex") : "Sign in"
            rightPadding: 14
            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: bar.win.openBackstage("account") }
        }
        CaptionButton { icon: "pane"; tip: "Show or hide the mixer pane"; onClicked: bar.win.toggleMixer() }
        CaptionButton { icon: "minimize"; onClicked: bar.win.showMinimized() }
        CaptionButton { icon: bar.win.maximized ? "restore" : "maximize"; onClicked: bar.win.toggleMaximized() }
        CaptionButton { icon: "close"; danger: true; onClicked: bar.win.close() }
    }

    component QatButton: Item {
        id: q
        property string icon
        property string tip
        signal clicked()
        width: 26
        height: parent.height
        Rectangle { anchors.fill: parent; color: qm.pressed ? X.greenDark : qm.containsMouse ? X.greenHover : "transparent" }
        Ico { anchors.centerIn: parent; name: q.icon; ink: "#FFFFFF"; size: q.icon === "dropdown" ? 10 : 16 }
        MouseArea { id: qm; anchors.fill: parent; hoverEnabled: true; onClicked: q.clicked() }
        Tip { target: qm; text: q.tip }
    }
}
