pragma Singleton
import QtQuick

// Calls into the app off the GUI thread: Api.call("albums", [sort], ok, err). Plus the phone's
// little formatting helpers.
QtObject {
    property var _cbs: ({})

    function call(name, args, ok, err) {
        var id = app.request(name, args || [])
        _cbs[id] = [ok, err]
        return id
    }

    property Connections _results: Connections {
        target: app
        function onResult(id, ok, value) {
            var cb = _cbs[id]
            if (!cb)
                return
            delete _cbs[id]
            if (ok) { if (cb[0]) cb[0](value) }
            else if (cb[1]) cb[1](value)
        }
    }

    function duration(ms) {
        var s = Math.max(0, Math.floor(ms / 1000))
        function two(v) { return (v < 10 ? "0" : "") + v }
        return s >= 3600 ? Math.floor(s / 3600) + ":" + two(Math.floor(s / 60) % 60) + ":" + two(s % 60)
                         : Math.floor(s / 60) + ":" + two(s % 60)
    }
    function minutes(ms) {
        var m = Math.floor(ms / 60000)
        return m >= 60 ? Math.floor(m / 60) + " hr " + (m % 60) + " min" : m + " minute" + (m === 1 ? "" : "s")
    }
    function songs(n) { return n + " song" + (n === 1 ? "" : "s") }
    function sum(list, key) { var t = 0; for (var i = 0; i < list.length; i++) t += list[i][key] || 0; return t }
    function gb(bytes) { return bytes < 1e9 ? (bytes / 1e6).toFixed(0) + " MB" : (bytes / 1e9).toFixed(1) + " GB" }
    // EQ formatting and slider mappings, as on the phone.
    function hz(f) {
        if (f >= 1000) return (f >= 10000 ? (f / 1000).toFixed(1) : (f / 1000).toFixed(2)).replace(/\.00$/, "").replace(/\.0$/, "") + " kHz"
        return Math.floor(f) + " Hz"
    }
    function signedDb(v) { return (v > 0.005 ? "+" : v < -0.005 ? "−" : "") + Math.abs(v).toFixed(1) + " dB" }
    function db(v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + Math.abs(v).toFixed(1) }
    function freqToPos(f) { return Math.max(0, Math.min(1, Math.log(f / 20) / Math.log(1000))) }
    function posToFreq(p) { return 20 * Math.pow(1000, p) }
    function qToPos(q) { return Math.max(0, Math.min(1, Math.log(q / 0.1) / Math.log(200))) }
    function posToQ(p) { return 0.1 * Math.pow(200, p) }
    // Rounds a dragged frequency to what a person would type: 3 significant figures.
    function tidyFreq(f) { var mag = Math.pow(10, Math.floor(Math.log10(f)) - 2); return Math.round(f / mag) * mag }
    function number(s) { var v = parseFloat(String(s).replace(",", ".").replace("−", "-").replace(/[^0-9.\-]/g, "")); return isNaN(v) ? null : v }

}
