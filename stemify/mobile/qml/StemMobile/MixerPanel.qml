import QtQuick
import QtQuick.Controls.Basic

// The stem mixer: one-tap mixes, a fader per stem with mute, solo and a live level meter, and
// the master volume.
Flickable {
    id: m
    property var pal: Ios
    readonly property var stems: player.stems
    readonly property bool hasStems: player.sourceKind === "stems"
    contentHeight: col.height
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    ScrollBar.vertical: ScrollBar { width: 6; contentItem: Rectangle { implicitWidth: 3; radius: 1.5; color: m.pal.label3; opacity: parent.active ? 0.9 : 0 } }

    // Live meters every frame, showing the block that's audible now: quick attack, ~130 ms fall.
    property var levels: []
    property real last: 0
    FrameAnimation {
        running: m.visible && player.playing
        onTriggered: {
            var raw = engine.levels(), now = Date.now()
            var fall = m.last ? Math.exp(-(now - m.last) / 1000 / 0.13) : 0
            m.last = now
            var out = []
            for (var i = 0; i < m.stems.length; i++) out.push(Math.max(raw[i] || 0, (m.levels[i] || 0) * fall))
            m.levels = out
        }
    }
    onVisibleChanged: if (!visible) { levels = []; last = 0 }

    Column {
        id: col
        x: 24
        width: m.width - 48
        Rectangle {
            visible: player.sourceKind === "original"
            width: parent.width; height: info.height + 28; radius: 12; color: m.pal.fill
            y: 8
            Txt {
                id: info
                x: 14; y: 14; width: parent.width - 28
                text: "No stems for this song yet, so you're hearing the original file. stemd on your server separates new music on its own; once this one's done, the app picks it up within a minute and plays it with stems next time."
                style: "footnote"; color: m.pal.label2; maximumLineCount: 5
            }
        }
        Item { visible: player.sourceKind === "original"; width: 1; height: 8 }
        Flickable {
            visible: m.hasStems
            width: parent.width; height: 48
            contentWidth: chips.width
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Row {
                id: chips
                y: 8
                spacing: 8
                Repeater {
                    model: player.presets
                    Chip { visible: modelData.usable; text: modelData.label; selected: modelData.active; pal: m.pal; tap: () => player.applyPreset(index) }
                }
            }
        }
        Repeater {
            model: m.stems.length  // a count, so rows survive level changes mid-drag
            Column {
                id: row
                width: col.width
                readonly property var st: m.stems[index] || ({})
                topPadding: 10
                readonly property color stemColor: Ios.stemColors[row.st.name] || "#8E8E93"
                Row {
                    width: parent.width
                    height: 26
                    spacing: 0
                    Rectangle { width: 10; height: 10; radius: 5; color: row.st.muted ? m.pal.label3 : row.stemColor; anchors.verticalCenter: parent.verticalCenter }
                    Item { width: 8; height: 1 }
                    Txt {
                        width: parent.width - 18 - pct.width - 10 - (m.hasStems ? 72 : 0)
                        text: row.st.label; style: "headline"; color: row.st.muted ? m.pal.label2 : m.pal.label
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Txt { id: pct; text: Math.round(Math.pow(Math.max(0, Math.min(1, row.st.level)) / 0.8, 2) * 100) + "%"; style: "footnote"; tnum: true; color: m.pal.label2; anchors.verticalCenter: parent.verticalCenter }
                    Item { width: 10; height: 1 }
                    ToggleBox { visible: m.hasStems; text: "M"; on: row.st.muted; onColor: m.pal.red; pal: m.pal; tap: () => player.toggleMute(index) }
                    Item { visible: m.hasStems; width: 6; height: 1 }
                    ToggleBox { visible: m.hasStems; text: "S"; on: row.st.solo; onColor: Ios.yellow; pal: m.pal; tap: () => player.toggleSolo(index) }
                }
                IosSlider {
                    width: parent.width
                    value: row.st.level
                    fill: row.st.muted ? m.pal.label3 : row.stemColor
                    detent: 0.8
                    thumb: 24
                    pal: m.pal
                    enabled: m.hasStems
                    moved: (v) => player.setLevel(index, v)
                }
                Item {
                    width: parent.width; height: 3
                    readonly property real lv: m.levels[index] || 0
                    Rectangle { x: 12; width: parent.width - 24; height: 3; radius: 1.5; color: m.pal.fill }
                    Rectangle {
                        x: 12; height: 3; radius: 1.5; color: row.stemColor
                        width: (parent.width - 24) * Math.min(1, Math.max(0, (20 * Math.log10(parent.lv + 1e-5) + 48) / 48))
                    }
                }
            }
        }
        Item { width: 1; height: 10 }
        Hairline { width: parent.width; pal: m.pal }
        Item { width: 1; height: 10 }
        Row {
            width: parent.width
            Txt { width: parent.width - mv.width; text: "Master"; style: "headline"; pal: m.pal }
            Txt { id: mv; text: Math.round(engine.volume * 100) + "%"; style: "footnote"; tnum: true; color: m.pal.label2 }
        }
        Row {
            width: parent.width
            spacing: 8
            Sym { name: "speaker"; color: m.pal.label2; size: 16; anchors.verticalCenter: parent.verticalCenter }
            IosSlider { width: parent.width - 16 - 18 - 16; value: engine.volume; fill: m.pal.label; pal: m.pal; moved: (v) => engine.setVolume(v) }
            Sym { name: "speakerWave"; color: m.pal.label2; size: 18; anchors.verticalCenter: parent.verticalCenter }
        }
        Item { width: 1; height: 16 }
    }
}
