import QtQuick
import QtQuick.Window

Rectangle {
    property var pal: Ios
    height: 1 / Screen.devicePixelRatio
    color: pal.separator
}
