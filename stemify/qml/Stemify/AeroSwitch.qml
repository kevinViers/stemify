import QtQuick
import QtQuick.Controls.Basic

Switch {
    id: sw
    hoverEnabled: true
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fs14
    spacing: 12
    indicator: Rectangle {
        implicitWidth: 40
        implicitHeight: 22
        x: sw.leftPadding
        y: parent.height / 2 - height / 2
        radius: 11
        color: sw.checked ? Theme.primary : Qt.rgba(1, 1, 1, 0.10)
        border.color: sw.checked ? Qt.lighter(Theme.primary, 1.3) : Qt.rgba(1, 1, 1, 0.16)
        Behavior on color { ColorAnimation { duration: Theme.med } }
        Glow { radius: 11; sigma: 4; opacity: sw.hovered ? 0.35 : 0 }
        Rectangle {
            x: sw.checked ? parent.width - width - 3 : 3
            anchors.verticalCenter: parent.verticalCenter
            width: 16
            height: 16
            radius: 8
            color: "#FFFFFF"
            Behavior on x { NumberAnimation { duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            Rectangle {
                anchors { left: parent.left; right: parent.right; top: parent.top; margins: 2 }
                height: 6
                radius: 3
                color: Qt.rgba(1, 1, 1, 0.6)
            }
        }
    }
    contentItem: Txt {
        text: sw.text
        font: sw.font
        leftPadding: sw.indicator.width + sw.spacing
        wrapMode: Text.WordWrap
        elide: Text.ElideNone
    }
}
