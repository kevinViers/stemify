import QtQuick

// Settings.app-style coloured rounded-square icon.
Rectangle {
    property string icon
    width: 30
    height: 30
    radius: 7
    Sym { anchors.centerIn: parent; name: parent.icon; color: "white"; size: 19 }
}
