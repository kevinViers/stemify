import QtQuick

// UISegmentedControl with a sliding selection.
Rectangle {
    id: c
    property var options: []
    property int selected: 0
    property var picked: null
    property var pal: Ios
    height: 32
    radius: 9
    color: pal.fill
    readonly property real segW: (width - 4) / Math.max(1, options.length)
    Rectangle {
        x: 2 + c.segW * c.selected; y: 2
        width: c.segW; height: c.height - 4; radius: 7
        color: Ios.dark ? c.pal.fillStrong : "white"
        border.width: Ios.dark ? 0 : 0.5
        border.color: c.pal.separator
        Behavior on x { SpringAnimation { spring: 5; damping: 0.45 } }
    }
    Row {
        x: 2
        Repeater {
            model: c.options
            Item {
                width: c.segW; height: c.height
                Txt { anchors.centerIn: parent; text: modelData; style: "footnote"; weight: index === c.selected ? 600 : 500; pal: c.pal }
                Pressable { anchors.fill: parent; tap: () => { if (c.picked) c.picked(index) } }
            }
        }
    }
}
