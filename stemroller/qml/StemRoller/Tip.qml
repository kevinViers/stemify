import QtQuick
import QtQuick.Controls.Basic

ToolTip {
    id: tip
    delay: 600
    timeout: 4000
    padding: 8
    contentItem: Txt { text: tip.text; font.pixelSize: Theme.fs12 }
    background: Rectangle {
        color: Theme.surface2
        radius: 6
        border.color: Theme.outline
        Elevation { level: 2; radius: 6 }
    }
    enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.fast } }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast } }
}
