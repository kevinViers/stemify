import QtQuick

// One inset-grouped section (UITableView): a header, the rows as one rounded card, a footer.
Column {
    id: sec
    property string header: ""
    property string footer: ""
    property real inset: 16
    default property alias rows: card.data
    width: parent ? parent.width : 0

    function relayout() {
        var shown = []
        for (var i = 0; i < card.children.length; i++) {
            var ch = card.children[i]
            if (ch.visible && ch.first !== undefined)
                shown.push(ch)
        }
        for (var j = 0; j < shown.length; j++) {
            shown[j].first = j === 0
            shown[j].last = j === shown.length - 1
            shown[j].inset = sec.inset
        }
    }

    Item {
        width: parent.width
        height: sec.header ? 48 : 18
        Txt {
            visible: sec.header !== ""
            x: 36; width: parent.width - 72
            anchors.bottom: parent.bottom; anchors.bottomMargin: 6
            text: sec.header.toUpperCase(); style: "footnote"; color: Ios.label2
        }
    }
    Rectangle {
        x: 16
        width: parent.width - 32
        height: card.height
        radius: 12
        color: Ios.cell
        Column {
            id: card
            width: parent.width
            onChildrenChanged: sec.relayout()
            onHeightChanged: sec.relayout()
        }
    }
    Txt {
        visible: sec.footer !== ""
        x: 36; width: parent.width - 72
        topPadding: 6
        text: sec.footer; style: "footnote"; color: Ios.label2
        maximumLineCount: 6
    }
    Component.onCompleted: relayout()
}
