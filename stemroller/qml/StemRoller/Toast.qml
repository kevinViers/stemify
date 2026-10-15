import QtQuick

// Material snackbar on surface-2.
Item {
    id: t
    property string message: ""
    property string kind: "info"
    property string folder: ""
    property real maxWidth: 800
    width: Math.min(maxWidth, row.implicitWidth + 32)
    height: 48
    opacity: 0
    visible: opacity > 0
    y: shown ? 0 : 12
    property bool shown: false

    function show(m, k, f) {
        message = m; kind = k; folder = f
        shown = true
        hideTimer.restart()
    }
    Timer { id: hideTimer; interval: 5000; onTriggered: t.shown = false }
    states: State { when: t.shown; PropertyChanges { t.opacity: 1 } }
    Behavior on opacity { NumberAnimation { duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    Behavior on y { NumberAnimation { duration: Theme.slow; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

    Rectangle {
        anchors.fill: parent
        radius: 8
        color: Theme.surface2
        border.color: t.kind === "error" ? Theme.alpha(Theme.error, 0.5) : t.kind === "success" ? Theme.alpha(Theme.success, 0.45) : Theme.outline
        Elevation { level: 3; radius: 8 }
    }
    Row {
        id: row
        anchors { left: parent.left; leftMargin: 16; verticalCenter: parent.verticalCenter }
        spacing: 12
        Icon {
            anchors.verticalCenter: parent.verticalCenter
            name: t.kind === "error" ? "error" : t.kind === "success" ? "check" : "music"
            size: 18
            color: t.kind === "error" ? Theme.error : t.kind === "success" ? Theme.success : Theme.accentGlow
        }
        Txt {
            anchors.verticalCenter: parent.verticalCenter
            text: t.message
            width: Math.min(implicitWidth, 640)
        }
        AeroButton {
            visible: t.folder.length > 0
            anchors.verticalCenter: parent.verticalCenter
            variant: "text"
            pillHeight: 32
            text: "Open folder"
            onClicked: { app.openFolder(t.folder); t.shown = false }
        }
        IconButton {
            anchors.verticalCenter: parent.verticalCenter
            size: 28; iconSize: 14
            iconName: "close"
            glow: false
            onClicked: t.shown = false
        }
    }
}
