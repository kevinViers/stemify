import QtQuick

// One row of an AlbumGrid (an Item, since a ListView places its delegates at x = 0).
Item {
    id: r
    property var grid
    property int row: 0
    width: r.grid ? r.grid.width : 0
    height: tiles.height
    Row {
        id: tiles
        x: Ios.margin
        spacing: 16
        Repeater {
            model: r.grid ? r.grid.albums.slice(r.row * r.grid.cols, r.row * r.grid.cols + r.grid.cols) : []
            AlbumTile {
                album: modelData
                tile: r.grid.tile
                subtitle: r.grid.subtitle ? r.grid.subtitle(modelData) || "" : r.grid.showArtist ? modelData.artist || "" : modelData.year ? String(modelData.year) : ""
                tap: r.grid.tap
                hold: r.grid.hold
                badge: r.grid.badge ? r.grid.badge(modelData) : null
            }
        }
    }
}
