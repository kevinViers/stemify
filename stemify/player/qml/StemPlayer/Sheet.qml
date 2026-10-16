import QtQuick
import QtQuick.Controls.Basic

// The worksheet. Row 1 is the frozen table header; data starts on row 2. Past the data the
// sheet carries on as empty gridlines, as Excel's does.
FocusScope {
    id: g
    property var win: Window.window
    readonly property real z100: win.zoom / 100
    readonly property int rowH: Math.round(20 * z100)
    readonly property int headW: Math.max(30, Math.round(rowNumW.advanceWidth + 12))
    readonly property int colHeadH: 20
    readonly property var cols: library.columns
    readonly property int rows: table.rows
    readonly property int padCols: 26
    property real extraColW: 64 * z100

    function colX(c) {
        var x = 0
        for (var i = 0; i < c && i < cols.length; i++) x += cols[i].width * z100
        if (c > cols.length) x += (c - cols.length) * extraColW
        return x
    }
    function colW(c) { return c < cols.length ? cols[c].width * z100 : extraColW }
    function colAt(x) {
        var acc = 0
        for (var i = 0; i < cols.length; i++) {
            acc += cols[i].width * z100
            if (x < acc) return i
        }
        return cols.length + Math.floor((x - acc) / extraColW)
    }
    function ensureVisible(r, c) {
        var y = r * rowH
        if (y < table.contentY) table.contentY = y
        else if (y + rowH > table.contentY + table.height) table.contentY = y + rowH - table.height
        var x = colX(c), w = colW(c)
        if (x < table.contentX) table.contentX = x
        else if (x + w > table.contentX + table.width) table.contentX = Math.min(x + w - table.width, Math.max(0, table.contentWidth - table.width))
    }
    function moveTo(r, c, extend) {
        r = Math.max(0, Math.min(r, Math.max(0, rows - 1)))
        c = Math.max(0, Math.min(c, cols.length - 1))
        win.curRow = r
        win.curCol = c
        if (!extend) { win.anchorRow = r; win.anchorCol = c }
        ensureVisible(r, c)
    }

    TextMetrics { id: rowNumW; font.family: uiFont; font.pixelSize: X.fsSmall; text: String(g.rows + 40) }

    // ---- column letters ----
    Rectangle {
        id: colHead
        visible: win.headings
        x: g.headW
        width: parent.width - x
        height: visible ? g.colHeadH : 0
        color: X.header
        clip: true
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.headerLine }
        Repeater {
            model: g.cols.length + g.padCols
            delegate: Rectangle {
                required property int index
                readonly property bool sel: index >= Math.min(win.curCol, win.anchorCol) && index <= Math.max(win.curCol, win.anchorCol)
                x: g.colX(index) - table.contentX
                width: g.colW(index)
                height: g.colHeadH
                visible: x + width > 0 && x < colHead.width
                color: sel ? X.headerSel : X.header
                T {
                    anchors.centerIn: parent
                    text: X.colName(index)
                    font.pixelSize: X.fsSmall
                    color: parent.sel ? X.greenText : X.ink
                }
                Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: X.headerLine }
                Rectangle { visible: parent.sel; anchors.bottom: parent.bottom; width: parent.width; height: 2; color: X.accent }
                MouseArea {
                    anchors.fill: parent
                    onClicked: if (index < g.cols.length) { win.anchorRow = 0; win.curRow = Math.max(0, g.rows - 1); win.curCol = win.anchorCol = index }
                }
            }
        }
    }
    // the select-all corner
    Rectangle {
        visible: win.headings
        width: g.headW
        height: g.colHeadH
        color: X.header
        Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: X.headerLine }
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.headerLine }
        Canvas {
            anchors { right: parent.right; bottom: parent.bottom; margins: 3 }
            width: 10
            height: 10
            property bool dk: X.dark
            onDkChanged: requestPaint()
            onPaint: {
                var c = getContext("2d")
                c.fillStyle = X.cornerMark
                c.beginPath(); c.moveTo(10, 0); c.lineTo(10, 10); c.lineTo(0, 10); c.closePath(); c.fill()
            }
        }
        MouseArea { anchors.fill: parent; onClicked: win.selectAll() }
    }

    // ---- row numbers ----
    Rectangle {
        id: rowHead
        visible: win.headings
        y: colHead.height
        width: visible ? g.headW : 0
        height: parent.height - y
        color: X.header
        clip: true
        Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: X.headerLine }
        // row 1: the frozen table header
        Rectangle {
            width: parent.width
            height: g.rowH
            color: X.header
            T { anchors.centerIn: parent; text: "1"; font.pixelSize: X.fsSmall }
            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.headerLine }
            Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: X.headerLine }
        }
        Item {
            y: g.rowH
            width: parent.width
            height: parent.height - y
            clip: true
            Repeater {
                model: Math.max(0, Math.ceil(parent.height / g.rowH) + 1)
                delegate: Rectangle {
                    required property int index
                    readonly property int r: Math.floor(table.contentY / g.rowH) + index
                    readonly property bool sel: r >= win.selTop && r <= win.selBottom
                    y: r * g.rowH - table.contentY
                    width: rowHead.width
                    height: g.rowH
                    color: sel ? X.headerSel : X.header
                    T {
                        anchors.centerIn: parent
                        text: parent.r + 2
                        font.pixelSize: X.fsSmall
                        color: parent.sel ? X.greenText : X.ink
                    }
                    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.headerLine }
                    Rectangle { anchors.right: parent.right; width: parent.sel ? 2 : 1; height: parent.height; color: parent.sel ? X.accent : X.headerLine }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: (m) => { if (parent.r < g.rows) { g.moveTo(parent.r, 0, m.modifiers & Qt.ShiftModifier); if (!(m.modifiers & Qt.ShiftModifier)) win.anchorCol = 0; win.curCol = g.cols.length - 1 } }
                        onDoubleClicked: if (parent.r < g.rows) library.activate(parent.r, 0)
                    }
                }
            }
        }
    }

    // ---- sheet ----
    Item {
        id: sheet
        x: rowHead.width
        y: colHead.height
        width: parent.width - x
        height: parent.height - y
        clip: true

        Rectangle { anchors.fill: parent; color: X.paper }

        // the empty sheet: gridlines everywhere, under the cells
        Item {
            anchors.fill: parent
            visible: win.gridlines
            Repeater {
                model: Math.max(0, Math.ceil(sheet.height / g.rowH) + 2)
                delegate: Rectangle {
                    required property int index
                    y: g.rowH + (index + 1) * g.rowH - (table.contentY % g.rowH) - 1
                    width: sheet.width
                    height: 1
                    color: X.grid
                }
            }
            Repeater {
                model: g.cols.length + g.padCols
                delegate: Rectangle {
                    required property int index
                    x: g.colX(index + 1) - table.contentX - 1
                    width: 1
                    height: sheet.height
                    color: X.grid
                }
            }
        }

        // row 1, frozen: the table header with its filter buttons
        Item {
            id: headRow
            width: parent.width
            height: g.rowH
            z: 2
            Repeater {
                model: g.cols
                delegate: Rectangle {
                    id: hc
                    required property int index
                    required property var modelData
                    x: g.colX(index) - table.contentX
                    width: g.colW(index)
                    height: g.rowH
                    color: X.tableHead
                    Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: "#FFFFFF"; opacity: 0.5 }
                    T {
                        x: 4
                        width: parent.width - 26
                        height: parent.height
                        font.family: cellFont
                        font.pixelSize: Math.round(X.fsCell * g.z100)
                        font.bold: true
                        color: "#FFFFFF"
                        text: hc.modelData.title
                        horizontalAlignment: hc.modelData.kind === "text" || hc.modelData.kind === "stems" ? Text.AlignLeft : Text.AlignRight
                        rightPadding: hc.modelData.kind === "text" || hc.modelData.kind === "stems" ? 0 : 2
                    }
                    // the AutoFilter button: sorts this column
                    Rectangle {
                        anchors { right: parent.right; rightMargin: 3; verticalCenter: parent.verticalCenter }
                        width: Math.round(15 * g.z100)
                        height: width
                        color: fm.containsMouse ? X.hoverSoft : X.paper
                        border.color: X.borderStrong
                        Ico {
                            anchors.centerIn: parent
                            size: Math.round(12 * g.z100)
                            name: library.sortColumn === hc.index ? (library.sortDescending ? "sort_down" : "sort_up") : "dropdown"
                        }
                        MouseArea {
                            id: fm
                            anchors.fill: parent
                            hoverEnabled: true
                            onClicked: { g.win.curCol = g.win.anchorCol = hc.index; filterMenu.col = hc.index; filterMenu.popup() }
                        }
                    }
                    MouseArea {
                        anchors { fill: parent; rightMargin: 20 }
                        onClicked: library.sortBy(hc.index)
                    }
                }
            }
        }

        TableView {
            id: table
            y: g.rowH
            width: parent.width
            height: parent.height - y
            model: sheetModel
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            reuseItems: true
            columnWidthProvider: (c) => g.colW(c)
            rowHeightProvider: (r) => g.rowH
            interactive: false  // the wheel and the scrollbars move it; dragging selects
            ScrollBar.vertical: XScrollBar { id: vbar }
            ScrollBar.horizontal: XScrollBar { id: hbar }
            Connections {
                target: g.win
                function onZoomChanged() { Qt.callLater(table.forceLayout) }
            }

            delegate: Rectangle {
                id: cell
                required property int row
                required property int column
                required property string display
                required property string align
                required property string cellStyle
                required property bool playing
                readonly property bool bar: cellStyle.indexOf("bar:") === 0
                color: cellStyle === "good" ? X.good : (row % 2 === 1 ? X.tableBand : X.paper)
                Rectangle {  // data bar
                    visible: cell.bar
                    x: 2
                    y: 2
                    height: parent.height - 4
                    width: cell.bar ? (parent.width - 4) * parseFloat(cell.cellStyle.slice(4)) : 0
                    color: X.dataBar
                    opacity: 0.85
                }
                Rectangle { visible: g.win.gridlines; anchors.right: parent.right; width: 1; height: parent.height; color: X.grid }
                Rectangle { visible: g.win.gridlines; anchors.bottom: parent.bottom; width: parent.width; height: 1; color: X.grid }
                Text {
                    anchors.fill: parent
                    leftPadding: 3
                    rightPadding: 4
                    verticalAlignment: Text.AlignBottom
                    bottomPadding: Math.round(2 * g.z100)
                    horizontalAlignment: cell.align === "right" ? Text.AlignRight : Text.AlignLeft
                    font.family: cellFont
                    font.pixelSize: Math.round(X.fsCell * g.z100)
                    font.bold: cell.playing
                    color: cell.cellStyle === "good" ? X.goodText : X.cellText
                    text: cell.cellStyle === "good" ? cell.display + " stems" : cell.display
                    elide: cell.align === "right" ? Text.ElideLeft : Text.ElideRight
                    renderType: Text.NativeRendering
                }
                // Excel's green error triangle, here marking the song that's playing
                Canvas {
                    visible: cell.playing && cell.column === 0
                    width: 7
                    height: 7
                    property bool dk: X.dark
                    onDkChanged: requestPaint()
                    onPaint: {
                        var c = getContext("2d")
                        c.fillStyle = X.accent
                        c.beginPath(); c.moveTo(0, 0); c.lineTo(7, 0); c.lineTo(0, 7); c.closePath(); c.fill()
                    }
                }
            }

            // selection: range fill, green border, fill handle
            Rectangle {
                id: selBox
                parent: table.contentItem
                z: 50
                visible: g.rows > 0
                readonly property bool range: g.win.selTop !== g.win.selBottom || g.win.curCol !== g.win.anchorCol
                x: g.colX(Math.min(g.win.curCol, g.win.anchorCol)) - 1
                y: g.win.selTop * g.rowH - 1
                width: g.colX(Math.max(g.win.curCol, g.win.anchorCol) + 1) - x + 1
                height: (g.win.selBottom - g.win.selTop + 1) * g.rowH + 1
                color: range ? X.selFill : "transparent"
                border.color: X.accent
                border.width: 2
                Rectangle {
                    // the active cell stays clear inside a range
                    visible: selBox.range
                    x: g.colX(g.win.curCol) - parent.x
                    y: g.win.curRow * g.rowH - parent.y
                    width: g.colW(g.win.curCol)
                    height: g.rowH
                    color: X.paper
                    opacity: 0.6
                }
                Rectangle {
                    x: parent.width - 4
                    y: parent.height - 4
                    width: 7
                    height: 7
                    color: X.accent
                    border.color: X.paper
                    border.width: 1
                }
            }

            MouseArea {
                parent: table.contentItem
                z: 60
                width: Math.max(table.contentWidth, table.width)
                height: Math.max(table.contentHeight, table.height)
                acceptedButtons: Qt.LeftButton | Qt.RightButton
                property bool dragging: false
                function cellAt(m) { return [Math.floor(m.y / g.rowH), g.colAt(m.x)] }
                onPressed: (m) => {
                    g.forceActiveFocus()
                    var rc = cellAt(m)
                    if (rc[0] >= g.rows) return
                    if (m.button === Qt.RightButton) {
                        if (rc[0] < g.win.selTop || rc[0] > g.win.selBottom) g.moveTo(rc[0], rc[1], false)
                        ctxMenu.popup()
                        return
                    }
                    g.moveTo(rc[0], rc[1], m.modifiers & Qt.ShiftModifier)
                    dragging = true
                }
                onPositionChanged: (m) => {
                    if (!dragging) return
                    var rc = cellAt(m)
                    g.moveTo(rc[0], rc[1], true)
                }
                onReleased: dragging = false
                onDoubleClicked: (m) => { var rc = cellAt(m); if (rc[0] < g.rows) library.activate(rc[0], 0) }
                onWheel: (w) => {
                    if (w.modifiers & Qt.ControlModifier) {
                        g.win.setZoom(g.win.zoom + (w.angleDelta.y > 0 ? 10 : -10))
                        return
                    }
                    var dy = w.pixelDelta.y !== 0 ? w.pixelDelta.y : w.angleDelta.y / 120 * 3 * g.rowH
                    var dx = w.pixelDelta.x !== 0 ? w.pixelDelta.x : w.angleDelta.x / 120 * 3 * g.rowH
                    if (w.modifiers & Qt.ShiftModifier) { dx = dy; dy = 0 }
                    table.contentY = Math.max(0, Math.min(table.contentY - dy, table.contentHeight - table.height))
                    table.contentX = Math.max(0, Math.min(table.contentX - dx, Math.max(0, table.contentWidth - table.width)))
                }
            }
        }

        // #GETTING_DATA: what Excel shows while a query runs
        T {
            visible: library.loading
            x: 4
            y: g.rowH
            height: g.rowH
            font.family: cellFont
            font.pixelSize: Math.round(X.fsCell * g.z100)
            color: X.cellText
            text: "#GETTING_DATA"
        }
        // a cell comment when there's nothing to show
        Rectangle {
            visible: g.rows === 0 && !library.loading
            x: g.colX(1) + 30
            y: g.rowH * 2
            width: 300
            height: note.implicitHeight + 16
            color: X.note
            border.color: X.borderStrong
            Rectangle { x: -30; y: 6; width: 30; height: 1; color: X.cellText }
            Text {
                id: note
                color: X.ink
                x: 8
                y: 8
                width: parent.width - 16
                wrapMode: Text.WordWrap
                font.family: uiFont
                font.pixelSize: X.fsUi
                textFormat: Text.StyledText
                text: !library.signedIn ? "<b>Stemify:</b><br>This workbook isn't connected yet. Go to <b>Data → Connect to Plex</b> and sign in with the code."
                    : library.layout === "queue" ? "<b>Stemify:</b><br>The queue is empty. Double-click a song on any sheet to play it."
                    : library.status !== "" ? "<b>Stemify:</b><br>Loading the library from Plex…"
                    : "<b>Stemify:</b><br>Nothing on this sheet."
            }
        }
    }

    Keys.onPressed: (e) => {
        var ext = e.modifiers & Qt.ShiftModifier
        var ctrl = e.modifiers & Qt.ControlModifier
        var page = Math.max(1, Math.floor(table.height / g.rowH) - 1)
        switch (e.key) {
        case Qt.Key_Down: g.moveTo(ctrl ? g.rows - 1 : win.curRow + 1, win.curCol, ext); break
        case Qt.Key_Up: g.moveTo(ctrl ? 0 : win.curRow - 1, win.curCol, ext); break
        case Qt.Key_Right: if (ctrl && (e.modifiers & Qt.ShiftModifier)) { player.skipBy(10); break } if (ctrl) { player.next(); break } g.moveTo(win.curRow, win.curCol + 1, ext); break
        case Qt.Key_Left: if (ctrl && (e.modifiers & Qt.ShiftModifier)) { player.skipBy(-10); break } if (ctrl) { player.previous(); break } g.moveTo(win.curRow, win.curCol - 1, ext); break
        case Qt.Key_Tab: g.moveTo(win.curRow, win.curCol + 1, false); break
        case Qt.Key_PageDown: g.moveTo(win.curRow + page, win.curCol, ext); break
        case Qt.Key_PageUp: g.moveTo(win.curRow - page, win.curCol, ext); break
        case Qt.Key_Home: g.moveTo(ctrl ? 0 : win.curRow, 0, ext); break
        case Qt.Key_End: g.moveTo(ctrl ? g.rows - 1 : win.curRow, g.cols.length - 1, ext); break
        case Qt.Key_Return:
        case Qt.Key_Enter: library.activate(win.curRow, 0); break
        case Qt.Key_Space: player.toggle(); break
        case Qt.Key_Delete: library.removeRows(win.selTop, win.selBottom); break
        case Qt.Key_Backspace: library.back(); break
        case Qt.Key_A: if (ctrl) win.selectAll(); else return; break
        case Qt.Key_C: if (ctrl) win.copySelection(); else return; break
        case Qt.Key_V: if (!ctrl) player.toggleVocals(); else return; break
        default: return
        }
        e.accepted = true
    }

    // A new sheet starts at the top; fresh data for the same sheet keeps its scroll position.
    property real keptY: 0
    property bool switching: false
    Connections {
        target: library
        function onSheetSwitched() { g.switching = true; table.contentY = 0; table.contentX = 0 }
    }
    Connections {
        target: sheetModel
        function onModelAboutToBeReset() { g.keptY = table.contentY }
        function onModelReset() {
            if (g.switching) { g.switching = false; return }
            Qt.callLater(() => { table.contentY = Math.min(g.keptY, Math.max(0, table.contentHeight - table.height)) })
        }
    }

    XMenu {
        id: ctxMenu
        Action { text: "Play"; icon.name: "play"; onTriggered: library.playRows(g.win.selTop, g.win.selBottom) }
        Action { text: "Play Next"; icon.name: "play_next"; onTriggered: library.playNextRows(g.win.selTop, g.win.selBottom) }
        Action { text: "Add to Queue"; icon.name: "queue_add"; onTriggered: library.queueRows(g.win.selTop, g.win.selBottom) }
        MenuSeparator {}
        Action { text: "Open"; icon.name: "right"; onTriggered: library.activate(g.win.curRow, 0) }
        Action { text: "New Playlist from Selection…"; icon.name: "playlist_new"; onTriggered: g.win.newPlaylist() }
        Action { text: "Copy"; onTriggered: g.win.copySelection() }
        MenuSeparator {}
        Action {
            text: "Delete Rows"
            icon.name: "delete_rows"
            enabled: library.layout === "queue" || g.win.inPlaylist
            onTriggered: library.removeRows(g.win.selTop, g.win.selBottom)
        }
    }
    XMenu {
        id: filterMenu
        property int col: 0
        Action { text: "Sort A to Z"; icon.name: "sort_az"; onTriggered: g.win.sortColumn(filterMenu.col, false) }
        Action { text: "Sort Z to A"; icon.name: "sort_za"; onTriggered: g.win.sortColumn(filterMenu.col, true) }
    }
}
