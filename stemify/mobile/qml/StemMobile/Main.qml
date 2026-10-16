import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Window

// The phone app's shell, in a window: five tabs with their own navigation stacks, the mini
// player over the tab bar, and Now Playing as a card that slides up over the app.
Window {
    id: win
    width: 460
    height: 940
    minimumWidth: 360
    minimumHeight: 600
    visible: true
    title: player.track.title ? player.track.title + " — " + player.track.artist : "Stemify"
    color: "black"

    property bool mprisOk: false
    property string devEval: ""
    onDevEvalChanged: if (devEval) { try { console.log("eval:", eval(devEval)) } catch (e) { console.log("eval error:", e) } }

    readonly property var roots: ({ library: "LibraryHome", playlists: "PlaylistsScreen", search: "SearchScreen",
                                    downloads: "DownloadsScreen", settings: "SettingsScreen" })
    readonly property var order: ["library", "playlists", "search", "downloads", "settings"]
    property string tab: "library"
    readonly property bool hasTrack: !!player.track.key

    QtObject {
        id: shell
        readonly property real bottomInset: 50 + (win.hasTrack ? 64 : 0)
        property alias overlays: overlays
        function stack(t) { return stacks.itemAt(win.order.indexOf(t || win.tab)) }
        function push(page, props) { stack().push(Qt.resolvedUrl(page + ".qml"), props) }
        function pop() { if (stack().depth > 1) stack().pop() }
        function selectTab(t) {
            if (t === win.tab) { var s = stack(t); if (s.depth > 1) s.pop(null) }
            else win.tab = t
        }
        function showIn(t, page, props) {
            sheetTo(0)
            win.tab = t
            var s = stack(t)
            if (!(s.currentItem && s.currentItem.objectName === page && page === "EqScreen")) s.push(Qt.resolvedUrl(page + ".qml"), props)
        }
        function sheetTo(v) { sheetAnim.to = v; sheetAnim.restart() }
    }
    Component.onCompleted: Ui.shell = shell
    Connections { target: app; function onHud(text, icon) { Ui.hud(text, icon) } }
    Connections { target: player; function onToast(msg) { Ui.hud(msg, "warning") } }

    // Now Playing's position: 0 hidden, 1 fully up.
    property real sheet: 0
    NumberAnimation { id: sheetAnim; target: win; property: "sheet"; duration: 380; easing.type: Easing.OutCubic }

    // ---- the app, which recedes into a card while Now Playing is up ----
    Item {
        id: content
        anchors.fill: parent
        scale: 1 - 0.075 * win.sheet
        transformOrigin: Item.Top
        y: 10 * win.sheet
        Rectangle { anchors.fill: parent; color: Ios.bg }
        Repeater {
            id: stacks
            model: win.order
            StackView {
                id: sv
                anchors.fill: parent
                visible: modelData === win.tab
                Component.onCompleted: push(Qt.resolvedUrl(win.roots[modelData] + ".qml"), {}, StackView.Immediate)
                pushEnter: Transition { XAnimator { from: sv.width; to: 0; duration: 380; easing.type: Easing.OutCubic } }
                pushExit: Transition {
                    XAnimator { from: 0; to: -0.3 * sv.width; duration: 380; easing.type: Easing.OutCubic }
                    OpacityAnimator { from: 1; to: 0.82; duration: 380 }
                }
                popEnter: Transition {
                    XAnimator { from: -0.3 * sv.width; to: 0; duration: 320; easing.type: Easing.OutCubic }
                    OpacityAnimator { from: 0.82; to: 1; duration: 320 }
                }
                popExit: Transition { XAnimator { from: 0; to: sv.width; duration: 320; easing.type: Easing.OutCubic } }
            }
        }
        Column {
            anchors.bottom: parent.bottom
            width: parent.width
            MiniPlayer { visible: win.hasTrack; width: parent.width; open: () => shell.sheetTo(1) }
            Tabs { width: parent.width; current: win.tab; select: (t) => shell.selectTab(t) }
        }
        Rectangle { anchors.fill: parent; color: "black"; opacity: 0.4 * win.sheet; visible: win.sheet > 0 }
    }

    NowPlaying {
        id: np
        visible: win.sheet > 0.001
        width: parent.width
        height: parent.height - 10
        y: 10 + (1 - win.sheet) * (parent.height + 40)
        close: () => shell.sheetTo(0)
        property real startSheet: 1
        dragStart: () => { sheetAnim.stop(); startSheet = win.sheet }
        dragMove: (dy) => win.sheet = Math.max(0, Math.min(1, startSheet - dy / win.height))
        dragEnd: (dy) => shell.sheetTo(win.sheet < 0.75 ? 0 : 1)
    }

    Overlays { id: overlays }

    // ---- keys ----
    function typing() { return activeFocusItem && (activeFocusItem instanceof TextInput) }
    Shortcut { sequences: ["Escape", "Back"]; onActivated: {
        if (overlays.back()) return
        if (win.sheet > 0.5) { shell.sheetTo(0); return }
        shell.pop()
    } }
    Shortcut { sequence: "Alt+Left"; onActivated: shell.pop() }
    Shortcut { sequence: "Space"; enabled: !win.typing() && !overlays.busy; onActivated: player.toggle() }
    Shortcut { sequence: "Ctrl+Right"; onActivated: player.next() }
    Shortcut { sequence: "Ctrl+Left"; onActivated: player.previous() }
    Shortcut { sequence: "Right"; enabled: !win.typing() && !overlays.busy; onActivated: player.skipBy(10) }
    Shortcut { sequence: "Left"; enabled: !win.typing() && !overlays.busy; onActivated: player.skipBy(-10) }
    Shortcut { sequence: "Ctrl+F"; onActivated: { shell.sheetTo(0); win.tab = "search"; var s = shell.stack("search"); s.pop(null); s.currentItem.focusField() } }
    Shortcut { sequence: "Ctrl+P"; enabled: win.hasTrack; onActivated: shell.sheetTo(win.sheet > 0.5 ? 0 : 1) }
    Shortcut { sequence: "Ctrl+Q"; onActivated: Qt.quit() }
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.BackButton
        onClicked: { if (!overlays.back()) { if (win.sheet > 0.5) shell.sheetTo(0); else shell.pop() } }
    }
}
