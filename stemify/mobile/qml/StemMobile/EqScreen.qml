import QtQuick

Page {
    id: page
    title: "Equalizer"
    grouped: true
    largeTitle: false
    readonly property var st: eq.info
    readonly property var profile: eq.profile
    readonly property var dev: eq.output
    readonly property var linked: { st; dev; return eq.linked() }
    readonly property var chain: { st; return profile.id ? eq.chainOf(profile.id) : [] }

    function deviceIcon(kind) {
        return { speaker: ["desktop", "#8E8E93"], bluetooth: ["headphones", "#007AFF"], wired: ["headphones", "#34C759"],
                 usb: ["usb", "#FF9500"], hdmi: ["airplay", "#5856D6"] }[kind] || ["speakerWave", "#8E8E93"]
    }
    function profileMenu(p) {
        var acts = []
        if (p.devices.indexOf(dev.id) < 0) acts.push({ title: "Use for " + dev.label, run: () => eq.link(p.id, true) })
        else acts.push({ title: "Unlink from " + dev.label, run: () => eq.link(p.id, false) })
        if (st.defaultId !== p.id) acts.push({ title: "Make Default", run: () => eq.setDefault(p.id) })
        acts.push({ title: "Rename…", run: () => Ui.prompt("Rename Profile", "", p.name, "Save", n => eq.rename(p.id, n)) })
        acts.push({ title: "Duplicate", run: () => eq.create(p.name + " Copy", p.id) })
        if (st.profiles.length > 1) acts.push({ title: "Delete Profile", destructive: true, run: () => Ui.confirm("Delete “" + p.name + "”?", "", "Delete", () => eq.delete(p.id)) })
        Ui.actions(p.name, acts)
    }
    function profilesSheet() {
        var acts = st.profiles.map(p => ({
            title: (profile.id === p.id ? "✓ " : "") + p.name + (p.devices.length ? " (" + p.devices.map(d => eq.deviceLabel(d)).join(", ") + ")" : ""),
            run: () => eq.select(p.id)
        }))
        acts.push({ title: "New Profile…", run: () => Ui.prompt("New EQ Profile", "Starts from the current curve.", "", "Create", function (name) {
            var id = eq.create(name, profile.id || ""); eq.link(id, true)
        }, "e.g. Sony WH-1000XM5") })
        if (profile.id) acts.push({ title: "Manage “" + profile.name + "”…", run: () => profileMenu(profile) })
        Ui.actions("Profiles", acts)
    }

    header: Column {
        width: page.width
        Section {
            Cell { title: "Equalizer"; icon: "sliders"; iconBg: Ios.accent
                   IosSwitch { checked: !!page.st.enabled; toggled: (v) => eq.setEnabled(v) } }
            Cell { title: "Anti-Clipping Preamp"
                   IosSwitch { checked: !!page.st.antiClip; toggled: (v) => eq.setAntiClip(v) } }
        }
        Section {
            header: "Output"; inset: 60
            Cell {
                title: page.dev.label; icon: page.deviceIcon(page.dev.kind)[0]; iconBg: page.deviceIcon(page.dev.kind)[1]
                value: page.linked.name || "Default"
                tap: () => Ui.actions("Profile for " + page.dev.label, [{ title: "Use Default", run: () => eq.unlinkAll() }]
                                      .concat(page.st.profiles.map(p => ({ title: p.name, run: () => eq.link(p.id, true) }))))
            }
            CellItem {
                height: 48
                SegmentedControl { x: 16; width: parent.width - 32; anchors.verticalCenter: parent.verticalCenter
                    options: ["Automatic", "Manual"]; selected: page.st.automatic ? 0 : 1; picked: (i) => eq.setAutomatic(i === 0) }
            }
        }
        Section {
            visible: !!page.profile.id
            header: (page.profile.name || "") + (page.st.automatic ? "" : " (manual)")
            CellItem {
                height: ed.height + 20
                Column {
                    id: ed
                    x: 16; y: 10; width: parent.width - 32
                    spacing: 6
                    EqCurve { width: parent.width; height: 64; chain: page.chain; preamp: page.profile.effectivePreamp || 0; active: !!page.st.enabled; range: 15; bandMarkers: true }
                    Row {
                        width: parent.width
                        spacing: (width - 10 * 32) / 9
                        Repeater {
                            model: 10
                            Column {
                                width: 32
                                spacing: 2
                                readonly property real g: page.profile.gains ? page.profile.gains[index] : 0
                                Txt { width: 32; horizontalAlignment: Text.AlignHCenter; text: Api.db(parent.g); style: "caption2"; tnum: true; color: parent.g === 0 ? Ios.label2 : Ios.accent }
                                VerticalBandSlider { height: 124; value: parent.g / 12; moved: (v) => eq.setGain(page.profile.id, index, Math.round(v * 120) / 10) }
                                Txt { width: 32; horizontalAlignment: Text.AlignHCenter; text: eq.bandLabels[index]; style: "caption2"; color: Ios.label2 }
                            }
                        }
                    }
                    Row {
                        width: parent.width
                        Txt { width: parent.width - pv.width; text: "Preamp"; style: "footnote"; color: Ios.label2 }
                        Txt { id: pv; text: page.profile.effectivePreamp ? Api.signedDb(page.profile.effectivePreamp) : "off"; style: "footnote"; tnum: true; color: Ios.label2 }
                    }
                }
            }
        }
        Section {
            Cell { title: "Profile"; value: page.profile.name || "—"; chevron: true; tap: () => page.profilesSheet(); hold: () => page.profile.id && page.profileMenu(page.profile) }
            Cell { title: "Parametric Filters"; chevron: true
                   value: page.profile.filters ? (page.profile.filters.length ? page.profile.filters.filter(f => f.on).length + " on" : "None") : "—"
                   tap: () => page.profile.id && Ui.push("EqFiltersScreen", { profileId: page.profile.id }) }
            Cell { title: "Presets"; value: "Flat, Bass…"; chevron: true
                   tap: () => page.profile.id && Ui.actions("Load Preset", eq.presets.map(p => ({ title: p.name, run: () => eq.setGains(page.profile.id, p.gains) }))) }
        }
    }
}
