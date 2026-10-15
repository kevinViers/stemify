import QtQuick
import QtQuick.Controls.Basic

// Main pane for the selected track: progress, error, or stem lanes.
Item {
    id: tv
    property var win: Window.window
    readonly property var sel: app.selected
    readonly property string status: sel.valid ? sel.status : ""

    // Lane model only changes when the track or its stems change, so per-lane
    // UI state survives the frequent selectedChanged notifications.
    property string stemsKey: ""
    property var stems: []
    property var soloState: []
    property var muteState: []
    property var gainState: []
    property real viewStart: 0
    property real viewEnd: 1
    property int waveW: 100

    function syncStems() {
        var key = sel.valid && sel.status === "done" ? sel.id + ":" + sel.stems.map(s => s.name).join(",") : ""
        if (key === stemsKey) return
        stemsKey = key
        stems = key ? sel.stems : []
        soloState = stems.map(() => false)
        muteState = stems.map(() => false)
        gainState = stems.map(() => 0)
        viewStart = 0
        viewEnd = Math.max(0.01, sel.duration || 1)
    }
    onSelChanged: syncStems()
    Component.onCompleted: syncStems()

    readonly property bool anySolo: soloState.indexOf(true) >= 0
    readonly property real span: Math.max(0.01, viewEnd - viewStart)
    readonly property real duration: Math.max(0.01, sel.duration || 1)

    function zoomAt(fx, factor) {
        var t = viewStart + fx * span
        var newSpan = Math.max(0.25, Math.min(duration, span * factor))
        var s = Math.max(0, Math.min(duration - newSpan, t - fx * newSpan))
        viewStart = s
        viewEnd = s + newSpan
    }
    function pan(dt) {
        var s = Math.max(0, Math.min(duration - span, viewStart + dt))
        viewEnd = s + span
        viewStart = s
    }

    // ------------------------------------------------------------- done
    Item {
        id: doneView
        anchors.fill: parent
        visible: tv.status === "done"

        Card {
            id: header
            anchors { left: parent.left; right: parent.right; top: parent.top; margins: 24; bottomMargin: 0 }
            height: 104
            Column {
                anchors { left: parent.left; leftMargin: 20; right: actions.left; rightMargin: 16; verticalCenter: parent.verticalCenter }
                spacing: 6
                Txt {
                    width: parent.width
                    text: tv.sel.fileName || ""
                    font.pixelSize: Theme.fs20
                    font.weight: Font.DemiBold
                }
                Txt {
                    width: parent.width
                    color: Theme.textLo
                    font.pixelSize: Theme.fs12
                    text: [tv.sel.modelLabel, tv.sel.deviceLabel, Theme.time(tv.sel.duration || 0, false),
                           tv.sel.elapsed > 0 ? "separated in " + tv.sel.elapsed.toFixed(1) + " s" : "from cache"]
                          .filter(s => s && s.length).join("  ·  ")
                }
                Row {
                    spacing: 6
                    Icon { name: "folder"; size: 14; color: Theme.textLo; anchors.verticalCenter: parent.verticalCenter }
                    Txt {
                        text: (tv.sel.exportedTo ? "Exported to " : "Exports to ") + (tv.sel.target || "")
                              + "  ·  " + appSettings.exportFormat.toUpperCase()
                        font.pixelSize: Theme.fs12
                        color: tv.sel.exportedTo ? Theme.success : Theme.textLo
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }
            Row {
                id: actions
                anchors { right: parent.right; rightMargin: 20; verticalCenter: parent.verticalCenter }
                spacing: 8
                AeroButton {
                    visible: (tv.sel.rerunLabel || "").length > 0
                    variant: "text"
                    iconName: "refresh"
                    text: "Re-run with " + (tv.sel.rerunLabel || "")
                    onClicked: app.rerunSelected()
                }
                IconButton {
                    anchors.verticalCenter: parent.verticalCenter
                    size: 40
                    iconName: "folder"
                    tip: "Open output folder"
                    onClicked: app.openTargetFolder()
                }
                AeroButton {
                    variant: "tonal"
                    iconName: "layers"
                    text: "Export mix"
                    onClicked: app.exportMix()
                }
                AeroButton {
                    variant: "filled"
                    iconName: "download"
                    text: "Export all"
                    onClicked: app.exportAll()
                }
            }
        }

        // timeline ruler + zoom hint
        Item {
            id: ruler
            anchors { left: parent.left; right: parent.right; top: header.bottom; leftMargin: 24; rightMargin: 24; topMargin: 12 }
            height: 22
            readonly property real waveX: 248
            Txt {
                anchors.verticalCenter: parent.verticalCenter
                text: tv.span < tv.duration - 0.01 ? "Zoomed  ·  Ctrl+wheel to zoom, Shift+wheel to pan, double-click to reset"
                                                    : "Ctrl+wheel to zoom"
                font.pixelSize: 11
                color: Theme.alpha(Theme.textLo, 0.8)
            }
            Repeater {
                model: 6
                Txt {
                    required property int index
                    x: ruler.waveX + (ruler.width - ruler.waveX - 8) * index / 5 - (index === 5 ? width : index === 0 ? 0 : width / 2)
                    anchors.verticalCenter: parent.verticalCenter
                    text: Theme.time(tv.viewStart + tv.span * index / 5, tv.span < 20)
                    font.pixelSize: 11
                    font.features: { "tnum": 1 }
                    color: Theme.textLo
                }
            }
        }

        Flickable {
            id: lanes
            anchors { left: parent.left; right: parent.right; top: ruler.bottom; bottom: parent.bottom; leftMargin: 24; rightMargin: 24; topMargin: 6 }
            contentHeight: col.height + 24
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {
                policy: ScrollBar.AsNeeded
                contentItem: Rectangle { implicitWidth: 4; radius: 2; color: Qt.rgba(1, 1, 1, 0.18); visible: parent.size < 1 }
                background: Item {}
            }

            readonly property real waveX: ruler.waveX
            onWidthChanged: waveTimer.restart()
            Timer {  // debounce texture re-renders during window resize
                id: waveTimer
                interval: 90
                onTriggered: tv.waveW = Math.max(10, lanes.width - lanes.waveX - 8)
            }
            Component.onCompleted: tv.waveW = Math.max(10, lanes.width - lanes.waveX - 8)

            Column {
                id: col
                width: lanes.width
                spacing: 8
                topPadding: 4
                Repeater {
                    model: tv.stems
                    StemLane {
                        width: col.width
                        jobId: tv.sel.id || ""
                        waveX: lanes.waveX
                        waveW: tv.waveW
                        viewStart: tv.viewStart
                        viewEnd: tv.viewEnd
                        anySolo: tv.anySolo
                        solo: tv.soloState[index] || false
                        mute: tv.muteState[index] || false
                        gainDb: tv.gainState[index] || 0
                        onSoloToggled: (on) => {
                            var s = tv.soloState.slice(); s[index] = on; tv.soloState = s
                            audio.setSolo(index, on)
                        }
                        onMuteChanged: { var m = tv.muteState.slice(); m[index] = mute; tv.muteState = m }
                        onGainDbChanged: { var g = tv.gainState.slice(); g[index] = gainDb; tv.gainState = g }
                    }
                }
            }

            // click / drag on any waveform to seek
            MouseArea {
                x: lanes.waveX
                y: 0
                width: tv.waveW
                height: col.height
                cursorShape: Qt.IBeamCursor
                enabled: audio.loaded
                function seekTo(mx) {
                    var t = tv.viewStart + Math.max(0, Math.min(1, mx / width)) * tv.span
                    audio.seek(t)
                    tv.win.playPos = t
                }
                onPressed: (e) => seekTo(e.x)
                onPositionChanged: (e) => { if (pressed) seekTo(e.x) }
                onDoubleClicked: { tv.viewStart = 0; tv.viewEnd = tv.duration }
            }

            // playhead (position interpolated per frame from the audio ring buffer)
            Rectangle {
                readonly property real f: (tv.win.playPos - tv.viewStart) / tv.span
                visible: audio.loaded && f >= 0 && f <= 1
                x: lanes.waveX + f * tv.waveW - 1
                y: 2
                width: 2
                height: col.height
                color: "#FFFFFF"
                Glow { radius: 1; sigma: 3; opacity: 0.8 }
            }

            WheelHandler {
                acceptedModifiers: Qt.ControlModifier
                onWheel: (e) => {
                    var fx = Math.max(0, Math.min(1, (point.position.x - lanes.waveX) / tv.waveW))
                    tv.zoomAt(fx, e.angleDelta.y > 0 ? 0.8 : 1.25)
                }
            }
            WheelHandler {
                acceptedModifiers: Qt.ShiftModifier
                onWheel: (e) => tv.pan(-(e.angleDelta.y || e.angleDelta.x) / 120 * tv.span * 0.1)
            }
        }
    }

    // ------------------------------------------------- running / queued
    Card {
        visible: tv.status === "running" || tv.status === "queued"
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 620)
        height: 236
        elevation: 2
        Column {
            anchors { fill: parent; margins: 28 }
            spacing: 12
            Txt {
                width: parent.width
                text: tv.sel.fileName || ""
                font.pixelSize: Theme.fs20
                font.weight: Font.DemiBold
            }
            Txt {
                text: tv.status === "queued" ? "Waiting for the GPU  ·  " + (tv.sel.modelLabel || "")
                                             : (tv.sel.stage || "") + "  ·  " + (tv.sel.modelLabel || "")
                color: Theme.textLo
            }
            Row {
                spacing: 12
                Txt {
                    text: Math.floor((progressBar.shown) * 100) + "%"
                    font.pixelSize: Theme.fs28
                    font.weight: Font.DemiBold
                    font.features: { "tnum": 1 }
                    color: tv.status === "running" ? Theme.textHi : Theme.textLo
                }
            }
            StripedProgress {
                id: progressBar
                width: parent.width
                barHeight: 10
                value: tv.sel.progress || 0
                active: tv.status === "running"
                opacity: tv.status === "running" ? 1 : 0.4
            }
            Item { width: 1; height: 4 }
            AeroButton {
                variant: "tonal"
                tone: Theme.error
                iconName: "close"
                text: tv.status === "queued" ? "Remove from queue" : "Cancel"
                onClicked: app.cancelJob(tv.sel.id)
            }
        }
    }

    // ------------------------------------------------ error / cancelled
    Card {
        visible: tv.status === "error" || tv.status === "cancelled"
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 620)
        height: errCol.implicitHeight + 56
        Column {
            id: errCol
            anchors { left: parent.left; right: parent.right; top: parent.top; margins: 28 }
            spacing: 12
            Row {
                spacing: 10
                Icon {
                    name: tv.status === "error" ? "error" : "close"
                    size: 24
                    color: tv.status === "error" ? Theme.error : Theme.textLo
                    anchors.verticalCenter: parent.verticalCenter
                }
                Txt {
                    text: tv.status === "error" ? "Separation failed" : "Cancelled"
                    font.pixelSize: Theme.fs20
                    font.weight: Font.DemiBold
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
            Txt { width: parent.width; text: tv.sel.fileName || ""; color: Theme.textLo }
            Rectangle {
                visible: tv.status === "error"
                width: parent.width
                height: msg.implicitHeight + 20
                radius: 6
                color: Theme.alpha(Theme.error, 0.08)
                border.color: Theme.alpha(Theme.error, 0.25)
                Txt {
                    id: msg
                    anchors { fill: parent; margins: 10 }
                    text: tv.sel.message || ""
                    wrapMode: Text.WordWrap
                    elide: Text.ElideNone
                    font.pixelSize: Theme.fs12
                }
            }
            Row {
                spacing: 8
                AeroButton { variant: "filled"; iconName: "refresh"; text: "Retry"; onClicked: app.retryJob(tv.sel.id) }
                AeroButton { variant: "text"; tone: Theme.textLo; iconName: "delete"; text: "Remove"; onClicked: app.removeJob(tv.sel.id) }
            }
        }
    }
}
