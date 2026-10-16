import QtQuick
import QtQuick.Controls.Basic

// Office-style tooltip: white box, gray border, after a short hover.
ToolTip {
    id: tt
    property var target
    visible: target && target.containsMouse && text !== "" && hoverTimer.done
    delay: 0
    timeout: 6000
    padding: 6
    Timer {
        id: hoverTimer
        property bool done: false
        interval: 600
        running: tt.target && tt.target.containsMouse
        onRunningChanged: if (!running) done = false
        onTriggered: done = true
    }
    contentItem: T { text: tt.text; font.pixelSize: X.fsSmall; elide: Text.ElideNone }
    background: Rectangle { color: X.raised; border.color: X.borderStrong }
}
