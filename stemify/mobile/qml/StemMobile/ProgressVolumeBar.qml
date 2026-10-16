import QtQuick

// Plexamp's seek bar: the song's loudness as bars, the played part solid. The bars' height
// follows the master volume, so the volume is drawn over the progress. Drag sideways to seek,
// up and down for volume (or scroll), click to jump.
Column {
    id: b
    property var envelope: []
    property real duration: 0       // seconds
    property real position: 0       // seconds
    property real master: 0.8
    property color played: pal.label
    property color unplayed: pal.label3
    property string centerLabel: ""
    property var pal: Ios
    property real scrub: -1
    property bool adjusting: false
    readonly property real volScale: 0.14 + 0.86 * Math.sqrt(Math.sqrt(master * master * master))
    readonly property real frac: duration > 0 ? Math.max(0, Math.min(1, scrub >= 0 ? scrub : position / duration)) : 0
    readonly property real shown: scrub >= 0 ? scrub * duration : position
    spacing: 4

    Item {
        id: area
        width: b.width
        height: 56
        readonly property real barW: 2.7
        readonly property int count: Math.max(8, Math.floor(width / 4.6))
        readonly property real gap: (width - count * barW) / (count - 1)
        Rectangle {
            visible: b.adjusting
            y: area.height / 2 - area.height * b.volScale / 2; width: parent.width; height: 1
            color: Ios.alpha(b.pal.label2, 0.5)
        }
        Rectangle {
            visible: b.adjusting
            y: area.height / 2 + area.height * b.volScale / 2; width: parent.width; height: 1
            color: Ios.alpha(b.pal.label2, 0.5)
        }
        Repeater {
            model: area.count
            Rectangle {
                readonly property real a: {
                    var env = b.envelope
                    if (!env || !env.length) return 0.28
                    var from = Math.floor(index * env.length / area.count)
                    var to = Math.max(from + 1, Math.floor((index + 1) * env.length / area.count))
                    var m = 0
                    for (var k = from; k < Math.min(to, env.length); k++) m = Math.max(m, env[k])
                    return m
                }
                x: index * (area.barW + area.gap)
                width: area.barW
                height: area.barW + (area.height - area.barW) * a * b.volScale
                y: (area.height - height) / 2
                radius: area.barW / 2
                color: (x + area.barW / 2) / area.width <= b.frac ? b.played : b.unplayed
                Behavior on height { NumberAnimation { duration: 90 } }
            }
        }
        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            property int axis: 0  // 1 seek, 2 volume
            property real sx: 0
            property real sy: 0
            property real startVol: 0
            onPressed: (e) => { axis = 0; sx = e.x; sy = e.y; startVol = b.master }
            onPositionChanged: (e) => {
                if (!pressed) return
                var dx = e.x - sx, dy = e.y - sy
                if (axis === 0 && (Math.abs(dx) > 6 || Math.abs(dy) > 6)) {
                    axis = Math.abs(dx) >= Math.abs(dy) ? 1 : 2
                    if (axis === 2) b.adjusting = true
                }
                if (axis === 1) b.scrub = Math.max(0, Math.min(1, e.x / width))
                else if (axis === 2) engine.setVolume(Math.max(0, Math.min(1, startVol - dy / (height * 4.5))))
            }
            onReleased: (e) => {
                if (axis === 0) player.seek(Math.max(0, Math.min(1, e.x / width)) * b.duration)
                else if (axis === 1) player.seek(b.scrub * b.duration)
                b.scrub = -1; b.adjusting = false; axis = 0
            }
            onWheel: (w) => {
                b.adjusting = true; hideGuides.restart()
                engine.setVolume(Math.max(0, Math.min(1, b.master + (w.angleDelta.y || -w.angleDelta.x) / 120 * 0.05)))
            }
            Timer { id: hideGuides; interval: 900; onTriggered: b.adjusting = false }
        }
    }
    Item {
        width: b.width
        height: 18
        Txt { text: Api.duration(Math.floor(b.shown) * 1000); style: "caption1"; tnum: true; weight: 500; color: b.pal.label2 }
        Txt { anchors.right: parent.right; text: "-" + Api.duration(Math.max(0, b.duration - Math.floor(b.shown)) * 1000); style: "caption1"; tnum: true; weight: 500; color: b.pal.label2 }
        Row {
            anchors.centerIn: parent
            spacing: 4
            Sym { visible: b.adjusting; name: b.master <= 0.001 ? "speakerSlash" : "speakerWave"; color: b.pal.label2; size: 14; anchors.verticalCenter: parent.verticalCenter }
            Txt { visible: b.adjusting; text: Math.round(b.master * 100) + "%"; style: "caption1"; tnum: true; weight: 600; color: b.pal.label2 }
            Txt { visible: !b.adjusting && b.centerLabel !== ""; text: b.centerLabel; style: "caption2"; weight: 700; color: b.pal.label2 }
        }
    }
}
