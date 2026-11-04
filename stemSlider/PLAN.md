# Stem Slider: Hardware Stem Controller Plan

A palm-sized controller (about 1.5 credit cards) with one physical fader per stem. It's sold in
two ways:

1. **Standalone** for Stemify users at home.
2. **Bundled** with a locked-down Stemify playback box for karaoke bars, pubs, hotels, cruise
   ships, event venues and similar retail and hospitality places.

Status: planning. Nothing is built yet. The open questions are at the bottom of this file.

---

## Contents

- [1. Key constraint: where the mix happens](#1-key-constraint-where-the-mix-happens)
- [2. Product shape](#2-product-shape)
- [3. Hardware](#3-hardware)
- [4. Firmware](#4-firmware)
- [5. Protocol](#5-protocol)
- [6. Software changes in Stemify](#6-software-changes-in-stemify)
- [7. The venue bundle](#7-the-venue-bundle)
- [8. Cost estimates](#8-cost-estimates)
- [9. Phases and milestones](#9-phases-and-milestones)
- [10. Testing](#10-testing)
- [11. Risks and caveats](#11-risks-and-caveats)
- [12. Open questions](#12-open-questions)

---

## 1. Key constraint: where the mix happens

stemserve (`server/stemserve.py`) only serves stem files, waveforms and transcodes. The stem
**mixing** happens on the playback device:

- Desktop: `stemify/player/engine.py` (PortAudio callback, per-stem gains with ramping)
- Separator app: `stemify/audio_engine.py` (`stem_gain`, solo, mute, master volume)
- Phone: the Android app's mixer

So **the controller has to talk to whatever is playing the audio, not to the server.** For the
venue bundle that is the playback box. At home it's the desktop player, or later the phone.

```
  ┌─────────────┐   USB-MIDI (v1)    ┌──────────────────────────┐   HTTP    ┌────────────┐
  │ Stem Slider │ ◀────────────────▶ │ Playback device          │ ◀───────▶ │ stemserve  │
  │ 6 faders    │   Wi-Fi/OSC (v2)   │ (Stemify Player / kiosk) │           │ + stemd    │
  └─────────────┘                    │ mixes stems, drives audio│           └────────────┘
                                     └──────────────────────────┘
```

---

## 2. Product shape

### Physical layout (about 86 × 90 mm)

```
 ┌──────────────────────────────────────────────┐
 │  VOX   DRM   BAS   GTR   PNO   OTH           │  labels / legend
 │  ●     ●     ●     ●     ●     ●             │  RGB status LEDs
 │  ┃     ┃     ┃     ┃     ┃     ┃             │
 │  ┃     ┃     ┃     ┃     ┃     ┃             │  6 × 45 mm faders, 14 mm pitch
 │  █     ┃     ┃     █     ┃     ┃             │
 │  ┃     █     █     ┃     █     █             │
 │  ┃     ┃     ┃     ┃     ┃     ┃             │
 │ [M]   [M]   [M]   [M]   [M]   [M]            │  mute buttons
 │ [ FULL ]              [ KARAOKE ]     ⏻ USB-C│  preset buttons
 └──────────────────────────────────────────────┘
```

- Six stems, matching the current separation output: vocals, drums, bass, guitar, piano, other.
- 6 × 14 mm pitch ≈ 84 mm, so the width is about one credit card (85.6 mm).
- The depth is set by fader travel plus the button rows, about 90 mm.

### SKU ideas

| SKU | Faders | Connection | Target |
|---|---|---|---|
| Slider Lite | Plain, with soft takeover | USB-C | Home users, budget venues |
| Slider Pro | Motorized | USB-C + Wi-Fi | Venues that recall presets often |
| Venue Bundle | Pro + playback box | — | Karaoke and hospitality |

A **"vocals only" mini** (one fader + a Karaoke button, about the size of one card) may be worth a
look. In a karaoke bar, the guide vocal level is the control that gets used 90% of the time.

---

## 3. Hardware

### Components

| Part | v1 choice | Notes |
|---|---|---|
| Faders | 6 × 45 mm linear slide pots (Alps RS45 / Bourns PTA45 class), 10 kΩ linear | Use linear taper: the dB curve is done in software |
| Motorized faders (Pro) | Bourns PSM / Alps RSA0N11M9 class, 45–60 mm | Needs an H-bridge per fader and a touch-sense line |
| Buttons | 6 low-profile tactile switches for mute + 2 for presets | Shallow caps that are easy to clean (venues are sticky) |
| LEDs | 6 × SK6812-mini (addressable RGB) | One data pin. Show mute/solo, sync state and stem activity |
| MCU (Lite) | RP2040 | Native USB, cleaner ADC than the ESP32, cheap, good TinyUSB support |
| MCU (Pro) | ESP32-S3 | Native USB + Wi-Fi/BLE. The ADC is noisier, so add an external ADC (e.g. ADS7828 / MCP3208) |
| ADC (optional) | External 12-bit, 8-channel SPI/I²C | Recommended if pot jitter is visible on the RP2040 |
| Connector | USB-C (device), 5.1 kΩ CC pull-downs | Bus-powered. Motorized needs USB-PD or a DC jack |
| Protection | USB ESD diode array, polyfuse | Venue use means lots of plugging in and static |
| Enclosure | 3D-printed (prototype), folded/CNC aluminum or injection molded (retail) | Bottom rubber feet, optional Kensington slot for venues |

### Electrical notes

- Feed the pots from a filtered, separate 3.3 V reference and put RC filtering on each wiper (e.g. 1 kΩ + 100 nF).
- Keep the pot traces away from the LED data line and the USB lines.
- Motorized faders: add capacitive touch on the fader knob so the motor stops fighting a hand.
  Budget 0.5–1 A peak while several faders move at once.
- Design the PCB in KiCad, as 2 layers for Lite and 4 for Pro.

---

## 4. Firmware

- **Lite:** C/C++ with the Pico SDK + TinyUSB, or CircuitPython for the first prototype.
- **Pro:** ESP-IDF with TinyUSB + Wi-Fi stack.

Responsibilities:

1. **Read the faders** at about 200 Hz, oversample, smooth (EMA), and apply ±1 step hysteresis so
   values don't jitter.
2. **Send changes only** (no repeated identical values).
3. **Debounce buttons** (5–10 ms).
4. **Soft takeover:** when the host sends a stem's current value and the physical fader disagrees,
   ignore that fader until it crosses the host value. The LED blinks while it's out of sync.
5. **Drive the LEDs** from host feedback (mute, solo, sync, optional level meter).
6. **Motor control (Pro):** PID to a target position, stop on touch.
7. **Identity:** a USB product string ("Stem Slider") and serial number, so the host can
   auto-detect it and handle several units.
8. **Updates:** UF2 drag-and-drop bootloader (RP2040) or OTA (ESP32).
9. **Venue lock (optional):** a hold-both-presets combo that turns the mute buttons on or off, so
   patrons can't kill the whole mix.

---

## 5. Protocol

Use class-compliant USB-MIDI so no drivers are needed on any OS. It also works as a generic DAW
controller, which helps marketing.

### Controller → host

| Control | Message | Range |
|---|---|---|
| Vocals fader | CC 20 | 0–127 |
| Drums fader | CC 21 | 0–127 |
| Bass fader | CC 22 | 0–127 |
| Guitar fader | CC 23 | 0–127 |
| Piano fader | CC 24 | 0–127 |
| Other fader | CC 25 | 0–127 |
| Mute buttons 1–6 | Note 36–41 on/off | velocity 127 / 0 |
| Full preset | Note 48 | |
| Karaoke preset | Note 49 | |

Higher resolution option: send 14-bit CC pairs (CC 20–25 MSB + CC 52–57 LSB) when the external
ADC is fitted.

### Host → controller

| Purpose | Message |
|---|---|
| Current stem gain (for soft takeover / motor target) | CC 20–25 |
| Mute state LED | Note 36–41 |
| LED colour / meter level | SysEx (manufacturer ID TBD, or a non-commercial ID for prototypes) |
| Identify / firmware version query | SysEx |

### Gain curve

Map 0–127 onto the same dB curve the apps' on-screen faders use, so physical and on-screen
faders feel the same. Fader at 0 means fully off, not just quiet.

### v2: wireless

OSC over UDP on the local network (`/stem/vocals/gain 0.0–1.0`, `/stem/vocals/mute 0|1`), found
with mDNS (`_stemslider._udp`). This lets a controller sit on a table or at the bar while the box
stays in the back.

---

## 6. Software changes in Stemify

### 6.1 MIDI input for the desktop player (first)

- A new module, e.g. `stemify/player/controller_input.py`, using `mido` + `python-rtmidi`.
- Auto-detect a port named "Stem Slider", and fall back to a configurable MIDI device so generic
  controllers work too.
- Fader CC → the existing per-stem gain path in `engine.py`, so the ramping that prevents clicks
  still applies.
- Mute notes → existing mute. Preset notes → existing mix presets (Full, Karaoke, …).
- Send state back out on song change, preset change, and any on-screen fader move.
- Settings: device picker, MIDI-learn for remapping, takeover mode (pickup / jump).
- Add the same input to the separator app (`stemify/audio_engine.py`) if needed.

### 6.2 Android

- Android supports USB-MIDI natively (`android.media.midi`) over a USB-C OTG cable.
- Hook it into the existing mixer, using the same mapping as desktop.

### 6.3 Venue / kiosk mode (for the bundle)

- Boots fullscreen into the player with settings locked behind a PIN.
- A large lyrics display for singers on a second screen or TV.
- A song request queue (patrons queue from their phones by QR code, or staff queue from a tablet).
- A per-song key change (pitch shift) is commonly expected in karaoke. It isn't in Stemify today.
- Remembers mix defaults, e.g. "Karaoke with guide vocal at 20%".
- Volume limits (a max master level) so staff can't blow out the PA.
- A health page and remote logs for support.

---

## 7. The venue bundle

### Playback box options

| Option | Pros | Cons |
|---|---|---|
| Mini-PC (N100-class), no GPU | Cheap (~$150), quiet, enough for playback | Can't separate on site. Needs a pre-separated library or a remote stemd |
| Mini-PC with GPU / small workstation | Separates new songs on site (Stem Now) | Costly, loud, more power, more to support |
| Raspberry Pi 5 | Very cheap, small | Playback headroom and audio output quality need testing; definitely no separation |
| Cloud stemd + local box | Separation in the cloud, cheap box on site | Ongoing hosting cost, upload bandwidth, legal exposure |

### Library

The current design depends on Plex. For venues, consider:

- A **local-folder library mode** (no Plex account needed), or
- A **managed catalog** you supply (this has licensing implications, see below).

### Audio out

- HDMI audio to the venue's AV system, and/or a USB audio interface with balanced outputs (XLR/TRS)
  for the PA.
- Optional microphone input mixing is out of scope for v1. Venues already have mic mixers.

### Business model ideas

- Hardware sold once + a software subscription (updates, support, catalog).
- Leasing to venues per month, with hardware included.
- Home users: controller only, works with the free/open app.

---

## 8. Cost estimates

Rough guesses at 50–500 unit quantity. Check against real quotes.

| Item | Lite | Pro |
|---|---|---|
| Faders | $6–12 | $60–90 (motorized) |
| MCU (+ external ADC) | $1–2 | $3–8 |
| LEDs, buttons, passives | $3–5 | $3–5 |
| Motor drivers | — | $6–12 |
| PCB + assembly | $5–10 | $10–20 |
| Enclosure | $5–20 | $10–30 |
| Packaging, cable | $3–5 | $3–5 |
| **Estimated BOM** | **$23–54** | **$95–170** |

Not included: certification (FCC/CE: roughly $3–10k for an intentional radiator with Wi-Fi, less
for USB-only), tooling for injection molding, support and returns.

---

## 9. Phases and milestones

| Phase | Deliverable | Est. effort | Done when |
|---|---|---|---|
| 0. Breadboard | Raspberry Pi Pico + 6 pots + buttons, USB-MIDI firmware in `stemSlider/firmware/` | A weekend | Shows up as a MIDI device on Linux; values are steady without touching |
| 1. Host integration | MIDI input in Stemify Player | 1–2 days | Faders move stems smoothly, presets work, LEDs reflect state |
| 2. PCB v1 | KiCad project in `stemSlider/hardware/`, printed case, 5–10 units | 2–4 weeks (incl. fab) | Survives a night at a friendly venue |
| 3. Venue software | Kiosk mode, local library, request queue, mini-PC image | 2–3 weeks | A non-technical person can set it up from a printed card |
| 4. Pilot | 2–3 venues for a month | 1–2 months | Feedback on which controls get used and what breaks |
| 5. Pro | Motorized and/or Wi-Fi SKU | Depends on pilot | Driven by pilot feedback |
| 6. Production | Certification, enclosure tooling, packaging, support process | Months | Ready to sell |

### Planned repo layout

```
stemSlider/
  PLAN.md            ← this file
  firmware/          ← Pico / ESP32 firmware
  hardware/          ← KiCad schematic + PCB, enclosure CAD (STEP/STL)
  docs/              ← protocol spec, assembly guide, venue setup card
```

---

## 10. Testing

- **Firmware:** jitter test (log 10 minutes of untouched faders, expect zero messages), latency
  test (fader to audible change, target under 20 ms), button debounce.
- **Host:** unit tests for CC → gain mapping and takeover logic. A virtual MIDI port
  (`mido` virtual / ALSA `snd-virmidi`) for CI without hardware.
- **Durability:** fader cycle test (most pots are rated 10–30k cycles; venue use can hit that in
  a year), drink spill test, USB plug cycling.
- **Field:** a night-long soak test with the box running and stems switching every song.

---

## 11. Risks and caveats

1. **Music rights (the biggest risk).** Venues need public performance licenses
   (ASCAP/BMI/SESAC/GMR in the US, PRS/PPL in the UK, etc.). Separating commercial recordings
   and playing the result commercially may also need mechanical/derivative or karaoke-specific
   licenses. Personal use is a different situation from selling to businesses. **Get legal advice
   before selling bundles or supplying a catalog.**
2. **Model licenses.** Demucs code is MIT, but each set of pretrained weights may have its own
   terms. Check every model you ship for commercial use.
3. **Plex dependency.** Plex's terms and its account requirement may not suit commercial
   deployment. A local-library mode lowers this risk.
4. **Code maturity.** The repo is openly "100% vibe coded". A paid product needs hardening,
   security review (kiosk on a venue network), crash recovery and an update channel.
5. **Fader wear and sticky environments.** Plain pots wear and gunk up. Consider dust-sealed
   faders or a touch-strip alternative for venues.
6. **Fader sync.** Without motors, preset recalls leave the faders in the wrong place. Soft
   takeover works but needs explaining to staff.
7. **Certification.** Wi-Fi models need FCC/CE intentional-radiator testing. Using a
   pre-certified module (ESP32-S3-WROOM) reduces but doesn't remove this.
8. **Support load.** Hospitality customers expect it to just work at 11 pm on a Saturday.

---

## 12. Open questions

### Product

1. Plain faders with soft takeover for v1, or go straight to motorized?
2. Six faders (one per stem), or fewer for venues (e.g. Vocals + Music + Karaoke button)?
3. Is a one-card "vocals only" mini worth prototyping alongside the six-fader version?
4. Who is the first customer: home Stemify users, or venues?
5. Should the controller also work as a generic MIDI controller in DAWs, as a marketing point?

### Hardware

6. RP2040 (USB only, cheaper) or ESP32-S3 (USB + Wi-Fi) for the first PCB?
7. Is wireless (controller on the table, box in the back) a must-have for venues, or a later add-on?
8. Enclosure target: 3D-printed boutique, aluminum, or injection molded?
9. What fader lifetime is acceptable, and are sealed faders worth the cost?

### Software

10. First host to support: desktop Stemify Player, or the Android app?
11. Do patrons get any control (e.g. their own vocal level through a phone QR page), or staff only?
12. Is per-song key change (pitch shift) needed for v1 of the venue mode?
13. Should the venue mode drop the Plex requirement in favour of a local-folder library?

### Venue bundle and business

14. Separation on site (GPU box), in the cloud, or pre-separated library only?
15. Where does the venue's music come from: their own files, or a catalog you supply?
16. Pricing model: one-off sale, subscription, or lease?
17. Which countries first? This decides the certification (FCC/CE/UKCA) and licensing bodies.
18. Who handles support, and what response time can you promise?
19. Will you get legal advice on licensing separated stems for commercial playback before the pilot?
20. Keep the project open source (and sell hardware/support), or make the venue build closed?
