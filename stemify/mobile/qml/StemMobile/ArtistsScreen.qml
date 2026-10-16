import QtQuick

Page {
    id: page
    title: "Artists"
    Fetch { id: artistsFetch; name: "artists"; deps: app.settings.musicSection }
    header: LoadState { fetch: artistsFetch }
    model: artistsFetch.value || []
    delegate: ArtistRow {
        required property var modelData
        artist: modelData
        tap: () => Ui.push("ArtistScreen", { artist: modelData })
        hold: () => Media.artist(modelData)
    }
    overlay: SectionIndex {
        visible: (artistsFetch.value || []).length > 30
        anchors.right: parent.right
        anchors.rightMargin: 2
        anchors.verticalCenter: parent.verticalCenter
        height: parent.height * 0.62
        titles: (artistsFetch.value || []).map(a => a.sortTitle || a.title)
        view: page.view
    }
}
