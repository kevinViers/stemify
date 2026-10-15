import QtQuick

// Material card (elevation 2, radius 8) with Aero top gloss: white 6% -> 0 over the top 40%.
Rectangle {
    id: card
    property int elevation: 2
    property bool glossy: true
    radius: 8
    color: Theme.surface1
    border.color: Theme.hairline
    border.width: 1

    Elevation { level: card.elevation; radius: card.radius }

    Rectangle {
        visible: card.glossy
        anchors { left: parent.left; right: parent.right; top: parent.top; margins: 1 }
        height: (parent.height - 2) * 0.4
        topLeftRadius: card.radius - 1
        topRightRadius: card.radius - 1
        gradient: Gradient {
            GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 0.06) }
            GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 0.0) }
        }
    }
}
