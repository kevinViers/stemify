pragma Singleton
import QtQuick

// Excel's Office themes: "Black" (dark, the default) and "Colorful". The title bar, ribbon tabs
// and status bar stay Excel green in both; everything below them follows the theme.
QtObject {
    property bool dark: true
    function pick(light, darkValue) { return dark ? darkValue : light }

    readonly property color green: "#217346"
    readonly property color greenDark: "#185C37"
    readonly property color greenHover: "#2A8754"
    readonly property color greenText: pick("#217346", "#6CC48F")
    readonly property color accent: pick("#217346", "#3D9C61")  // selection, focus, active marks
    readonly property color ribbon: pick("#F3F3F3", "#2E2E2E")
    readonly property color ribbonLine: pick("#D5D5D5", "#474747")
    readonly property color hover: pick("#DCDCDC", "#454545")
    readonly property color hoverSoft: pick("#E5E5E5", "#3A3A3A")
    readonly property color pressed: pick("#C6C6C6", "#555555")
    readonly property color checked: pick("#C6C6C6", "#555555")
    readonly property color checkedBorder: pick("#A5A5A5", "#7A7A7A")
    readonly property color ink: pick("#444444", "#E3E3E3")
    readonly property color inkSoft: pick("#666666", "#B5B5B5")
    readonly property color inkFaint: pick("#9A9A9A", "#808080")
    readonly property string iconInk: dark ? "D6D6D6" : ""  // Ico's default ink ("" = the provider's own)
    readonly property color title: pick("#222222", "#F2F2F2")
    readonly property color white: "#FFFFFF"
    readonly property color paper: pick("#FFFFFF", "#1F1F1F")     // sheet, panes, fields
    readonly property color raised: pick("#FFFFFF", "#2B2B2B")    // menus, dialogs, tooltips
    readonly property color paperAlt: pick("#F2F2F2", "#333333")  // boxes inside panes
    readonly property color borderSoft: pick("#D9D9D9", "#404040")
    readonly property color borderStrong: pick("#ABABAB", "#5E5E5E")
    readonly property color grid: pick("#D4D4D4", "#3A3A3A")
    readonly property color header: pick("#F3F3F3", "#2B2B2B")
    readonly property color headerSel: pick("#D2D2D2", "#454545")
    readonly property color headerLine: pick("#BDBDBD", "#4A4A4A")
    readonly property color cornerMark: pick("#B4B4B4", "#6A6A6A")
    readonly property color selFill: dark ? Qt.rgba(1, 1, 1, 0.10) : Qt.rgba(0, 0, 0, 0.09)
    readonly property color selField: pick("#B7D7C4", "#2F5F45")
    readonly property color cellText: pick("#000000", "#E8E8E8")
    readonly property color tableHead: pick("#70AD47", "#4E7F31")
    readonly property color tableBand: pick("#E2EFDA", "#243020")
    readonly property color good: pick("#C6EFCE", "#1E3B26")
    readonly property color goodText: pick("#006100", "#8FD19E")
    readonly property color bad: pick("#FFC7CE", "#4A1F24")
    readonly property color badText: pick("#9C0006", "#F4A6AE")
    readonly property color neutral: pick("#FFEB9C", "#4A3F14")
    readonly property color neutralText: pick("#9C5700", "#F2D27A")
    readonly property color dataBar: "#63BE7B"
    readonly property color note: pick("#FFFFE1", "#3A3826")
    readonly property color menuBorder: pick("#C6C6C6", "#555555")
    readonly property color chartLine: pick("#D9D9D9", "#3A3A3A")
    readonly property color chartText: pick("#595959", "#BDBDBD")
    readonly property color sparkRest: pick("#A9D08E", "#3F5F31")
    readonly property color btn: pick("#FDFDFD", "#333333")
    readonly property color btnHover: pick("#E5F1FB", "#3A4752")
    readonly property color btnPressed: pick("#CCE4F7", "#2B5278")
    readonly property color btnFocus: pick("#0078D7", "#4AA3F0")
    readonly property color btnBorder: pick("#ADADAD", "#5E5E5E")
    readonly property color msg: pick("#FFF4CE", "#4A4220")
    readonly property color msgLine: pick("#E6D9A8", "#6B5E2C")
    readonly property color msgHover: pick("#F2E2A6", "#5C5229")
    readonly property color scrollBg: pick("#F0F0F0", "#262626")
    readonly property color scrollLine: pick("#E0E0E0", "#333333")
    readonly property color scrollThumb: pick("#C2C2C2", "#555555")
    readonly property color scrollHover: pick("#A6A6A6", "#6A6A6A")
    readonly property color scrollPress: pick("#7A7A7A", "#888888")

    // Excel's cell styles, used for the mix presets.
    readonly property var styles: dark ? [
        { name: "Normal",      fill: "#1F1F1F", text: "#E8E8E8", border: "#3A3A3A", bold: false },
        { name: "Bad",         fill: "#4A1F24", text: "#F4A6AE", border: "#4A1F24", bold: false },
        { name: "Good",        fill: "#1E3B26", text: "#8FD19E", border: "#1E3B26", bold: false },
        { name: "Neutral",     fill: "#4A3F14", text: "#F2D27A", border: "#4A3F14", bold: false },
        { name: "Calculation", fill: "#333333", text: "#FA9A3C", border: "#7F7F7F", bold: true },
        { name: "Check Cell",  fill: "#5A5A5A", text: "#FFFFFF", border: "#9A9A9A", bold: true },
        { name: "Note",        fill: "#3A3826", text: "#E8E8E8", border: "#6A6A5A", bold: false }
    ] : [
        { name: "Normal",      fill: "#FFFFFF", text: "#000000", border: "#D4D4D4", bold: false },
        { name: "Bad",         fill: "#FFC7CE", text: "#9C0006", border: "#FFC7CE", bold: false },
        { name: "Good",        fill: "#C6EFCE", text: "#006100", border: "#C6EFCE", bold: false },
        { name: "Neutral",     fill: "#FFEB9C", text: "#9C5700", border: "#FFEB9C", bold: false },
        { name: "Calculation", fill: "#F2F2F2", text: "#FA7D00", border: "#7F7F7F", bold: true },
        { name: "Check Cell",  fill: "#A5A5A5", text: "#FFFFFF", border: "#3F3F3F", bold: true },
        { name: "Note",        fill: "#FFFFCC", text: "#000000", border: "#B2B2B2", bold: false }
    ]

    readonly property int fsSmall: 11
    readonly property int fsUi: 12
    readonly property int fsCell: 15  // Calibri 11 pt
    readonly property int easeMs: 120

    function colName(i) {
        var s = ""
        i += 1
        while (i > 0) {
            var m = (i - 1) % 26
            s = String.fromCharCode(65 + m) + s
            i = Math.floor((i - 1) / 26)
        }
        return s
    }
    function time(sec) {
        if (!(sec > 0)) sec = 0
        var s = Math.floor(sec), m = Math.floor(s / 60)
        return m + ":" + ("0" + (s % 60)).slice(-2)
    }
}
