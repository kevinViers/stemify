import QtQuick
import QtQuick.Controls.Basic

// The "Format Mix" task pane: what's playing, a sparkline to seek with, and the stems as a chart.
Rectangle {
    id: pane
    property var win: Window.window
    color: "#FFFFFF"
    Rectangle { width: 1; height: parent.height; color: X.ribbonLine }

    Item {
        id: head
        x: 1
        width: parent.width - 1
        height: 40
        T { x: 14; height: parent.height; text: "Format Mix"; font.pixelSize: 17; color: X.ink }
        Ico { anchors.right: parent.right; anchors.rightMargin: 34; anchors.verticalCenter: parent.verticalCenter; name: "dropdown"; size: 12 }
        Item {
            anchors { right: parent.right; rightMargin: 6; verticalCenter: parent.verticalCenter }
            width: 24
            height: 24
            Rectangle { anchors.fill: parent; color: cm.containsMouse ? X.hover : "transparent" }
            Ico { anchors.centerIn: parent; name: "close"; size: 20 }
            MouseArea { id: cm; anchors.fill: parent; hoverEnabled: true; onClicked: pane.win.toggleMixer() }
        }
    }

    Flickable {
        id: flick
        x: 1
        y: head.height
        width: parent.width - 1
        height: parent.height - y
        contentHeight: col.height + 20
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        ScrollBar.vertical: XScrollBar { policy: flick.contentHeight > flick.height ? ScrollBar.AlwaysOn : ScrollBar.AlwaysOff }

        Column {
            id: col
            x: 14
            width: flick.width - 34
            spacing: 10

            // ---- now playing ----
            Row {
                width: parent.width
                spacing: 12
                Rectangle {
                    width: 128
                    height: 128
                    color: "#F2F2F2"
                    border.color: "#D9D9D9"
                    Image {
                        id: art
                        anchors.fill: parent
                        anchors.margins: 1
                        source: player.track.art || ""
                        fillMode: Image.PreserveAspectCrop
                        asynchronous: true
                        cache: true
                        sourceSize.width: 256
                        sourceSize.height: 256
                    }
                    Ico { visible: art.status !== Image.Ready; anchors.centerIn: parent; name: "excel"; size: 48; opacity: 0.25 }
                }
                Column {
                    width: parent.width - 140
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 3
                    Text {
                        width: parent.width
                        text: player.track.title || "Nothing playing"
                        font.family: uiFont
                        font.pixelSize: 16
                        font.bold: true
                        color: "#222222"
                        wrapMode: Text.WordWrap
                        maximumLineCount: 3
                        elide: Text.ElideRight
                    }
                    T { width: parent.width; text: player.track.artist || "Double-click a song to play it"; font.pixelSize: 13; color: X.inkSoft }
                    Text {
                        width: parent.width
                        visible: !!player.track.album
                        text: (player.track.album || "") + (player.track.year ? " · " + player.track.year : "")
                        font.family: uiFont
                        font.pixelSize: 12
                        color: X.inkFaint
                        wrapMode: Text.WordWrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                    }
                }
            }

            // ---- the sparkline seek bar ----
            Item {
                id: spark
                width: parent.width
                height: 46
                readonly property real dur: engine.duration > 0 ? engine.duration : (player.track.durationMs || 0) / 1000
                readonly property real frac: dur > 0 ? Math.min(1, pane.win.playPos / dur) : 0
                readonly property var bars: player.wave.length ? player.wave : []
                readonly property int n: bars.length ? bars.length : 80
                property real hoverFrac: -1
                Rectangle { anchors.fill: parent; color: "#FFFFFF"; border.color: "#D9D9D9" }
                Canvas {
                    id: sparkCanvas
                    anchors.fill: parent
                    anchors.margins: 3
                    property real frac: spark.frac
                    property var bars: spark.bars
                    onFracChanged: requestPaint()
                    onBarsChanged: requestPaint()
                    onWidthChanged: requestPaint()
                    onPaint: {
                        var c = getContext("2d")
                        c.reset()
                        var n = spark.n, w = width / n, h = height
                        for (var i = 0; i < n; i++) {
                            var v = bars.length ? bars[i] : 0.12
                            var bh = Math.max(2, v * h)
                            c.fillStyle = (i + 0.5) / n <= frac ? "#217346" : "#A9D08E"
                            c.fillRect(i * w + w * 0.15, h - bh, Math.max(1, w * 0.7), bh)
                        }
                    }
                }
                Rectangle {  // playhead
                    visible: spark.dur > 0
                    x: 3 + spark.frac * (parent.width - 6) - 1
                    y: 2
                    width: 2
                    height: parent.height - 4
                    color: "#C00000"
                }
                Rectangle {
                    visible: spark.hoverFrac >= 0
                    x: Math.max(0, Math.min(parent.width - width, spark.hoverFrac * parent.width - width / 2))
                    y: -22
                    width: hv.implicitWidth + 10
                    height: 18
                    color: "#FFFFFF"
                    border.color: "#BEBEBE"
                    T { id: hv; anchors.centerIn: parent; font.pixelSize: X.fsSmall; text: X.time(spark.hoverFrac * spark.dur) }
                }
                MouseArea {
                    anchors.fill: parent
                    hoverEnabled: true
                    function f(m) { return Math.max(0, Math.min(1, (m.x - 3) / (width - 6))) }
                    onPositionChanged: (m) => { spark.hoverFrac = f(m); if (pressed) pane.win.seekTo(f(m) * spark.dur) }
                    onExited: spark.hoverFrac = -1
                    onPressed: (m) => pane.win.seekTo(f(m) * spark.dur)
                }
            }
            Item {
                width: parent.width
                height: 14
                y: -6
                T { font.pixelSize: X.fsSmall; color: X.inkSoft; text: X.time(pane.win.playPos) }
                T {
                    anchors.right: parent.right
                    font.pixelSize: X.fsSmall
                    color: X.inkSoft
                    text: spark.dur > 0 ? "-" + X.time(spark.dur - pane.win.playPos) : ""
                }
            }

            // ---- transport ----
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 6
                PaneButton { icon: "shuffle"; checked: player.shuffleOn; tip: "Shuffle"; onClicked: player.toggleShuffle() }
                PaneButton { icon: "previous"; tip: "Previous"; onClicked: player.previous() }
                PaneButton {
                    icon: player.status === "playing" ? "pause" : "play"
                    size: 40
                    tip: "Play / Pause (Space)"
                    onClicked: player.toggle()
                }
                PaneButton { icon: "next"; tip: "Next"; onClicked: player.next() }
                PaneButton {
                    icon: player.repeatMode === "one" ? "repeat_one" : "repeat"
                    checked: player.repeatMode !== "off"
                    tip: player.repeatMode === "off" ? "Repeat" : player.repeatMode === "all" ? "Repeat all" : "Repeat one"
                    onClicked: player.cycleRepeat()
                }
            }

            // output meter, as a pair of data bars
            Column {
                width: parent.width
                spacing: 2
                Repeater {
                    model: 2
                    delegate: Rectangle {
                        required property int index
                        width: parent.width
                        height: 5
                        color: "#EDEDED"
                        Rectangle {
                            readonly property real lv: index === 0 ? pane.win.levelL : pane.win.levelR
                            readonly property real db: 20 * Math.log(Math.max(lv, 1e-5)) / Math.LN10
                            width: parent.width * Math.max(0, Math.min(1, (db + 48) / 48))
                            height: parent.height
                            color: db > -1 ? "#C00000" : db > -9 ? "#FFC000" : X.dataBar
                        }
                    }
                }
            }

            // ---- section: Stem Mix ----
            Row {
                spacing: 6
                height: 24
                Ico { anchors.verticalCenter: parent.verticalCenter; name: "dropdown"; size: 10 }
                T { anchors.verticalCenter: parent.verticalCenter; text: "Stem Mix"; font.bold: true; font.pixelSize: 13 }
            }

            Rectangle {  // "comment" for songs without stems
                visible: player.sourceKind === "original"
                width: parent.width
                height: noteTxt.implicitHeight + 16
                color: X.note
                border.color: "#B2B2B2"
                Text {
                    id: noteTxt
                    x: 8
                    y: 8
                    width: parent.width - 16
                    wrapMode: Text.WordWrap
                    font.family: uiFont
                    font.pixelSize: X.fsUi
                    textFormat: Text.StyledText
                    text: "<b>Stemify:</b><br>This song hasn't been separated yet, so it plays the original file. The server's stemd will get to it."
                }
            }

            StemChart {
                visible: player.sourceKind === "stems"
                width: parent.width
                height: 250
            }

            Row {
                visible: player.sourceKind === "stems"
                spacing: 8
                T { height: 22; text: "Mix style:" }
                Rectangle {
                    width: 150
                    height: 22
                    border.color: pm.containsMouse ? X.green : "#ABABAB"
                    T { x: 6; height: parent.height; width: parent.width - 24; text: player.presetLabel }
                    Ico { anchors.right: parent.right; anchors.rightMargin: 5; anchors.verticalCenter: parent.verticalCenter; name: "dropdown"; size: 10 }
                    MouseArea { id: pm; anchors.fill: parent; hoverEnabled: true; onClicked: presetMenu.popup() }
                }
            }
            XCheck {
                text: "Keep the mix for the next song"
                checked: player.keepMix
                onToggled: player.setKeepMix(!player.keepMix)
            }
        }
    }

    XMenu {
        id: presetMenu
        Instantiator {
            model: player.presets
            delegate: MenuItem {
                required property int index
                required property var modelData
                text: modelData.label
                enabled: modelData.usable
                checkable: true
                checked: modelData.active
                onTriggered: player.applyPreset(index)
            }
            onObjectAdded: (i, o) => presetMenu.insertItem(i, o)
            onObjectRemoved: (i, o) => presetMenu.removeItem(o)
        }
    }

    component PaneButton: Item {
        id: pb
        property string icon
        property bool checked: false
        property int size: 32
        property string tip: ""
        signal clicked()
        width: size
        height: size
        Rectangle {
            anchors.fill: parent
            radius: pb.size / 2
            color: pb.checked ? X.checked : pbm.pressed ? X.pressed : pbm.containsMouse ? X.hover : "transparent"
        }
        Ico { anchors.centerIn: parent; name: pb.icon; size: Math.round(pb.size * 0.55) }
        MouseArea { id: pbm; anchors.fill: parent; hoverEnabled: true; onClicked: pb.clicked() }
        Tip { target: pbm; text: pb.tip }
    }
}
