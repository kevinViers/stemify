import QtQuick

// A tap target without a ripple. tap() on click; hold() on a long press or a right click, when set.
MouseArea {
    id: m
    property var tap: null
    property var hold: null
    property bool fade: false  // dims the parent to 35% while pressed, as iOS buttons do
    property bool _held: false
    acceptedButtons: hold ? Qt.LeftButton | Qt.RightButton : Qt.LeftButton
    hoverEnabled: true
    cursorShape: enabled && (tap || hold) ? Qt.PointingHandCursor : Qt.ArrowCursor
    onPressed: (e) => {
        _held = false
        if (e.button === Qt.RightButton && hold) { _held = true; hold() }
    }
    onPressAndHold: (e) => { if (e.button === Qt.LeftButton && hold) { _held = true; hold() } }
    onClicked: (e) => { if (e.button === Qt.LeftButton && !_held && tap) tap() }
    states: State {
        when: m.fade && m.pressed && m.pressedButtons & Qt.LeftButton
        PropertyChanges { target: m.parent; opacity: 0.35 }
    }
    transitions: Transition { from: "*"; to: ""; NumberAnimation { property: "opacity"; duration: 220 } }
}
