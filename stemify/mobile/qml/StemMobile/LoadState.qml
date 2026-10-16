import QtQuick

// Spinner while a Fetch loads; "Not Connected" or "Couldn't Load" when it fails.
Item {
    id: l
    property var fetch: null
    width: parent ? parent.width : 0
    visible: fetch && (fetch.loading || fetch.error !== "")
    height: !visible ? 0 : fetch.loading ? 106 : err.height
    Spinner { visible: l.fetch && l.fetch.loading; anchors.centerIn: parent; size: 26 }
    EmptyState {
        id: err
        visible: l.fetch && !l.fetch.loading && l.fetch.error !== ""
        icon: app.settings.signedIn ? "warning" : "server"
        title: app.settings.signedIn ? "Couldn't Load" : "Not Connected"
        message: app.settings.signedIn ? (l.fetch ? l.fetch.error : "") : "Sign in to your Plex server to see your music here."
        action: app.settings.signedIn ? "Try Again" : "Connect"
        tap: () => app.settings.signedIn ? l.fetch.refresh(true) : Ui.push("PlexAccountScreen")
    }
}
