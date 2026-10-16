import QtQuick

Page {
    id: page
    title: "Albums"
    readonly property var sorts: [["Title", "titleSort"], ["Artist", "artist.titleSort,year"], ["Recently Added", "addedAt:desc"], ["Year", "year:desc"]]
    property int sort: 0
    Fetch { id: albumsFetch; name: "albums"; args: [page.sorts[page.sort][1]]; deps: app.settings.musicSection }
    trailing: TextButton {
        text: "Sort"
        tap: () => Ui.actions("Sort By", page.sorts.map((s, i) => ({ title: (i === page.sort ? "✓ " : "") + s[0], run: () => page.sort = i })))
    }
    AlbumGrid {
        id: albumGrid
        inline: false
        width: page.width
        albums: albumsFetch.value || []
        tap: (a) => Ui.push("AlbumScreen", { album: a })
        hold: (a) => Media.album(a)
    }
    header: Column { width: page.width; Item { width: 1; height: 8 } LoadState { fetch: albumsFetch } }
    model: albumGrid.rows
    delegate: AlbumGridRow { grid: albumGrid; row: index }
}
