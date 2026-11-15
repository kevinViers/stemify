import QtQuick

// What's downloading, what's next and what failed, with live progress and speed.
Page {
    id: page
    title: "Download Queue"
    grouped: true

    readonly property var q: downloads.queue
    readonly property var recs: { var m = {}; downloads.tracks.forEach(t => m[t.key] = t); return m }
    readonly property var running: q.active.filter(k => recs[k]).map(k => recs[k])
    readonly property var waiting: q.queued.filter(k => recs[k]).map(k => recs[k])
    readonly property var failed: downloads.tracks.filter(t => t.error).sort((a, b) => a.added - b.added)
    readonly property int remaining: running.length + waiting.length
    readonly property int batch: q.finished + remaining
    readonly property var pm: downloads.progressMap
    readonly property real overall: {
        if (batch === 0) return 1
        var f = q.finished
        running.forEach(t => f += pm[t.key] || 0)
        return f / batch
    }
    readonly property string status: remaining === 0 ? "All done" + (failed.length ? ", " + failed.length + " failed" : "") : "Downloading"

    // Speed over the last few seconds, sampled once a second.
    property real speed: 0
    property real fetched: downloads.bytesFetched()
    property real start: downloads.bytesFetched()
    property var window: []
    Timer {
        interval: 1000; repeat: true; running: true; triggeredOnStart: true
        onTriggered: {
            var now = downloads.bytesFetched(), w = page.window.concat([now])
            if (w.length > 4) w.shift()
            page.window = w
            page.speed = (w[w.length - 1] - w[0]) / Math.max(1, w.length - 1)
            page.fetched = now
        }
    }
    function rate(b) { return b <= 0 ? "Starting…" : b < 1e6 ? (b / 1e3).toFixed(0) + " KB/s" : (b / 1e6).toFixed(1) + " MB/s" }
    function size(b) { return b < 1e9 ? (b / 1e6).toFixed(0) + " MB" : (b / 1e9).toFixed(2) + " GB" }
    readonly property string facts: {
        var f = []
        if (remaining > 0) f.push(remaining + " of " + batch + " song" + (batch === 1 ? "" : "s") + " left")
        if (remaining > 0 && running.length) f.push(rate(speed))
        if (fetched > start) f.push(size(fetched - start) + " downloaded")
        return f.join(" · ")
    }

    component Bar: Rectangle {
        property real fraction: 0
        property int thickness: 3
        width: parent.width; height: thickness; radius: thickness / 2; color: Ios.fillStrong
        Rectangle { width: parent.width * Math.max(0, Math.min(1, parent.fraction)); height: parent.height; radius: parent.radius; color: Ios.accent }
    }
    component QueueRow: CellItem {
        id: row
        property var rec
        property real fraction: -1
        property string error: ""
        inset: 72
        height: 60
        Artwork { id: art; x: 16; anchors.verticalCenter: parent.verticalCenter; thumb: row.rec.thumb || ""; size: 44 }
        Column {
            x: 72; width: parent.width - 72 - 16 - (row.fraction >= 0 ? 48 : 0)
            anchors.verticalCenter: parent.verticalCenter
            spacing: 1
            Txt { width: parent.width; text: row.rec.title; maximumLineCount: 1 }
            Txt {
                width: parent.width; style: "subhead"; maximumLineCount: 1
                text: row.error || [row.rec.artist, row.rec.album].filter(s => s).join(" · ")
                color: row.error ? Ios.red : Ios.label2
            }
            Item { visible: row.fraction >= 0; width: parent.width; height: 9
                Bar { y: 6; fraction: row.fraction } }
        }
        Txt {
            visible: row.fraction >= 0
            anchors.right: parent.right; anchors.rightMargin: 16; anchors.verticalCenter: parent.verticalCenter
            text: Math.floor(row.fraction * 100) + "%"; style: "subhead"; color: Ios.label2
        }
    }

    header: Column {
        width: page.width
        EmptyState {
            visible: page.remaining === 0 && page.failed.length === 0
            icon: "download"; title: "Nothing Downloading"
            message: "Songs you download wait here until they're on the computer."
        }
        Section {
            visible: page.remaining > 0 || page.failed.length > 0
            CellItem {
                height: statusCol.height + 24
                Column {
                    id: statusCol
                    x: 16; y: 12; width: parent.width - 32
                    spacing: 8
                    Item {
                        width: parent.width; height: st.implicitHeight
                        Txt { id: st; text: page.status; style: "headline" }
                        Txt { visible: page.remaining > 0; anchors.right: parent.right; text: Math.floor(page.overall * 100) + "%"; style: "headline"; color: Ios.label2 }
                    }
                    Bar { fraction: page.overall; thickness: 6 }
                    Txt { width: parent.width; text: page.facts || " "; style: "subhead"; color: Ios.label2 }
                }
            }
        }
        Section {
            visible: page.running.length > 0
            header: "Downloading"
            Repeater { model: page.running; QueueRow { rec: modelData; fraction: page.pm[modelData.key] || 0 } }
        }
        Section {
            visible: page.waiting.length > 0
            header: "Up Next · " + page.waiting.length
            Repeater { model: page.waiting; QueueRow { rec: modelData } }
        }
        Section {
            visible: page.failed.length > 0
            header: "Failed · " + page.failed.length
            Repeater { model: page.failed; QueueRow { rec: modelData; error: modelData.error } }
            Cell { title: "Retry Failed"; titleColor: Ios.accent; centered: true; tap: () => { downloads.retryFailed(); Ui.hud("Retrying") } }
        }
    }
}
