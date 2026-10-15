pragma Singleton
import QtQuick

// Excel 2016, "Colorful" Office theme.
QtObject {
    readonly property color green: "#217346"
    readonly property color greenDark: "#185C37"
    readonly property color greenHover: "#2A8754"
    readonly property color greenText: "#217346"
    readonly property color ribbon: "#F3F3F3"
    readonly property color ribbonLine: "#D5D5D5"
    readonly property color hover: "#DCDCDC"
    readonly property color pressed: "#C6C6C6"
    readonly property color checked: "#C6C6C6"
    readonly property color checkedBorder: "#A5A5A5"
    readonly property color ink: "#444444"
    readonly property color inkSoft: "#666666"
    readonly property color inkFaint: "#9A9A9A"
    readonly property color white: "#FFFFFF"
    readonly property color grid: "#D4D4D4"
    readonly property color header: "#F3F3F3"
    readonly property color headerSel: "#D2D2D2"
    readonly property color headerLine: "#BDBDBD"
    readonly property color selFill: Qt.rgba(0, 0, 0, 0.09)
    readonly property color cellText: "#000000"
    readonly property color tableHead: "#70AD47"
    readonly property color tableBand: "#E2EFDA"
    readonly property color good: "#C6EFCE"
    readonly property color goodText: "#006100"
    readonly property color bad: "#FFC7CE"
    readonly property color badText: "#9C0006"
    readonly property color neutral: "#FFEB9C"
    readonly property color neutralText: "#9C5700"
    readonly property color dataBar: "#63BE7B"
    readonly property color note: "#FFFFE1"
    readonly property color menuBorder: "#C6C6C6"
    readonly property color chartLine: "#D9D9D9"
    readonly property color chartText: "#595959"

    // Excel's cell styles, used for the mix presets.
    readonly property var styles: [
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
