import QtQuick

// Ready / Calculating on the left; Average, Count, Sum; view buttons; the zoom slider is the volume.
Rectangle {
    id: sb
    property var win: Window.window
    height: 24
    color: X.green

    T {
        x: 10
        height: parent.height
        color: "#FFFFFF"
        text: library.status !== "" ? library.status + (library.progress >= 0 ? ": " + Math.round(library.progress * 100) + "%" : "")
            : player.status === "loading" ? "Calculating..."
            : player.status === "buffering" ? "Buffering..."
            : player.status === "playing" ? "Playing" + (player.presetLabel && player.presetLabel !== "Full Mix" ? " · " + player.presetLabel : "")
            : "Ready"
    }
    Rectangle {  // progress for Calculating, like Excel's
        visible: library.progress >= 0
        x: 190
        anchors.verticalCenter: parent.verticalCenter
        width: 120
        height: 8
        color: "#FFFFFF"
        opacity: 0.9
        Rectangle { width: parent.width * Math.max(0, library.progress); height: parent.height; color: "#9FD2B4" }
    }

    Row {
        anchors.right: parent.right
        anchors.rightMargin: 10
        height: parent.height
        spacing: 18

        Row {
            height: parent.height
            spacing: 18
            visible: !!sb.win.selStats.count
            T { color: "#FFFFFF"; height: parent.height; visible: !!sb.win.selStats.average; text: "Average: " + (sb.win.selStats.average || "") }
            T { color: "#FFFFFF"; height: parent.height; text: "Count: " + (sb.win.selStats.count || "") }
            T { color: "#FFFFFF"; height: parent.height; visible: !!sb.win.selStats.sum; text: "Sum: " + (sb.win.selStats.sum || "") }
        }
        Row {
            height: parent.height
            spacing: 2
            Repeater {
                model: ["normal_view", "page_view", "break_view"]
                delegate: Item {
                    required property int index
                    required property string modelData
                    width: 24
                    height: sb.height
                    Rectangle { anchors.fill: parent; anchors.margins: 2; color: index === 0 ? X.greenDark : "transparent" }
                    Ico { anchors.centerIn: parent; name: parent.modelData; ink: "#FFFFFF"; size: 16 }
                }
            }
        }
        // the zoom slider, wired to the master volume
        Row {
            height: parent.height
            spacing: 6
            T { color: "#FFFFFF"; height: parent.height; text: "−"; font.pixelSize: 16
                MouseArea { anchors.fill: parent; anchors.margins: -4; onClicked: engine.volume = Math.max(0, engine.volume - 0.05) } }
            Item {
                id: track
                width: 110
                height: parent.height
                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 2; color: "#FFFFFF"; opacity: 0.8 }
                Rectangle { x: parent.width / 2 - 1; anchors.verticalCenter: parent.verticalCenter; width: 2; height: 8; color: "#FFFFFF"; opacity: 0.8 }
                Rectangle {
                    x: engine.volume * (track.width - width)
                    anchors.verticalCenter: parent.verticalCenter
                    width: 6
                    height: 13
                    color: vm.pressed ? "#E0E0E0" : "#FFFFFF"
                }
                MouseArea {
                    id: vm
                    anchors.fill: parent
                    anchors.margins: -3
                    hoverEnabled: true
                    function set(m) { engine.volume = Math.max(0, Math.min(1, (m.x - 3) / (track.width))) }
                    onPressed: (m) => set(m)
                    onPositionChanged: (m) => { if (pressed) set(m) }
                    onWheel: (w) => engine.volume = Math.max(0, Math.min(1, engine.volume + (w.angleDelta.y > 0 ? 0.03 : -0.03)))
                }
                Tip { target: vm; text: "Volume" }
            }
            T { color: "#FFFFFF"; height: parent.height; text: "+"; font.pixelSize: 15
                MouseArea { anchors.fill: parent; anchors.margins: -4; onClicked: engine.volume = Math.min(1, engine.volume + 0.05) } }
            T { color: "#FFFFFF"; height: parent.height; width: 40; horizontalAlignment: Text.AlignRight; text: Math.round(engine.volume * 100) + "%" }
        }
    }
}
