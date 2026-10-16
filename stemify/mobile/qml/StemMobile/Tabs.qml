import QtQuick

Rectangle {
    id: bar
    property string current: "library"
    property var select: null
    readonly property var tabs: [["library", "Library", "library"], ["playlists", "Playlists", "noteList"], ["search", "Search", "search"],
                                 ["downloads", "Downloads", "download"], ["settings", "Settings", "gear"]]
    height: 50
    color: Ios.bar
    Hairline { width: parent.width; color: Ios.barBorder }
    Row {
        anchors.fill: parent
        Repeater {
            model: bar.tabs
            Item {
                width: bar.width / bar.tabs.length
                height: 50
                readonly property color col: modelData[0] === bar.current ? Ios.accent : Ios.tabInactive
                Column {
                    anchors.horizontalCenter: parent.horizontalCenter
                    y: 5
                    spacing: 2
                    Sym { anchors.horizontalCenter: parent.horizontalCenter; name: modelData[2]; color: parent.parent.col; size: 26 }
                    Txt { anchors.horizontalCenter: parent.horizontalCenter; text: modelData[1]; style: "caption2"; font.pixelSize: 10; weight: 500; color: parent.parent.col; lineHeight: 12 }
                }
                Pressable { anchors.fill: parent; tap: () => bar.select(modelData[0]) }
            }
        }
    }
}
