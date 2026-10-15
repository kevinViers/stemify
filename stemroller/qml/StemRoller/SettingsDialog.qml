import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs

// Glass dialog (elevation 3, stronger 24px blur, 50% scrim).
Popup {
    id: dlg
    property var win: null
    modal: true
    focus: true
    width: Math.min(parent.width - 64, 640)
    height: Math.min(parent.height - 64, content.implicitHeight + 120)
    x: Math.round((parent.width - width) / 2)
    y: Math.round((parent.height - height) / 2)
    padding: 0
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
    onOpened: app.refreshCacheSize()

    Overlay.modal: Rectangle {
        color: Qt.rgba(0, 0, 0, 0.5)
        Behavior on opacity { NumberAnimation { duration: Theme.med } }
    }
    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Theme.med; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
        NumberAnimation { property: "scale"; from: 0.95; to: 1; duration: Theme.slow; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
    }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: Theme.fast } }

    background: Item {
        Elevation { level: 3; radius: 12 }
        Image {  // stronger-blur glass sheet, pre-rounded, rendered once per open/resize
            anchors.fill: parent
            asynchronous: true
            retainWhileLoading: true
            source: dlg.win && dlg.width > 0
                    ? "image://fx/glasscrop/" + dlg.win.fxW + "x" + dlg.win.fxH + "/0/24/"
                      + Math.round(dlg.x) + "," + Math.round(dlg.y) + "," + Math.round(dlg.width) + "," + Math.round(dlg.height) + "/12"
                    : ""
        }
        Rectangle {  // Aero gloss
            anchors { left: parent.left; right: parent.right; top: parent.top; margins: 1 }
            height: 48
            topLeftRadius: 11
            topRightRadius: 11
            gradient: Gradient {
                GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 0.08) }
                GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 0.0) }
            }
        }
        Rectangle {
            anchors.fill: parent
            radius: 12
            color: "transparent"
            border.color: Qt.rgba(1, 1, 1, 0.12)
        }
    }

    FolderDialog {
        id: folderDialog
        title: "Choose the stems folder"
        currentFolder: "file://" + appSettings.stemsDir
        onAccepted: appSettings.stemsDir = decodeURIComponent(selectedFolder.toString().replace("file://", ""))
    }

    component SectionTitle: Txt {
        font.pixelSize: 11
        font.weight: Font.DemiBold
        font.letterSpacing: 0.8
        color: Theme.accentGlow
        topPadding: 8
    }
    component Hint: Txt {
        font.pixelSize: Theme.fs12
        color: Theme.textLo
        wrapMode: Text.WordWrap
        elide: Text.ElideNone
    }
    component Segmented: Row {
        id: seg
        property var options: []
        property string value: ""
        signal picked(string v)
        spacing: 0
        Repeater {
            model: seg.options
            AbstractButton {
                id: sb
                required property var modelData
                required property int index
                readonly property bool on: seg.value === modelData.v
                implicitWidth: 84
                implicitHeight: 34
                hoverEnabled: true
                onClicked: seg.picked(modelData.v)
                background: Rectangle {
                    topLeftRadius: sb.index === 0 ? 17 : 0
                    bottomLeftRadius: sb.index === 0 ? 17 : 0
                    topRightRadius: sb.index === seg.options.length - 1 ? 17 : 0
                    bottomRightRadius: sb.index === seg.options.length - 1 ? 17 : 0
                    color: sb.on ? Theme.alpha(Theme.primary, 0.28) : (sb.hovered ? Qt.rgba(1, 1, 1, 0.07) : Qt.rgba(1, 1, 1, 0.03))
                    border.color: sb.on ? Theme.alpha(Theme.primary, 0.7) : Qt.rgba(1, 1, 1, 0.12)
                    Behavior on color { ColorAnimation { duration: Theme.fast } }
                }
                contentItem: Txt {
                    text: sb.modelData.t
                    horizontalAlignment: Text.AlignHCenter
                    font.weight: sb.on ? Font.DemiBold : Font.Normal
                    color: sb.on ? Theme.textHi : Theme.textLo
                }
            }
        }
    }

    contentItem: Item {
        Item {
            id: head
            anchors { left: parent.left; right: parent.right; top: parent.top }
            height: 60
            Txt {
                anchors { left: parent.left; leftMargin: 24; verticalCenter: parent.verticalCenter }
                text: "Settings"
                font.pixelSize: Theme.fs20
                font.weight: Font.DemiBold
            }
            IconButton {
                anchors { right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
                iconName: "close"
                onClicked: dlg.close()
            }
            Rectangle { anchors { left: parent.left; right: parent.right; bottom: parent.bottom } height: 1; color: Theme.hairline }
        }

        Flickable {
            anchors { left: parent.left; right: parent.right; top: head.bottom; bottom: parent.bottom }
            contentHeight: content.implicitHeight + 32
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { contentItem: Rectangle { implicitWidth: 4; radius: 2; color: Qt.rgba(1, 1, 1, 0.18); visible: parent.size < 1 }
                background: Item {} }

            Column {
                id: content
                x: 24
                y: 12
                width: parent.width - 48
                spacing: 12

                SectionTitle { text: "OUTPUT" }
                Segmented {
                    options: [{ v: "beside", t: "Beside" }, { v: "folder", t: "Folder" }]
                    value: appSettings.outputMode
                    onPicked: (v) => appSettings.outputMode = v
                }
                Hint {
                    width: parent.width
                    text: appSettings.outputMode === "beside"
                          ? "Stems go next to the original, in “<name> - stems”."
                          : "Stems go to a folder per track inside the stems folder."
                }
                Row {
                    visible: appSettings.outputMode === "folder"
                    width: parent.width
                    spacing: 8
                    Rectangle {
                        width: parent.width - browseBtn.width - 8
                        height: 36
                        radius: 18
                        color: Qt.rgba(1, 1, 1, 0.05)
                        border.color: Qt.rgba(1, 1, 1, 0.12)
                        Txt {
                            anchors { fill: parent; leftMargin: 16; rightMargin: 16 }
                            text: appSettings.stemsDir
                            elide: Text.ElideMiddle
                        }
                    }
                    AeroButton { id: browseBtn; variant: "tonal"; pillHeight: 36; iconName: "folder"; text: "Browse"; onClicked: folderDialog.open() }
                }
                Row {
                    spacing: 16
                    Txt { text: "Format"; width: 80; height: 34 }
                    Segmented {
                        options: [{ v: "wav", t: "WAV" }, { v: "flac", t: "FLAC" }, { v: "mp3", t: "MP3" }]
                        value: appSettings.exportFormat
                        onPicked: (v) => appSettings.exportFormat = v
                    }
                }
                Hint { width: parent.width; text: "WAV and FLAC are 24-bit (WAV switches to 32-bit float if a stem would clip). MP3 is 320 kbps." }
                AeroSwitch {
                    width: parent.width
                    text: "Export all stems automatically when separation finishes"
                    checked: appSettings.autoExport
                    onToggled: appSettings.autoExport = checked
                }

                SectionTitle { text: "SEPARATION" }
                AeroSwitch {
                    width: parent.width
                    text: "Half precision on GPU (about 2x faster on the RX 9070 XT, same quality)"
                    checked: appSettings.fp16
                    onToggled: appSettings.fp16 = checked
                }
                Row {
                    width: parent.width
                    spacing: 12
                    Txt { text: "Segment"; width: 80; height: 24 }
                    AeroSlider {
                        id: segSlider
                        width: parent.width - 200
                        from: 0; to: 7.8; stepSize: 0.1
                        value: appSettings.segment
                        onMoved: appSettings.segment = value
                    }
                    Txt { text: appSettings.segment <= 0.05 ? "Model default" : appSettings.segment.toFixed(1) + " s"; width: 96; height: 24; color: Theme.textLo }
                }
                Hint { width: parent.width; text: "Demucs chunk length. Lower it only if you run out of VRAM; 16 GB fits every model at the default." }
                Row {
                    width: parent.width
                    spacing: 12
                    Txt { text: "Shifts"; width: 80; height: 24 }
                    AeroSlider {
                        width: parent.width - 200
                        from: 0; to: 5; stepSize: 1; snapMode: Slider.SnapAlways
                        value: appSettings.shifts
                        onMoved: appSettings.shifts = Math.round(value)
                    }
                    Txt { text: appSettings.shifts === 0 ? "Off" : appSettings.shifts + " passes"; width: 96; height: 24; color: Theme.textLo }
                }
                Hint { width: parent.width; text: "Demucs random-shift averaging: small quality gain, each pass adds a full run." }
                Row {
                    width: parent.width
                    spacing: 12
                    Txt { text: "Overlap"; width: 80; height: 24 }
                    AeroSlider {
                        width: parent.width - 200
                        from: 0.1; to: 0.9; stepSize: 0.05
                        value: appSettings.overlap
                        onMoved: appSettings.overlap = value
                    }
                    Txt { text: Math.round(appSettings.overlap * 100) + "%"; width: 96; height: 24; color: Theme.textLo }
                }
                Hint { width: parent.width; text: "Segment overlap. RoFormer models always use at least 75%." }

                SectionTitle { text: "STORAGE" }
                Row {
                    width: parent.width
                    spacing: 12
                    Txt { text: "Stem cache uses " + app.cacheText; height: 36 }
                    AeroButton { variant: "tonal"; pillHeight: 36; iconName: "delete"; text: "Clear cache"; onClicked: app.clearCache() }
                }
                Row {
                    width: parent.width
                    spacing: 12
                    Txt { text: "Keep up to"; width: 80; height: 24 }
                    AeroSlider {
                        width: parent.width - 200
                        from: 1; to: 40; stepSize: 1; snapMode: Slider.SnapAlways
                        value: appSettings.cacheLimitGb
                        onMoved: appSettings.cacheLimitGb = Math.round(value)
                    }
                    Txt { text: appSettings.cacheLimitGb.toFixed(0) + " GB"; width: 96; height: 24; color: Theme.textLo }
                }
                Hint { width: parent.width; text: "Cached stems make re-opening a track instant. Oldest entries are pruned past the limit." }

                SectionTitle { text: "DEVELOPER" }
                AeroSwitch {
                    width: parent.width
                    text: "Frame-time overlay (p50 / p99)  ·  F12"
                    checked: appSettings.frameOverlay
                    onToggled: appSettings.frameOverlay = checked
                }
            }
        }
    }
}
