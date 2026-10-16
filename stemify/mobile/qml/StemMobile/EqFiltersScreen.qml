import QtQuick

Page {
    id: page
    property string profileId
    title: "Filters"
    grouped: true
    largeTitle: false
    readonly property var profile: { eq.info; return eq.profileById(profileId) }
    readonly property var chain: { eq.info; return eq.chainOf(profileId) }
    onProfileChanged: if (!profile.id) Ui.pop()

    function add() {
        Ui.actions("Add Filter", eq.typeList.map(t => ({ title: t.label, run: function () {
            var f = eq.newFilter(page.profileId, t.type)
            Ui.push("EqFilterScreen", { profileId: page.profileId, filterId: f.id })
        } })))
    }
    function summary(f) {
        var t = eq.typeInfo(f.type), out = []
        if (t.hasGain) out.push(Api.signedDb(f.gain))
        if (t.hasSlope) out.push(f.slope + " dB/oct")
        if (!t.hasSlope || f.slope === 12) out.push("Q " + f.q.toFixed(2))
        if (f.channel !== "both") out.push(f.channel === "left" ? "Left" : "Right")
        return out.join(" · ")
    }
    function importApo() {
        var parsed = eq.parseApo(app.clipboardText())
        if (!parsed.filters) { Ui.hud("No Equalizer APO filters on the clipboard", "info"); return }
        var n = parsed.filters.length
        var what = [n ? n + " filter" + (n === 1 ? "" : "s") : "", parsed.preamp !== null && parsed.preamp !== undefined ? "preamp " + Api.signedDb(parsed.preamp) : ""].filter(s => s).join(" and ")
        var have = profile.filters.length
        var skipped = parsed.skipped > 0 ? " " + parsed.skipped + " line" + (parsed.skipped === 1 ? "" : "s") + " it doesn't understand (GraphicEQ, Delay…) will be skipped." : ""
        Ui.confirm("Replace Filters?", "Loads " + what + " into “" + profile.name + "”, replacing its " + have + " filter" + (have === 1 ? "" : "s") + "." + skipped, "Replace",
                   function () { eq.importApo(page.profileId, parsed); Ui.hud("Imported " + what) }, false)
    }
    trailing: [
        IconButton { icon: "ellipsis"; tap: () => {
            var acts = [{ title: "Paste APO Config", run: () => page.importApo() },
                        { title: "Copy as APO Config", run: () => { app.copyText(eq.apoText(page.profileId)); Ui.hud("Copied") } }]
            if (page.profile.filters.length) acts.push({ title: "Remove All Filters", destructive: true, run: () =>
                Ui.confirm("Remove All Filters?", "The graphic EQ bands stay as they are.", "Remove All", () => eq.clearFilters(page.profileId)) })
            Ui.actions("Equalizer APO / AutoEQ", acts)
        } },
        IconButton { icon: "plus"; tap: () => page.add() }
    ]
    header: Column {
        width: page.width
        Section {
            header: page.profile.name || ""
            footer: "Click a numbered handle to edit that filter. Right-channel filters are orange. Paste an AutoEQ ParametricEQ.txt (from the ••• menu) to load a headphone correction."
            CellItem {
                height: 190
                EqCurve {
                    id: curve
                    x: 12; y: 10; width: parent.width - 24; height: 170
                    chain: page.chain; preamp: page.profile.effectivePreamp || 0; active: !!eq.info.enabled
                    nodes: page.profile.filters || []; labels: true
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: (e) => {
                            var best = -1, bd = 1e9, fs = page.profile.filters
                            for (var i = 0; i < fs.length; i++) {
                                var dx = Api.freqToPos(fs[i].freq) * width - e.x
                                var dy = (eq.typeInfo(fs[i].type).hasGain ? height / 2 - fs[i].gain / 18 * height / 2 : height / 2) - e.y
                                if (dx * dx + dy * dy < bd) { bd = dx * dx + dy * dy; best = i }
                            }
                            if (best >= 0 && Math.abs(Api.freqToPos(fs[best].freq) * width - e.x) < 40)
                                Ui.push("EqFilterScreen", { profileId: page.profileId, filterId: fs[best].id })
                        }
                    }
                }
            }
        }
        Section {
            header: "Preamp"
            footer: eq.info.antiClip ? "Anti-clipping adds " + Api.signedDb((page.profile.effectivePreamp || 0) - (page.profile.preamp || 0)) + " on top, so the total is " + Api.signedDb(page.profile.effectivePreamp || 0) + "." : ""
            CellItem {
                height: 48
                IosSlider {
                    x: 16; width: parent.width - 32 - 86; anchors.verticalCenter: parent.verticalCenter
                    value: ((page.profile.preamp || 0) + 24) / 48; detent: 0.5
                    moved: (v) => eq.setPreamp(page.profileId, Math.round((v * 2 - 1) * 240) / 10)
                }
                Txt {
                    anchors.right: parent.right; anchors.rightMargin: 16; anchors.verticalCenter: parent.verticalCenter
                    text: Api.signedDb(page.profile.preamp || 0); style: "subhead"; tnum: true; color: Ios.accent
                    Pressable { anchors.fill: parent; tap: () => Ui.prompt("Preamp", "In dB, −24 to +24.", (page.profile.preamp || 0).toFixed(1), "Set",
                        s => { var v = Api.number(s); if (v !== null) eq.setPreamp(page.profileId, v) }) }
                }
            }
        }
        Section {
            header: "Filters"
            Repeater {
                model: page.profile.filters || []
                Cell {
                    title: (index + 1) + ". " + eq.typeInfo(modelData.type).label + " · " + Api.hz(modelData.freq)
                    subtitle: page.summary(modelData)
                    titleColor: modelData.on ? Ios.label : Ios.label2
                    tap: () => Ui.push("EqFilterScreen", { profileId: page.profileId, filterId: modelData.id })
                    hold: () => Ui.actions(eq.typeInfo(modelData.type).label + " · " + Api.hz(modelData.freq), [
                        { title: "Duplicate", run: () => eq.duplicateFilter(page.profileId, modelData.id) },
                        { title: "Delete", destructive: true, run: () => eq.removeFilter(page.profileId, modelData.id) }])
                    IosSwitch { checked: modelData.on; toggled: (v) => eq.putFilter(page.profileId, Object.assign({}, modelData, { on: v })) }
                }
            }
            Cell { title: "Add Filter…"; titleColor: Ios.accent; tap: () => page.add() }
        }
    }
}
