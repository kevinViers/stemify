import QtQuick

// Data for a screen, fetched when it opens (and again when args change). Like the phone's Loader.
QtObject {
    id: f
    property string name
    property var args: []
    property var deps: null  // reloads when it changes, without being passed along
    property var value: null
    property bool loading: true
    property string error: ""
    property bool active: true
    property int _seq: 0
    property bool _ready: false

    function refresh(showLoading) {
        if (!name || !active)
            return
        if (showLoading) { loading = true; error = "" }
        var seq = ++_seq
        Api.call(name, args, function (v) {
            if (seq !== f._seq) return
            f.value = v; f.loading = false; f.error = ""
        }, function (e) {
            if (seq !== f._seq) return
            f.loading = false
            if (f.value === null || showLoading) f.error = e
        })
    }
    onArgsChanged: if (_ready) { value = null; refresh(true) }
    onDepsChanged: if (_ready) { value = null; refresh(true) }
    onActiveChanged: if (_ready && active && value === null) refresh(true)
    Component.onCompleted: { _ready = true; refresh(true) }
}
