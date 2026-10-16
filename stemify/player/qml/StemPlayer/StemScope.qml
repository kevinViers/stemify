import QtQuick

// A spectrum for each stem, after its fader: what each instrument is putting into the mix
// right now. Read from the engine once per presented frame, so it runs at the panel's rate.
Rectangle {
    id: sc
    color: X.paper
    border.color: X.borderSoft
    readonly property var stems: player.stems
    readonly property int n: Math.min(8, stems.length)  // engine MAX_STEMS
    readonly property int bands: engine.spectrumRange[0]
    readonly property real fLo: engine.spectrumRange[1]
    readonly property real fHi: engine.spectrumRange[2]
    readonly property real rowH: 30
    property var spec: []
    property bool live: false
    implicitHeight: plot.y + n * rowH + 26

    FrameAnimation {
        running: sc.visible && (engine.playing || sc.live)
        onTriggered: {
            var s = engine.spectrum()
            var any = false
            for (var i = 0; i < s.length && !any; i++) any = s[i] > 0
            sc.spec = s
            sc.live = any
        }
    }

    T {
        id: title
        anchors.horizontalCenter: parent.horizontalCenter
        y: 6
        text: "Spectrum"
        font.family: cellFont
        font.pixelSize: 17
        color: X.chartText
    }

    Item {
        id: plot
        x: 52
        y: title.y + title.height + 10
        width: parent.width - x - 10
        height: sc.n * sc.rowH
        readonly property real bw: width / sc.bands

        // frequency gridlines, labelled under the last row
        Repeater {
            model: [100, 1000, 10000]
            delegate: Item {
                required property var modelData
                x: plot.width * Math.log(modelData / sc.fLo) / Math.log(sc.fHi / sc.fLo)
                height: plot.height
                Rectangle { width: 1; height: parent.height; color: X.chartLine }
                T {
                    x: -20
                    width: 40
                    y: parent.height + 3
                    horizontalAlignment: Text.AlignHCenter
                    font.family: cellFont
                    font.pixelSize: 11
                    color: X.chartText
                    text: modelData >= 1000 ? modelData / 1000 + "k" : modelData
                }
            }
        }

        Repeater {
            model: sc.n
            delegate: Item {
                id: row
                required property int index
                readonly property var stem: sc.stems[index] || ({ label: "", color: "transparent", audible: false })
                y: index * sc.rowH
                width: plot.width
                height: sc.rowH

                T {
                    x: -plot.x + 4
                    width: plot.x - 10
                    height: parent.height
                    horizontalAlignment: Text.AlignRight
                    elide: Text.ElideRight
                    font.family: cellFont
                    font.pixelSize: 12
                    color: X.chartText
                    text: row.stem.label
                }
                Repeater {
                    model: sc.bands
                    delegate: Rectangle {
                        required property int index
                        readonly property real v: sc.spec[row.index * sc.bands + index] || 0
                        x: index * plot.bw
                        width: Math.max(1, plot.bw - 1)
                        height: (row.height - 3) * v
                        y: row.height - 1 - height
                        visible: v > 0
                        color: row.stem.color
                        opacity: row.stem.audible ? 1 : 0.22
                    }
                }
                Rectangle { y: parent.height - 1; width: parent.width; height: 1; color: X.borderStrong }
            }
        }
    }
}
