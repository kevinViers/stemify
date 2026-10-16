import QtQuick

Item {
    id: t
    property var album: ({})
    property real tile: 160
    property string subtitle: ""
    property var tap: null
    property var hold: null
    property var badge: null  // {progress} or {failed}
    width: tile
    height: tile + 6 + 20 + (subtitle ? 20 : 0) + 18
    Artwork { thumb: t.album.thumb || ""; size: t.tile; corner: 8; placeholder: "album" }
    Rectangle {
        visible: !!t.badge
        x: t.tile - 38; y: t.tile - 38
        width: 30; height: 30; radius: 15; color: "#8C000000"
        ProgressRing { visible: !!t.badge && !t.badge.failed; anchors.centerIn: parent; size: 20; color: "white"; progress: t.badge ? t.badge.progress || 0 : 0 }
        Sym { visible: !!t.badge && !!t.badge.failed; anchors.centerIn: parent; name: "warning"; color: "white"; size: 18 }
    }
    Row {
        y: t.tile + 6
        width: t.tile
        spacing: 6
        Txt { id: name; width: Math.min(implicitWidth, t.tile - (flag.visible ? flag.width + 6 : 0)); text: t.album.title || ""; style: "subhead"; weight: 500 }
        StemFlag { id: flag; cov: app.albumCoverage[t.album.key]; anchors.verticalCenter: name.verticalCenter }
    }
    Txt { visible: t.subtitle !== ""; y: t.tile + 26; width: t.tile; text: t.subtitle; style: "subhead"; color: Ios.label2 }
    Pressable { width: t.tile; height: t.height - 18; tap: () => t.tap && t.tap(t.album); hold: t.hold ? () => t.hold(t.album) : null }
}
