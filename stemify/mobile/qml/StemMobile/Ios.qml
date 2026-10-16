pragma Singleton
import QtQuick

// iOS semantic colours flattened to solid values for each scheme, and the iOS text styles
// (ui/theme/Theme.kt). Components read colours from a `pal` that defaults to this object, so
// Now Playing can hand them its album-coloured palette instead.
QtObject {
    id: ios
    readonly property string mode: app.settings.theme || "black"
    readonly property string accentName: app.settings.accent || "music"
    readonly property bool dark: mode !== "white"

    readonly property var accents: ({
        music: ["#FA2D48", "#FC3C44"], pink: ["#FF2D55", "#FF375F"], orange: ["#FF9500", "#FF9F0A"],
        yellow: ["#FFCC00", "#FFD60A"], green: ["#34C759", "#30D158"], teal: ["#30B0C7", "#40C8E0"],
        blue: ["#007AFF", "#0A84FF"], indigo: ["#5856D6", "#5E5CE6"], purple: ["#AF52DE", "#BF5AF2"]
    })
    readonly property var accentOrder: ["music", "pink", "orange", "yellow", "green", "teal", "blue", "indigo", "purple"]
    readonly property var modes: [["black", "Black"], ["dark", "Dark"], ["white", "White"]]

    readonly property var palettes: ({
        black: { bg: "#000000", groupedBg: "#000000", cell: "#1C1C1E", cellPressed: "#2C2C2E", elevated: "#1C1C1E",
                 fill: "#1C1C1E", fillStrong: "#3A3A3C", label: "#FFFFFF", label2: "#8D8D93", label3: "#48484A",
                 separator: "#2C2C2E", bar: "#000000", barBorder: "#262628", red: "#FF453A", green: "#30D158",
                 switchOff: "#39393D", scrim: "#99000000", placeholder: "#1C1C1E", thumbBorder: "#00000000", tabInactive: "#757575" },
        dark: { bg: "#1C1C1E", groupedBg: "#1C1C1E", cell: "#2C2C2E", cellPressed: "#3A3A3C", elevated: "#2C2C2E",
                fill: "#2C2C2E", fillStrong: "#48484A", label: "#FFFFFF", label2: "#98989D", label3: "#5A5A5E",
                separator: "#3D3D40", bar: "#1C1C1E", barBorder: "#38383A", red: "#FF453A", green: "#30D158",
                switchOff: "#48484A", scrim: "#99000000", placeholder: "#3A3A3C", thumbBorder: "#00000000", tabInactive: "#808084" },
        white: { bg: "#FFFFFF", groupedBg: "#F2F2F7", cell: "#FFFFFF", cellPressed: "#D1D1D6", elevated: "#FFFFFF",
                 fill: "#EFEFF0", fillStrong: "#E3E3E8", label: "#000000", label2: "#8A8A8E", label3: "#C4C4C7",
                 separator: "#D8D8DC", bar: "#F9F9F9", barBorder: "#D1D1D6", red: "#FF3B30", green: "#34C759",
                 switchOff: "#E9E9EA", scrim: "#55000000", placeholder: "#E5E5EA", thumbBorder: "#D1D1D6", tabInactive: "#999999" }
    })
    readonly property var p: palettes[mode] || palettes.black

    function accentFor(name, isDark) { var a = accents[name] || accents.music; return isDark ? a[1] : a[0] }

    readonly property color accent: accentFor(accentName, dark)
    readonly property color bg: p.bg
    readonly property color groupedBg: p.groupedBg
    readonly property color cell: p.cell
    readonly property color cellPressed: p.cellPressed
    readonly property color elevated: p.elevated
    readonly property color fill: p.fill
    readonly property color fillStrong: p.fillStrong
    readonly property color label: p.label
    readonly property color label2: p.label2
    readonly property color label3: p.label3
    readonly property color separator: p.separator
    readonly property color bar: p.bar
    readonly property color barBorder: p.barBorder
    readonly property color red: p.red
    readonly property color green: p.green
    readonly property color switchOff: p.switchOff
    readonly property color scrim: p.scrim
    readonly property color placeholder: p.placeholder
    readonly property color thumbBorder: p.thumbBorder
    readonly property color tabInactive: p.tabInactive

    // Inter stands in for SF Pro, as on the phone.
    readonly property string font: "Inter"
    readonly property var type: ({
        largeTitle: { size: 34, line: 41, weight: 700 },
        title2: { size: 22, line: 28, weight: 700 },
        title3: { size: 20, line: 25, weight: 600 },
        headline: { size: 17, line: 22, weight: 600 },
        body: { size: 17, line: 22, weight: 400 },
        subhead: { size: 15, line: 20, weight: 400 },
        footnote: { size: 13, line: 18, weight: 400 },
        caption1: { size: 12, line: 16, weight: 400 },
        caption2: { size: 11, line: 13, weight: 400 }
    })
    // Inter's recommended tracking for a size, like SF's size-specific tracking.
    function tracking(size) { return size * (-0.0223 + 0.185 * Math.exp(-0.1745 * size)) }

    readonly property int margin: 20
    readonly property color yellow: "#FFCC00"
    readonly property color rightChannel: "#FF9500"

    function hex(c) {
        function h(v) { return ("0" + Math.round(v * 255).toString(16)).slice(-2) }
        return h(c.r) + h(c.g) + h(c.b)
    }
    function alpha(c, a) { return Qt.rgba(c.r, c.g, c.b, c.a * a) }

    // Stem colours, matching the separator, the player and the phone.
    readonly property var stemColors: ({ vocals: "#FF7AB6", drums: "#FFB547", bass: "#7C8CFF", other: "#4CD07D",
                                        guitar: "#FF8A5C", piano: "#B48CFF", instrumental: "#3DD6C6", mix: "#8E8E93" })
}
