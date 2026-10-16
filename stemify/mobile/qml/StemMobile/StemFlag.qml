import QtQuick

// NO STEMS / 7/10 STEMS for an album or artist, once the whole library has been matched.
Tag {
    property var cov: null  // [total, withStems]
    readonly property bool none: !!cov && cov[1] === 0
    readonly property bool partial: !!cov && cov[1] > 0 && cov[1] < cov[0]
    visible: app.coverageReady && (none || partial)
    text: none ? "NO STEMS" : partial ? cov[1] + "/" + cov[0] + " STEMS" : ""
}
