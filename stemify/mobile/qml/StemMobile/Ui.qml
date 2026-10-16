pragma Singleton
import QtQuick

// What any screen can ask of the shell (set by Main): navigation, sheets, alerts and the HUD.
QtObject {
    property var shell: null

    function push(page, props) { shell.push(page, props || {}) }
    function pop() { shell.pop() }
    function showInLibrary(page, props) { shell.showIn("library", page, props || {}) }
    function showInSettings(page, props) { shell.showIn("settings", page, props || {}) }
    function selectTab(t) { shell.selectTab(t) }
    function openNowPlaying() { shell.sheetTo(1) }
    function closeNowPlaying() { shell.sheetTo(0) }

    // actions: [{title, destructive, run}]
    function actions(title, list) { shell.overlays.actions(title, list) }
    function confirm(title, message, button, run, destructive) {
        shell.overlays.alert({ title: title, message: message || "", confirm: button, run: run,
                               destructive: destructive === undefined ? true : destructive })
    }
    function prompt(title, message, initial, button, run, placeholder) {
        shell.overlays.alert({ title: title, message: message || "", confirm: button, run: run, field: initial || "",
                               placeholder: placeholder || "", hasField: true, destructive: false })
    }
    function hud(text, icon) { shell.overlays.hud(text, icon === undefined ? "check" : icon) }
}
