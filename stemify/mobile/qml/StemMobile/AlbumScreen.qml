import QtQuick

Page {
    id: page
    property var album: ({})
    Fetch { id: tracksFetch; name: "albumTracks"; args: [page.album.key] }
    Fetch { id: detailFetch; name: "album"; args: [page.album.key] }
    readonly property var a: detailFetch.value && detailFetch.value.key ? detailFetch.value : album
    readonly property var list: tracksFetch.value || []
    readonly property bool allDownloaded: list.length > 0 && list.every(t => downloads.states[t.key] && downloads.states[t.key].done)
    readonly property bool discs: { var s = {}; list.forEach(t => s[t.disc || 0] = 1); return Object.keys(s).length > 1 }
    title: a.title || ""
    largeTitle: false
    titleAfter: 330
    trailing: [
        IconButton {
            visible: page.list.length > 0
            icon: page.allDownloaded ? "check" : "download"
            tap: () => page.allDownloaded
                ? Ui.confirm("Remove Download?", "“" + page.a.title + "” will no longer be available offline.", "Remove", () => downloads.remove(page.list))
                : Media.download(page.list)
        },
        IconButton { visible: page.list.length > 0; icon: "ellipsis"; tap: () => Media.album(page.a) }
    ]
    header: Column {
        width: page.width
        topPadding: 8
        spacing: 0
        Artwork { anchors.horizontalCenter: parent.horizontalCenter; thumb: page.a.thumb || ""; size: Math.min(264, page.width - 80); corner: 10; placeholder: "album" }
        Item { width: 1; height: 16 }
        Txt { x: 32; width: parent.width - 64; horizontalAlignment: Text.AlignHCenter; text: page.a.title || ""; style: "title3"; maximumLineCount: 2 }
        Txt {
            x: 32; width: parent.width - 64; horizontalAlignment: Text.AlignHCenter
            text: page.a.artist || ""; style: "title3"; weight: 400; color: Ios.accent
            Pressable { anchors.fill: parent; tap: () => page.a.artistKey && Ui.push("ArtistScreen", { artist: { key: page.a.artistKey, title: page.a.artist } }) }
        }
        Txt {
            readonly property string meta: [(page.a.genre || "").toUpperCase(), page.a.year ? String(page.a.year) : ""].filter(s => s).join(" · ")
            visible: meta !== ""
            x: 32; width: parent.width - 64; topPadding: 4; horizontalAlignment: Text.AlignHCenter
            text: meta; style: "caption1"; weight: 600; color: Ios.label2
        }
        Item { width: 1; height: 16 }
        PlayShuffle { play: () => player.playTracks(page.list, 0, false); shuffle: () => player.playTracks(page.list, -1, true) }
        Item { width: 1; height: 16 }
        Hairline { x: Ios.margin; width: parent.width - Ios.margin }
        LoadState { fetch: tracksFetch }
    }
    model: list
    delegate: Column {
        required property var modelData
        required property int index
        width: page.width
        Txt {
            visible: page.discs && (index === 0 || page.list[index - 1].disc !== modelData.disc)
            x: Ios.margin; topPadding: 18; bottomPadding: 6
            text: "Disc " + modelData.disc; style: "headline"; color: Ios.label2
        }
        TrackRow {
            track: modelData
            showArt: false
            number: modelData.index || index + 1
            subtitle: modelData.artist !== page.a.artist ? modelData.artist : ""
            tap: () => player.playTracks(page.list, index, false)
            more: () => Media.show(modelData, [], false)
        }
    }
    footer: Txt {
        visible: page.list.length > 0
        x: Ios.margin; topPadding: 14
        text: Api.songs(page.list.length) + ", " + Api.minutes(Api.sum(page.list, "durationMs"))
        style: "subhead"; color: Ios.label2
    }
}
