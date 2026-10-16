import QtQuick

// The stem faders as an Excel column chart. Drag a column to set its level; double-click
// for 0 dB. Below each category: M(ute) and S(olo).
Rectangle {
    id: ch
    color: X.paper
    border.color: X.borderSoft
    readonly property var stems: player.stems
    readonly property int n: stems.length
    readonly property real unity: 0.8

    T {
        id: title
        anchors.horizontalCenter: parent.horizontalCenter
        y: 6
        text: "Stem Mix"
        font.family: cellFont
        font.pixelSize: 17
        color: X.chartText
    }

    Item {
        id: plot
        x: 34
        y: title.y + title.height + 18
        width: parent.width - x - 8
        height: parent.height - y - 52

        // value axis: fader position, 0 dB marked
        Repeater {
            model: [0, 0.25, 0.5, 0.8, 1.0]
            delegate: Item {
                required property var modelData
                y: plot.height * (1 - modelData)
                width: plot.width
                Rectangle { width: parent.width; height: 1; color: modelData === 0.8 ? X.borderStrong : X.chartLine }
                T {
                    x: -32
                    width: 28
                    y: -7
                    height: 14
                    horizontalAlignment: Text.AlignRight
                    font.family: cellFont
                    font.pixelSize: 11
                    color: X.chartText
                    text: modelData === 0 ? "-∞" : modelData === 0.8 ? "0 dB" : modelData === 1 ? "+4" : (40 * Math.log(modelData / 0.8) / Math.LN10).toFixed(0)
                }
            }
        }

        Repeater {
            model: ch.stems
            delegate: Item {
                id: cat
                required property int index
                required property var modelData
                readonly property real cw: plot.width / Math.max(1, ch.n)
                x: index * cw
                width: cw
                height: plot.height

                Rectangle {
                    id: bar
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: cat.cw * 0.62
                    height: Math.max(1, plot.height * cat.modelData.level)
                    y: plot.height - height
                    color: cat.modelData.color
                    opacity: cat.modelData.audible ? 1 : 0.22
                    border.color: dm.containsMouse || dm.pressed ? X.cellText : "transparent"
                    Behavior on height { enabled: !dm.pressed; NumberAnimation { duration: X.easeMs; easing.type: Easing.OutCubic } }
                }
                T {  // data label
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: bar.y - 16
                    font.family: cellFont
                    font.pixelSize: 11
                    color: X.chartText
                    text: cat.modelData.muted ? "Muted" : !cat.modelData.audible ? "—" : cat.modelData.level <= 0.001 ? "-∞" : (cat.modelData.db >= 0 ? "+" : "") + cat.modelData.db.toFixed(1)
                }
                MouseArea {
                    id: dm
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.SizeVerCursor
                    function set(m) { player.setLevel(cat.index, Math.max(0, Math.min(1, 1 - m.y / plot.height))) }
                    onPressed: (m) => set(m)
                    onPositionChanged: (m) => { if (pressed) set(m) }
                    onDoubleClicked: player.setLevel(cat.index, ch.unity)
                    onWheel: (w) => player.setLevel(cat.index, Math.max(0, Math.min(1, cat.modelData.level + (w.angleDelta.y > 0 ? 0.02 : -0.02))))
                }
                Tip { target: dm; text: cat.modelData.label + ": drag to set, double-click for 0 dB" }

                // category axis label and the M / S toggles
                Column {
                    y: plot.height + 4
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: 3
                    T {
                        anchors.horizontalCenter: parent.horizontalCenter
                        font.family: cellFont
                        font.pixelSize: 12
                        color: X.chartText
                        text: cat.modelData.label
                        width: Math.min(implicitWidth, cat.cw)
                    }
                    Row {
                        anchors.horizontalCenter: parent.horizontalCenter
                        spacing: 2
                        Toggle { label: "M"; on: cat.modelData.muted; onFill: X.bad; onText: X.badText; tip: "Mute " + cat.modelData.label; onClicked: player.toggleMute(cat.index) }
                        Toggle { label: "S"; on: cat.modelData.solo; onFill: X.neutral; onText: X.neutralText; tip: "Solo " + cat.modelData.label; onClicked: player.toggleSolo(cat.index) }
                    }
                }
            }
        }
        Rectangle { y: plot.height; width: plot.width; height: 1; color: X.borderStrong }
    }

    component Toggle: Rectangle {
        id: tg
        property string label
        property bool on: false
        property color onFill
        property color onText
        property string tip
        signal clicked()
        width: 18
        height: 18
        color: on ? onFill : tgm.containsMouse ? X.hoverSoft : X.paper
        border.color: on ? onText : X.borderStrong
        T { anchors.centerIn: parent; text: tg.label; font.pixelSize: 11; font.bold: tg.on; color: tg.on ? tg.onText : X.inkSoft }
        MouseArea { id: tgm; anchors.fill: parent; hoverEnabled: true; onClicked: tg.clicked() }
        Tip { target: tgm; text: tg.tip }
    }
}
