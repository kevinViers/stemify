import QtQuick
import QtQuick.Controls.Basic

Window {
    id: win
    width: 1440
    height: 900
    minimumWidth: 960
    minimumHeight: 600
    visible: true
    title: (player.track.title ? player.track.title + " - " + player.track.artist : "Book1") + ".xlsx - Excel"
    color: X.paper
    flags: Qt.Window | Qt.FramelessWindowHint

    readonly property bool maximized: visibility === Window.Maximized || visibility === Window.FullScreen
    function toggleMaximized() { maximized ? showNormal() : showMaximized() }

    // ---- selection: the active cell and the anchor of the range ----
    property int curRow: 0
    property int curCol: 0
    property int anchorRow: 0
    property int anchorCol: 0
    readonly property int selTop: Math.min(curRow, anchorRow)
    readonly property int selBottom: Math.max(curRow, anchorRow)
    readonly property bool multiSel: curRow !== anchorRow || curCol !== anchorCol
    property string formulaText: ""
    property var selStats: ({})
    function refreshCell() {
        formulaText = library.formula(curRow, curCol)
        selStats = multiSel ? library.stats(anchorRow, anchorCol, curRow, curCol) : ({})
    }
    onCurRowChanged: cellTimer.restart()
    onCurColChanged: cellTimer.restart()
    onAnchorRowChanged: cellTimer.restart()
    Timer { id: cellTimer; interval: 0; onTriggered: win.refreshCell() }
    Connections {
        target: library
        function onSheetSwitched() { win.curRow = win.anchorRow = 0; win.curCol = win.anchorCol = 0; win.refreshCell() }
        function onSheetChanged() { cellTimer.restart() }
        function onToast(msg) { messages.show(msg) }
    }
    Connections { target: player; function onToast(msg) { messages.show(msg) } }
    Connections {
        target: sheetModel
        function onModelReset() {
            // new data under the same sheet: keep the selection, inside the rows that exist
            var last = Math.max(0, grid.rows - 1)
            if (win.curRow > last) win.curRow = last
            if (win.anchorRow > last) win.anchorRow = last
            cellTimer.restart()
        }
    }
    Component.onCompleted: { X.dark = settings.get("officeTheme") !== "colorful"; refreshCell() }

    // ---- view options ----
    property bool showMixer: settings.get("showMixer") !== false
    property bool gridlines: settings.get("gridlines") !== false
    property bool headings: settings.get("headings") !== false
    property int zoom: settings.get("zoom") || 100
    property bool mprisOk: false
    readonly property bool inPlaylist: library.sheetId.indexOf("playlist:") === 0
    function toggleMixer() { showMixer = !showMixer; settings.set("showMixer", showMixer) }
    function setOption(k, v) { win[k] = v; settings.set(k, v) }
    function setZoom(z) { zoom = Math.max(50, Math.min(200, z)); settings.set("zoom", zoom) }

    function openBackstage(page) { backstage.page = page; backstage.visible = true }
    function closeBackstage() { backstage.visible = false; focusGrid() }
    function focusGrid() { grid.forceActiveFocus() }
    function focusSearch() { ribbon.focusSearch() }
    function selectAll() { anchorRow = 0; anchorCol = 0; curRow = Math.max(0, grid.rows - 1); curCol = Math.max(0, library.columns.length - 1) }
    function copySelection() { library.copyCells(anchorRow, anchorCol, curRow, curCol); messages.show("Copied " + (selBottom - selTop + 1) + " rows") }
    function sortCurrent(desc) { library.sortTo(curCol, desc) }
    function sortColumn(c, desc) { library.sortTo(c, desc) }
    function newPlaylist() { plDialog.open() }
    function addToPlaylist(name) { library.addToPlaylist(name, selTop, selBottom) }

    // ---- playhead, read once per frame while playing ----
    property real playPos: 0
    property real levelL: 0
    property real levelR: 0
    function seekTo(s) { player.seek(s); playPos = s }
    FrameAnimation {
        running: engine.playing
        onTriggered: {
            var s = engine.snapshot()
            win.playPos = s[0]
            // meters fall back smoothly between peaks
            var k = Math.exp(-frameTime / 0.12)
            win.levelL = Math.max(s[1], win.levelL * k)
            win.levelR = Math.max(s[2], win.levelR * k)
        }
    }
    Connections {
        target: engine
        function onPlayingChanged() { var s = engine.snapshot(); win.playPos = s[0]; if (!engine.playing) { win.levelL = 0; win.levelR = 0 } }
        function onDurationChanged() { win.playPos = engine.snapshot()[0] }
    }
    Timer { interval: 500; running: !engine.playing; repeat: true; onTriggered: win.playPos = engine.snapshot()[0] }

    // dev hook: --eval runs JavaScript against the window
    property string devEval: ""
    onDevEvalChanged: if (devEval) { try { console.log("eval:", eval(devEval)) } catch (e) { console.log("eval error:", e) } }

    Shortcut { sequence: "Ctrl+F"; onActivated: win.focusSearch() }
    Shortcut { sequence: "Ctrl+Q"; onActivated: win.close() }
    Shortcut { sequence: "Media Play"; onActivated: player.toggle() }
    Shortcut { sequence: "Ctrl+Right"; context: Qt.ApplicationShortcut; enabled: !grid.activeFocus; onActivated: player.next() }
    Shortcut { sequence: "Ctrl+Left"; context: Qt.ApplicationShortcut; enabled: !grid.activeFocus; onActivated: player.previous() }
    Shortcut { sequence: "Ctrl+Shift+Right"; context: Qt.ApplicationShortcut; enabled: !grid.activeFocus; onActivated: player.skipBy(10) }
    Shortcut { sequence: "Ctrl+Shift+Left"; context: Qt.ApplicationShortcut; enabled: !grid.activeFocus; onActivated: player.skipBy(-10) }
    Shortcut { sequence: "Alt+Left"; onActivated: library.back() }
    Shortcut { sequence: "F1"; onActivated: win.openBackstage("info") }

    Column {
        anchors.fill: parent
        TitleBar { width: parent.width }
        Ribbon { id: ribbon; width: parent.width }
        MessageBar { id: messages; width: parent.width }
        FormulaBar { width: parent.width }
        Item {
            width: parent.width
            height: parent.height - y - tabs.height - status.height
            Sheet {
                id: grid
                width: parent.width - (pane.visible ? pane.width : 0)
                height: parent.height
                focus: true
            }
            TaskPane {
                id: pane
                visible: win.showMixer
                anchors.right: parent.right
                width: 330
                height: parent.height
            }
        }
        SheetTabs { id: tabs; width: parent.width }
        StatusBar { id: status; width: parent.width }
    }

    Backstage {
        id: backstage
        visible: false
        y: 32
        width: parent.width
        height: parent.height - y
    }

    // New Playlist: an Office dialog
    Popup {
        id: plDialog
        modal: true
        anchors.centerIn: parent
        width: 380
        padding: 0
        onOpened: { plName.text = ""; plName.forceActiveFocus() }
        onClosed: win.focusGrid()
        background: Rectangle { color: X.raised; border.color: X.borderStrong }
        Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.12) }
        contentItem: Column {
            Rectangle {
                width: parent.width
                height: 30
                color: X.raised
                T { x: 10; height: parent.height; text: "New Playlist" }
                Ico { anchors.right: parent.right; anchors.rightMargin: 8; anchors.verticalCenter: parent.verticalCenter; name: "close"; size: 18
                    MouseArea { anchors.fill: parent; onClicked: plDialog.close() } }
            }
            Column {
                x: 14
                width: parent.width - 28
                spacing: 8
                T { text: (win.selBottom - win.selTop + 1) + " selected row(s) go into a new Plex playlist."; color: X.inkSoft }
                T { text: "Name:" }
                XField {
                    id: plName
                    width: parent.width
                    onAccepted: okBtn.clicked()
                }
                Row {
                    anchors.right: parent.right
                    spacing: 8
                    bottomPadding: 14
                    topPadding: 6
                    OfficeButton {
                        id: okBtn
                        text: "OK"
                        primary: true
                        onClicked: { library.newPlaylist(plName.text, win.selTop, win.selBottom); plDialog.close() }
                    }
                    OfficeButton { text: "Cancel"; onClicked: plDialog.close() }
                }
            }
        }
    }

    // frameless: resize from the edges
    ResizeEdge { visible: !win.maximized; edges: Qt.LeftEdge; x: 0; y: 6; width: 5; height: parent.height - 12 }
    ResizeEdge { visible: !win.maximized; edges: Qt.RightEdge; x: parent.width - 5; y: 6; width: 5; height: parent.height - 12 }
    ResizeEdge { visible: !win.maximized; edges: Qt.TopEdge; x: 6; y: 0; width: parent.width - 12; height: 4 }
    ResizeEdge { visible: !win.maximized; edges: Qt.BottomEdge; x: 6; y: parent.height - 4; width: parent.width - 12; height: 4 }
    ResizeEdge { visible: !win.maximized; edges: Qt.LeftEdge | Qt.TopEdge; width: 8; height: 8 }
    ResizeEdge { visible: !win.maximized; edges: Qt.RightEdge | Qt.TopEdge; x: parent.width - 8; width: 8; height: 8 }
    ResizeEdge { visible: !win.maximized; edges: Qt.LeftEdge | Qt.BottomEdge; y: parent.height - 8; width: 8; height: 8 }
    ResizeEdge { visible: !win.maximized; edges: Qt.RightEdge | Qt.BottomEdge; x: parent.width - 8; y: parent.height - 8; width: 8; height: 8 }
    Rectangle { anchors.fill: parent; color: "transparent"; border.color: X.green; border.width: win.maximized ? 0 : 1 }
}
