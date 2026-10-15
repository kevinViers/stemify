import QtQuick

// Invisible edge/corner grip that hands resizing to the compositor.
MouseArea {
    property int edges: 0
    property var win: Window.window
    hoverEnabled: true
    acceptedButtons: Qt.LeftButton
    cursorShape: edges === (Qt.LeftEdge | Qt.TopEdge) || edges === (Qt.RightEdge | Qt.BottomEdge) ? Qt.SizeFDiagCursor
               : edges === (Qt.RightEdge | Qt.TopEdge) || edges === (Qt.LeftEdge | Qt.BottomEdge) ? Qt.SizeBDiagCursor
               : (edges & (Qt.LeftEdge | Qt.RightEdge)) ? Qt.SizeHorCursor : Qt.SizeVerCursor
    onPressed: win.startSystemResize(edges)
}
