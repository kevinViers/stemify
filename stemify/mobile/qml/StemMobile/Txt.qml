import QtQuick

// Text with an iOS style: label colour, one line, tail ellipsis.
Text {
    property string style: "body"
    property int weight: -1
    property bool tnum: false
    property var pal: Ios
    readonly property var spec: Ios.type[style] || Ios.type.body
    font.family: Ios.font
    font.pixelSize: spec.size
    font.weight: weight >= 0 ? weight : spec.weight
    font.letterSpacing: Ios.tracking(spec.size)
    font.features: tnum ? { "tnum": 1 } : {}
    lineHeight: spec.line
    lineHeightMode: Text.FixedHeight
    color: pal.label
    elide: Text.ElideRight
    maximumLineCount: 1
    wrapMode: maximumLineCount > 1 ? Text.Wrap : Text.NoWrap
    verticalAlignment: Text.AlignVCenter
    textFormat: Text.PlainText
}
