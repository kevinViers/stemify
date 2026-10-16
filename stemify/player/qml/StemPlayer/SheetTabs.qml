import QtQuick

// Worksheet tabs. The first six are fixed; opened artists, albums, playlists and searches follow.
Rectangle {
    id: st
    property var win: Window.window
    height: 26
    color: X.ribbon
    Rectangle { width: parent.width; height: 1; color: X.ribbonLine }

    Row {
        id: nav
        x: 6
        height: parent.height
        spacing: 2
        Repeater {
            model: ["left", "right"]
            delegate: Item {
                required property int index
                required property string modelData
                width: 18
                height: parent.height
                Ico { anchors.centerIn: parent; name: parent.modelData; size: 12; opacity: na.containsMouse ? 1 : 0.6 }
                MouseArea {
                    id: na
                    anchors.fill: parent
                    hoverEnabled: true
                    onClicked: library.current = Math.max(0, Math.min(library.tabs.length - 1, library.current + (parent.index ? 1 : -1)))
                }
            }
        }
    }

    Flickable {
        id: flick
        x: nav.x + nav.width + 8
        width: parent.width - x - 8
        height: parent.height
        contentWidth: tabRow.width + 40
        clip: true
        flickableDirection: Flickable.HorizontalFlick
        boundsBehavior: Flickable.StopAtBounds
        Row {
            id: tabRow
            height: parent.height
            Repeater {
                model: library.tabs
                delegate: Item {
                    id: tb
                    required property int index
                    required property var modelData
                    readonly property bool active: library.current === index
                    width: lbl.implicitWidth + 28
                    height: st.height
                    Rectangle {
                        anchors.fill: parent
                        anchors.topMargin: 0
                        color: tb.active ? X.paper : tm.containsMouse ? X.hoverSoft : "transparent"
                    }
                    Rectangle { visible: !tb.active; anchors.right: parent.right; y: 6; width: 1; height: parent.height - 12; color: X.borderSoft }
                    Rectangle { visible: tb.active; anchors.bottom: parent.bottom; width: parent.width; height: 3; color: X.accent }
                    T {
                        id: lbl
                        anchors.centerIn: parent
                        text: tb.modelData.title
                        color: tb.active ? X.greenText : X.ink
                        font.bold: tb.active
                    }
                    MouseArea {
                        id: tm
                        anchors.fill: parent
                        hoverEnabled: true
                        acceptedButtons: Qt.LeftButton | Qt.MiddleButton
                        onClicked: (m) => m.button === Qt.MiddleButton ? library.closeTab(tb.index) : (library.current = tb.index)
                    }
                    Tip { target: tm; text: tb.modelData.base ? "" : "Middle-click or Backspace to close" }
                }
            }
            // ⊕ new sheet: here, the search box
            Item {
                width: 30
                height: st.height
                Ico { anchors.centerIn: parent; name: "plus"; size: 16; opacity: pm.containsMouse ? 1 : 0.6 }
                MouseArea { id: pm; anchors.fill: parent; hoverEnabled: true; onClicked: st.win.focusSearch() }
                Tip { target: pm; text: "New sheet from a search" }
            }
        }
    }
}
