import QtQuick

// UITableView's section index: click or drag down the letters to jump.
Item {
    id: idx
    property var titles: []
    property var view: null
    property int offset: 0  // rows before the first title
    readonly property var letters: "ABCDEFGHIJKLMNOPQRSTUVWXYZ#".split("")
    readonly property var first: {
        var m = {}
        for (var i = 0; i < titles.length; i++) {
            var t = titles[i].replace(/^\s+/, "").replace(/^The /, ""), ch = t.charAt(0).toUpperCase()
            if (!(ch >= "A" && ch <= "Z")) ch = "#"
            if (m[ch] === undefined) m[ch] = i
        }
        return m
    }
    width: 22
    property string last: ""
    function jump(y) {
        var l = letters[Math.max(0, Math.min(letters.length - 1, Math.floor(y / height * letters.length)))]
        if (l === last) return
        last = l
        for (var k = letters.indexOf(l); k < letters.length; k++) {
            if (first[letters[k]] !== undefined) { view.positionViewAtIndex(first[letters[k]] + offset, ListView.Beginning); return }
        }
    }
    Column {
        Repeater {
            model: idx.letters
            Txt { width: idx.width; height: idx.height / idx.letters.length; horizontalAlignment: Text.AlignHCenter; text: modelData; style: "caption2"; weight: 600; color: Ios.accent; lineHeight: height }
        }
    }
    MouseArea {
        preventStealing: true  // a scrolling list around it must not take the drag
        anchors.fill: parent
        cursorShape: Qt.PointingHandCursor
        onPressed: (e) => idx.jump(e.y)
        onPositionChanged: (e) => { if (pressed) idx.jump(e.y) }
        onReleased: idx.last = ""
    }
}
