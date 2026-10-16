import QtQuick

Page {
    id: page
    property string profileId
    property string filterId
    grouped: true
    largeTitle: false
    readonly property var profile: { eq.info; return eq.profileById(profileId) }
    readonly property var f: { var fs = profile.filters || []; for (var i = 0; i < fs.length; i++) if (fs[i].id === filterId) return fs[i]; return null }
    readonly property int number: { var fs = profile.filters || []; for (var i = 0; i < fs.length; i++) if (fs[i].id === filterId) return i + 1; return 0 }
    readonly property var t: f ? eq.typeInfo(f.type) : ({})
    readonly property var chain: { eq.info; return eq.chainOf(profileId) }
    onFChanged: if (!f) Ui.pop()
    title: f ? number + ". " + t.label : ""

    function put(changes) { eq.putFilter(profileId, Object.assign({}, f, changes)) }
    function ask(title, hint, initial, set) {
        Ui.prompt(title, hint, String(Math.round(initial * 100) / 100), "Set", s => { var v = Api.number(s); if (v !== null) set(v) })
    }
    trailing: IconButton {
        icon: "ellipsis"
        tap: () => Ui.actions(page.t.label + " · " + Api.hz(page.f.freq), [
            { title: "Duplicate", run: () => { var id = eq.duplicateFilter(page.profileId, page.filterId); if (id) Ui.push("EqFilterScreen", { profileId: page.profileId, filterId: id }) } },
            { title: "Delete Filter", destructive: true, run: () => eq.removeFilter(page.profileId, page.filterId) }])
    }
    header: Column {
        width: page.width
        visible: !!page.f
        Section {
            footer: page.t.hasGain ? "Drag on the graph: sideways for frequency, up and down for gain." : "Drag on the graph sideways to change the frequency."
            CellItem {
                height: 210
                EqCurve {
                    x: 12; y: 10; width: parent.width - 24; height: 190
                    chain: page.chain; preamp: 0; active: !!eq.info.enabled && !!page.f && page.f.on
                    nodes: page.profile.filters || []; highlight: page.f; labels: true
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.SizeAllCursor
                        function apply(e) {
                            var p = Math.max(0, Math.min(1, e.x / width))
                            var gain = page.t.hasGain ? Math.max(-18, Math.min(18, 18 * (1 - 2 * e.y / height))) : page.f.gain
                            page.put({ freq: Api.tidyFreq(Api.posToFreq(p)), gain: Math.round(gain * 10) / 10 })
                        }
                        onPressed: (e) => apply(e)
                        onPositionChanged: (e) => { if (pressed) apply(e) }
                    }
                }
            }
        }
        Section {
            Cell { title: "Type"; value: page.t.label || ""; chevron: true
                   tap: () => Ui.actions("Filter Type", eq.typeList.map(x => ({ title: (x.type === page.f.type ? "✓ " : "") + x.label,
                       run: () => page.put({ type: x.type, q: x.type === "notch" && page.f.type !== "notch" ? 4 : page.f.q }) }))) }
            Cell { title: "On"; IosSwitch { checked: !!page.f && page.f.on; toggled: (v) => page.put({ on: v }) } }
        }
        Section {
            footer: page.t.hasSlope && page.f.slope !== 12 ? "Steeper than 12 dB/oct is a Butterworth response, so there's no Q."
                  : page.f && page.f.type === "allpass" ? "An all-pass changes phase around its frequency, not level, so the curve stays flat."
                  : "Click a value to type it."
            ParamCell {
                title: "Frequency"; valueText: page.f ? Api.hz(page.f.freq) : ""
                edit: () => page.ask("Frequency", "In Hz, 10 to 22000.", page.f.freq, v => page.put({ freq: v }))
                IosSlider { width: parent.width; value: page.f ? Api.freqToPos(page.f.freq) : 0; moved: (v) => page.put({ freq: Api.tidyFreq(Api.posToFreq(v)) }) }
            }
            ParamCell {
                visible: !!page.t.hasGain
                title: "Gain"; valueText: page.f ? Api.signedDb(page.f.gain) : ""
                edit: () => page.ask("Gain", "In dB, −24 to +24.", page.f.gain, v => page.put({ gain: v }))
                IosSlider { width: parent.width; detent: 0.5; value: page.f ? (page.f.gain + 24) / 48 : 0.5; moved: (v) => page.put({ gain: Math.round((v * 2 - 1) * 240) / 10 }) }
            }
            ParamCell {
                visible: !!page.f && (!page.t.hasSlope || page.f.slope === 12)
                title: "Q"; valueText: page.f ? page.f.q.toFixed(2) : ""
                edit: () => page.ask("Q", "0.1 (broad) to 20 (narrow). One octave is 1.41.", page.f.q, v => page.put({ q: v }))
                IosSlider { width: parent.width; value: page.f ? Api.qToPos(page.f.q) : 0; moved: (v) => page.put({ q: Math.round(Api.posToQ(v) * 100) / 100 }) }
            }
            CellItem {
                visible: !!page.t.hasSlope
                height: 84
                Column {
                    x: 16; y: 10; width: parent.width - 32; spacing: 8
                    Txt { text: "Slope (dB per octave)"; style: "subhead"; color: Ios.label2 }
                    SegmentedControl { width: parent.width; options: ["6", "12", "24", "36", "48"]
                        selected: page.f ? Math.max(0, [6, 12, 24, 36, 48].indexOf(page.f.slope)) : 1
                        picked: (i) => page.put({ slope: [6, 12, 24, 36, 48][i] }) }
                }
            }
            CellItem {
                height: 84
                Column {
                    x: 16; y: 10; width: parent.width - 32; spacing: 8
                    Txt { text: "Channel"; style: "subhead"; color: Ios.label2 }
                    SegmentedControl { width: parent.width; options: ["Both", "Left", "Right"]
                        selected: page.f ? ["both", "left", "right"].indexOf(page.f.channel) : 0
                        picked: (i) => page.put({ channel: ["both", "left", "right"][i] }) }
                }
            }
        }
    }
}
