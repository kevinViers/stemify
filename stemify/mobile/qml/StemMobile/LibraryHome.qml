import QtQuick
import QtQuick.Layouts

Page {
    id: page
    title: "Library"
    readonly property bool ready: app.settings.signedIn
    Fetch { id: albumsFetch; name: "albums"; args: ["titleSort"]; deps: app.settings.musicSection; active: page.ready }

    // Albums released on today's date in earlier years, and, for slow days, the three days either side.
    readonly property var days: {
        var today = new Date(), y = today.getFullYear()
        today = new Date(y, today.getMonth(), today.getDate())
        var all = [], list = albumsFetch.value || []
        for (var i = 0; i < list.length; i++) {
            var p = (list[i].released || "").split("-")
            if (p.length < 3 || +p[0] >= y) continue
            var m = +p[1] - 1, d = +p[2]
            if (m === 1 && d === 29 && new Date(y, 1, 29).getMonth() !== 1) d = 28  // Feb 29 falls back to Feb 28
            all.push({ album: list[i], date: new Date(y, m, d), years: y - +p[0] })
        }
        var onDay = all.filter(b => b.date.getTime() === today.getTime()).sort((a, b) => b.years - a.years)
        var week = all.filter(b => b.date.getTime() !== today.getTime() && Math.abs(b.date - today) <= 3 * 86400000 + 3600000)
                      .sort((a, b) => a.date - b.date || b.years - a.years)
        return { today: today, onDay: onDay, week: week }
    }
    function find(list, a) { for (var i = 0; i < list.length; i++) if (list[i].album.key === a.key) return list[i]; return null }

    header: Column {
        width: page.width
        EmptyState {
            visible: !page.ready
            icon: "server"; title: "Connect to Plex"
            message: "Sign in to your Plex server to browse your music. Stems come from stemserve on the same box."
            action: "Connect"; tap: () => Ui.push("PlexAccountScreen")
        }
        Repeater {
            model: [["noteList", "Playlists", "PlaylistsScreen"], ["person", "Artists", "ArtistsScreen"],
                    ["album", "Albums", "AlbumsScreen"], ["note", "Songs", "SongsScreen"]]
            Item {
                width: page.width; height: 52
                Rectangle { anchors.fill: parent; color: area.pressed ? Ios.cellPressed : "transparent" }
                Pressable { id: area; anchors.fill: parent; tap: () => Ui.push(modelData[2]) }
                RowLayout {
                    anchors.fill: parent; anchors.leftMargin: Ios.margin; anchors.rightMargin: 16; spacing: 14
                    Sym { name: modelData[0]; color: Ios.accent; size: 26 }
                    Txt { text: modelData[1]; style: "title3"; weight: 400; Layout.fillWidth: true }
                    Chevron {}
                }
                Hairline { visible: index < 3; x: 60; width: parent.width - 60; anchors.bottom: parent.bottom }
            }
        }
        Column {
            visible: page.ready
            width: page.width
            SectionTitle { text: "On This Day" }
            Txt { x: Ios.margin; bottomPadding: 10; text: page.days.today.toLocaleDateString(Qt.locale(), "MMMM d"); style: "subhead"; color: Ios.label2 }
            LoadState { fetch: albumsFetch }
            AlbumGrid {
                visible: albumsFetch.value !== null
                albums: page.days.onDay.map(b => b.album)
                tap: (a) => Ui.push("AlbumScreen", { album: a })
                hold: (a) => Media.album(a)
                subtitle: (a) => { var b = page.find(page.days.onDay, a); return a.artist + " · " + (b.years === 1 ? "1 year ago" : b.years + " years ago") }
            }
            Txt {
                visible: albumsFetch.value !== null && page.days.onDay.length === 0
                x: Ios.margin; width: page.width - 2 * Ios.margin; bottomPadding: 12
                text: page.days.week.length ? "No album birthdays today. Coming up and just past:" : "No albums in your library came out this week in any year."
                style: "subhead"; color: Ios.label2; maximumLineCount: 3
            }
            AlbumGrid {
                visible: albumsFetch.value !== null && page.days.onDay.length === 0
                albums: page.days.onDay.length ? [] : page.days.week.map(b => b.album)
                tap: (a) => Ui.push("AlbumScreen", { album: a })
                hold: (a) => Media.album(a)
                subtitle: (a) => {
                    var b = page.find(page.days.week, a)
                    return (b.date < page.days.today ? "Turned " : "Turns ") + b.years + " · " + b.date.toLocaleDateString(Qt.locale(), "MMM d")
                }
            }
        }
    }
}
