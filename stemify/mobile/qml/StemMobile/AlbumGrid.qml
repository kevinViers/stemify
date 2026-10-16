import QtQuick

// The phone's two-column album grid (more columns in wider windows), laid out as rows. Small
// grids drop in whole; big ones (Albums) use AlbumGridRow as a list delegate with `rows`.
Column {
    id: g
    property var albums: []
    property bool showArtist: true
    property var subtitle: null  // (album) → string
    property var tap: null
    property var hold: null
    property var badge: null     // (album) → {progress}|{failed}|null
    property bool inline: true
    width: parent ? parent.width : 0
    readonly property int cols: Math.max(2, Math.floor((width - 2 * Ios.margin + 16) / 196))
    readonly property real tile: (width - 2 * Ios.margin - 16 * (cols - 1)) / cols
    readonly property int rows: Math.ceil(albums.length / cols)
    Repeater {
        model: g.inline ? g.rows : 0
        AlbumGridRow { grid: g; row: index }
    }
}
