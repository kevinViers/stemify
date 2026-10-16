import QtQuick
import QtQuick.Controls.Basic

// A navigation-controller page: a list with a large title that collapses into the bar as you
// scroll, a back button titled after the previous page, and the bar's background only once
// content is under it. `header` holds everything above the list's rows (all of it, on
// settings-style pages); `model` + `delegate` are the rows.
Item {
    id: page
    property string title
    property bool grouped: false
    property bool largeTitle: true
    property real titleAfter: 0  // without a large title: scroll this far before the bar shows it
    property Component header: null
    property Component footer: null
    property alias model: view.model
    property alias delegate: view.delegate
    property alias view: view
    property alias trailing: trail.data
    property alias overlay: over.data
    readonly property color pageBg: grouped ? Ios.groupedBg : Ios.bg

    readonly property var stack: StackView.view
    readonly property int stackIndex: StackView.index
    readonly property string backTitle: stack && stackIndex > 0 && stack.get(stackIndex - 1) ? stack.get(stackIndex - 1).title || "Back" : ""
    readonly property real scrolled: view.contentY - view.originY + view.topMargin
    readonly property bool collapsed: !largeTitle || scrolled > 40
    readonly property bool showTitle: largeTitle ? collapsed : scrolled > titleAfter
    readonly property bool showBar: largeTitle ? collapsed : scrolled > 0

    Rectangle { anchors.fill: parent; color: page.pageBg }

    ListView {
        id: view
        anchors.fill: parent
        topMargin: 44
        bottomMargin: (Ui.shell ? Ui.shell.bottomInset : 0) + 28
        boundsBehavior: Flickable.StopAtBounds
        flickDeceleration: 4000
        maximumFlickVelocity: 7000
        reuseItems: true
        cacheBuffer: 600
        header: Column {
            width: view.width
            // A header that grows once its data loads would otherwise leave the list scrolled.
            onHeightChanged: if (page.scrolled <= 1) Qt.callLater(() => view.contentY = view.originY - view.topMargin)
            Item {
                visible: page.largeTitle
                width: parent.width
                height: page.largeTitle ? 60 : 0
                Txt {
                    x: Ios.margin; width: parent.width - 2 * Ios.margin
                    anchors.bottom: parent.bottom; anchors.bottomMargin: 8
                    text: page.title; style: "largeTitle"
                }
            }
            Loader { width: parent.width; sourceComponent: page.header; active: page.header !== null }
        }
        footer: Loader { width: view.width; sourceComponent: page.footer; active: page.footer !== null }
        ScrollBar.vertical: ScrollBar {
            policy: ScrollBar.AsNeeded
            width: 8
            topPadding: 44
            bottomPadding: view.bottomMargin
            contentItem: Rectangle { implicitWidth: 3; radius: 1.5; color: Ios.label3; opacity: parent.active ? 0.9 : 0
                Behavior on opacity { NumberAnimation { duration: 200 } } }
        }
    }

    // ---- nav bar ----
    Item {
        id: bar
        width: parent.width
        height: 44
        Rectangle { anchors.fill: parent; color: page.pageBg }
        Rectangle { anchors.fill: parent; color: Ios.bar; opacity: page.showBar ? 1 : 0; Behavior on opacity { NumberAnimation { duration: 160 } } }
        Hairline { anchors.bottom: parent.bottom; width: parent.width; color: Ios.barBorder; opacity: page.showBar ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 160 } } }
        Txt {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(implicitWidth, parent.width - 2 * Math.max(back.width, trail.width) - 16)
            horizontalAlignment: Text.AlignHCenter
            text: page.title; style: "headline"
            opacity: page.showTitle ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 160 } }
        }
        Item {
            id: back
            visible: page.backTitle !== ""
            x: 6
            width: backRow.width + 8
            height: 44
            Row {
                id: backRow
                height: 44
                Sym { name: "chevronLeft"; color: Ios.accent; size: 24; anchors.verticalCenter: parent.verticalCenter }
                Txt { text: page.backTitle.length > 14 ? "Back" : page.backTitle; color: Ios.accent; anchors.verticalCenter: parent.verticalCenter }
            }
            Pressable { anchors.fill: parent; fade: true; tap: () => Ui.pop() }
        }
        Row {
            id: trail
            anchors.right: parent.right
            anchors.rightMargin: 6
            height: 44
            spacing: 0
        }
    }
    Item { id: over; anchors.fill: parent }
}
