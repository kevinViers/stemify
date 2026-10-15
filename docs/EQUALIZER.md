# The Stemify Equalizer: the complete guide

This guide takes you from "I've moved an EQ slider before" to building your own headphone corrections,
fixing problem recordings and knowing exactly why each knob does what it does. It covers every
control in **Settings → Equalizer** on the Android app: what it does, every setting it takes,
when to reach for it, and when to leave it alone.

It assumes you're comfortable with a music player and know the basics: what clipping is, and that
bass lives low and cymbals live high. Everything else is explained as we go.

Every graph here was drawn from the app's own filter maths (the same formulas, the same constants),
so the curves are what the app shows and what you hear. `docs/eq/make_figures.py` regenerates them.

---

## Contents

- [Quick start: five minutes to a better sound](#quick-start-five-minutes-to-a-better-sound)
- [1. The vocabulary](#1-the-vocabulary)
- [2. Where the EQ sits](#2-where-the-eq-sits)
- [3. The Equalizer screen, control by control](#3-the-equalizer-screen-control-by-control)
- [4. The graphic EQ](#4-the-graphic-eq)
- [5. The presets, one by one](#5-the-presets-one-by-one)
- [6. Parametric filters](#6-parametric-filters)
- [7. The filter types in depth](#7-the-filter-types-in-depth)
- [8. Gain staging: preamp, anti-clipping, the limiter and volume](#8-gain-staging-preamp-anti-clipping-the-limiter-and-volume)
- [9. Profiles and devices](#9-profiles-and-devices)
- [10. AutoEQ and Equalizer APO](#10-autoeq-and-equalizer-apo)
- [11. How to EQ by ear](#11-how-to-eq-by-ear)
- [12. Recipes](#12-recipes)
- [13. Troubleshooting and FAQ](#13-troubleshooting-and-faq)
- [Reference card](#reference-card)

---

## Quick start: five minutes to a better sound

1. **Open it.** Settings → **Equalizer**, or on Now Playing tap the output name under the play
   button (it reads something like `Galaxy Buds2 Pro · Rock`).
2. **Connect what you listen on.** The **Output** row shows the device. The EQ remembers a setup
   per device, so do this with your headphones actually connected.
3. **Make a profile for it.** Tap **Profile → New Profile…**, type a name ("Galaxy Buds"), tap
   **Create**. It's linked to the connected device, and (in **Automatic** mode, the default)
   active straight away.
4. **Pick a starting point.** Tap **Presets** and try a few while a song you know well is playing.
   **Rock**, **Late Night** and **Acoustic** are good all-rounders.
5. **Adjust by ear.** Drag a band or two by 2 to 3 dB at a time. Double-tap a slider to put it
   back to 0.
6. **Compare.** Flick the **Equalizer** switch off and on. If you like "on" better, you're done. If
   you only like it because it's louder, read [section 8](#8-gain-staging-preamp-anti-clipping-the-limiter-and-volume).

Want it *right* rather than just *nicer*? Load a measured correction for your exact headphones
from AutoEQ ([section 10](#10-autoeq-and-equalizer-apo)). That's a two-minute job.

---

## 1. The vocabulary

### How big is a dB?

Every gain in the EQ is in decibels (dB). As a guide to what you'll hear:

| Change | What it sounds like | Typical use |
|---|---|---|
| 0.5 dB | Only audible in a careful back-to-back comparison | Fine-tuning a correction |
| 1 dB | Subtle; you notice it if you're listening for it | Gentle polish |
| 2 to 3 dB | Clearly audible, still natural | **The normal EQ move.** Most good settings live here |
| 6 dB | Big. The signal's amplitude has doubled | A deliberate change of character (bass boost, de-harshing) |
| 9 to 12 dB | Dramatic, effect territory | Fixing something badly wrong, or a special effect |
| More than 12 dB | Rarely musical | Notching out a hum, AutoEQ fixing a severe dip |

A boost of +10 dB sounds roughly "twice as loud" in that region. Cuts work the same way downwards.

### Octaves, and why the graph's frequency axis is squashed

Pitch works in ratios: going up an **octave** doubles the frequency. 100 Hz to 200 Hz is one
octave, and so is 5 kHz to 10 kHz, even though the second gap is 50 times wider in Hz. Your ears
hear both as the same-sized step, so every curve in the app (and in this guide) uses a
**logarithmic** frequency axis where each octave takes the same width. Human hearing spans about
ten octaves, 20 Hz to 20 kHz, which is why the graphic EQ has exactly ten bands.

### What lives where

Instruments cover several regions: a **fundamental** (the note itself) plus **harmonics** (the
overtones that give it character). Boosting or cutting a region affects every instrument with
energy there.

| Range | Name | What's there | Too much sounds | Too little sounds |
|---|---|---|---|---|
| 20 to 60 Hz | Sub-bass | 808s, synth subs, kick drum fundamentals, pipe organ, film rumble | Rumbly, woolly, flapping speaker cones | Thin; no "feel" in your chest |
| 60 to 250 Hz | Bass | Kick thump, bass guitar notes, low piano, body of male vocals, floor toms | Boomy, muddy, "one-note" bass | Weak, lightweight |
| 250 to 500 Hz | Low mids | Body of guitars, piano, snare, vocals; the room sound of a recording | Muddy, congested, "blanket over the speakers" | Hollow, thin |
| 500 Hz to 2 kHz | Mids | The core of almost every instrument and voice; guitar crunch, bass growl, horn honk | Honky, nasal, boxy, telephone-like | Scooped, distant, lacking punch |
| 2 to 5 kHz | Upper mids / presence | Vocal clarity, consonants, guitar bite, pick and stick attack, kick "click" | Harsh, shouty, fatiguing (your ears are most sensitive here) | Dull, veiled, vocals buried |
| 5 to 10 kHz | Brilliance | Sibilance (*s*, *t*, *sh*), cymbals, hi-hats, string "zing" | Spitty esses, splashy cymbals, sizzle | Dark, muffled |
| 10 to 20 kHz | Air | Shimmer, breath, room ambience, tape hiss | Brittle, hissy | Closed-in, lifeless (subtle; older ears hear little up here) |

Some anchor points for specific sources:

| Source | Weight / fundamental | Character | Attack / detail |
|---|---|---|---|
| Kick drum | 50 to 100 Hz | Boxiness 300 to 500 Hz | Beater click 2 to 5 kHz |
| Bass guitar | 40 to 250 Hz | Growl 700 Hz to 1.2 kHz | String and pick 2 to 4 kHz |
| Snare | Body 150 to 250 Hz | Crack around 1 kHz | Snap 2 to 5 kHz, wires 5 to 10 kHz |
| Male voice | 85 to 300 Hz | Nasal around 1 kHz | Presence 2 to 5 kHz, sibilance 4 to 8 kHz |
| Female voice | 165 to 500 Hz | Nasal around 1 to 1.5 kHz | Presence 3 to 6 kHz, sibilance 6 to 10 kHz |
| Electric guitar | 80 to 300 Hz | Crunch 1.5 to 3 kHz | Fizz 5 kHz and up (often best tamed) |
| Acoustic guitar | Boom 100 to 200 Hz | Body 200 to 500 Hz | Strings 2 to 5 kHz, sparkle 8 to 12 kHz |
| Piano | 27 Hz to 4 kHz fundamentals | Wood and body 200 to 500 Hz | Hammer 2 to 5 kHz |
| Cymbals, hi-hats | Clang 300 to 600 Hz | Shimmer 6 to 12 kHz | Stick 3 to 5 kHz |

### Boost or cut?

Both change the balance, but they're not equivalent in practice:

- **A cut removes a problem; a boost adds emphasis.** Most "this sounds bad" problems (mud,
  harshness, boom) are something there's *too much* of, so cutting it is the honest fix.
- **Cuts are free; boosts cost headroom.** A boost needs the whole signal turned down to avoid
  clipping (see [section 8](#8-gain-staging-preamp-anti-clipping-the-limiter-and-volume)), so a
  big boost means a quieter result overall.
- **"More bass" and "less everything else" are the same curve, at different volumes.** If you
  want +4 dB of bass, a −4 dB high shelf above 200 Hz gets you the same tonal balance with no
  clipping risk. You just turn the volume up a notch.

### Louder sounds better (and will fool you)

Played back to back, the louder version almost always sounds "better": fuller, clearer, more
exciting, even when it isn't. Every boost makes things louder, so a beginner's EQ tends to drift
into "everything up". Stemify's anti-clipping preamp helps here: it turns the EQ'd signal down by
the size of the biggest boost, so an "everything up" curve comes out *quieter*, not louder. The
habit to build anyway: **when comparing two settings, match their loudness first.**

---

## 2. Where the EQ sits

Everything you hear passes through this chain, in this order:

```
 vocals ─┐
 drums  ─┤  stem faders     ┌──────────────────────────── the EQ ─────────────────────────────┐
 bass   ─┼─ (mute, solo,  ─▶│ preamp ──▶ 10 graphic bands ──▶ parametric filters, top to bottom │─▶ master ─▶ limiter ─▶ 🎧
 guitar ─┤   0 to +3.9 dB)  │ (yours + anti-clip)                                              │   volume
 piano  ─┤                  └──────────────────────────────────────────────────────────────────┘
 other  ─┘
```

What that means in practice:

- **The EQ shapes the finished mix, not individual stems.** A cut at 80 Hz lowers the kick, the
  bass guitar and the bottom of the piano together. If you want less of *one instrument*, that's
  the mixer's job (the stem faders on Now Playing). **Faders pick the instruments; the EQ sets the
  tone.** They combine nicely: mute the vocals, then use the EQ to push the guitars into the gap.
- **Songs without stems go through the same EQ.** The original file is simply a mix of one stem,
  so your profile sounds the same before and after a song is separated.
- **The mixer's level meters read *before* the EQ.** They show each stem's level after its fader,
  so EQ changes don't move them.
- **It's the same engine everywhere:** phone, headphones, Bluetooth, USB DACs, and Android Auto
  in the car. Whatever profile applies to the current output is what you hear.
- **It runs at the song's own sample rate.** Filters are recalculated for 44.1 kHz, 48 kHz or
  96 kHz material, so a 1 kHz filter is 1 kHz whatever you play. (The one visible effect: at
  44.1 and 48 kHz the 16 kHz band is squeezed against the top of the audio range and falls away
  faster on its upper side. You can see it in the preset graphs below.)
- **Changes are instant.** Moving a slider or dragging a filter updates the sound within a few
  milliseconds and glides smoothly. Adding, deleting or re-typing a filter, or changing its
  channel, restarts the filters, which can make a tiny click mid-song.

---

## 3. The Equalizer screen, control by control

The main screen fits on one page. Here's every control from top to bottom.

### Equalizer (switch)

**What it does:** turns the whole EQ on or off: bands, parametric filters and preamp together.
Off is a true bypass, so the audio passes through untouched. Your settings are kept.

**Settings:** On (default) or Off. When it's off, the curve greys out, Settings shows
"Equalizer: Off", and the Now Playing output label drops the profile name.

**When to use it:** for instant before/after comparisons, and whenever you want the recording
exactly as mastered.

**Watch out:** switching it on can change loudness. With anti-clipping on, an EQ with boosts
gets *quieter*, so "on" may sound worse purely because it's quieter. Compare fairly.

### Anti-Clipping Preamp (switch)

**What it does:** works out the highest point of the current curve and turns the EQ's input down
by exactly that much, so a full-scale signal can never be boosted past full scale.

**Settings:** On (default) or Off. It's a global setting that applies to every profile.

**When to use it:** almost always. Turn it off only if you understand the trade-off: louder
output, with the limiter doing the work instead, which can pump and squash on big boosts. The
full story, with worked numbers, is in [section 8](#8-gain-staging-preamp-anti-clipping-the-limiter-and-volume).

### Output (row)

**What it shows:** the device audio is going to right now (icon and name), and on the right the
profile **linked** to that device, or **Default** if none is.

| Icon | Device kind |
|---|---|
| Grey phone | Phone speaker (and earpiece) |
| Blue headphones | Bluetooth (headphones, speakers, hearing aids, LE Audio) |
| Green headphones | Wired: 3.5 mm jack, USB-C to 3.5 mm dongles, analogue line out |
| Orange USB | USB DACs, USB headsets, docks |
| Purple AirPlay-style icon | HDMI (TVs, receivers) |
| Grey speaker | Anything else Android reports |

**Tap it** for the "Profile for <device>" sheet:

- **Use Default** unlinks this device, so it falls back to the default profile.
- **Any profile name** links that profile to this device. Each device has at most one linked
  profile, so this replaces any previous link.

**When to use it:** whenever you connect something new and want it to have its own sound. You can
only link the device that's currently playing, so connect it first.

**Watch out:** links only take effect in **Automatic** mode. In Manual, the row still shows the
link, but the hand-picked profile plays.

### Automatic / Manual (segmented control)

**What it does:** decides how the active profile is chosen.

- **Automatic** (default): the profile linked to the current output plays. If none is linked, the
  default profile plays. Plug in headphones and their profile takes over instantly, mid-song included.
- **Manual**: one profile plays on every device until you switch back. Tapping **Manual** freezes
  whichever profile was playing at that moment.

**Settings and their side effects:**

- **Picking a profile from the Profile sheet switches to Manual by itself**, since you've
  clearly asked for that profile.
- **Switching back to Automatic forgets the manual pick.** The device's linked profile (or the
  default) takes over again.

**When to use Manual:** for situational setups (a "Karaoke" or "Late Night" profile you want *now*,
whatever you're wearing), and for editing a profile that isn't linked to the current device.

### The editor: curve, bands and preamp readout

The section header is the active profile's name, plus "(manual)" when it was picked by hand. This is
how you know which profile your edits go into.

**The curve** shows the true combined response of everything in the profile: the ten bands *and*
every parametric filter that's on, calculated from the actual filters. The vertical scale is ±15 dB
with a line every 6 dB, and faint vertical lines mark the ten band centres.

- **Solid line:** the EQ's shape.
- **Dashed line:** the same shape after the preamp's cut. It only appears when the total preamp
  is negative.
- **Orange line:** the right channel, drawn separately whenever a filter applies to only one channel.
- **Grey everything:** the Equalizer switch is off.

**The ten band sliders** are the graphic EQ ([section 4](#4-the-graphic-eq)). The number above each
is its gain, in grey at 0 and in the accent colour otherwise.

**Preamp** (bottom line) shows the **total** preamp in effect: your manual preamp plus whatever
anti-clipping adds, or "off" when it's zero. It's a readout only; the manual part is set on the
Parametric Filters page.

### Profile (row)

Shows the active profile. **Tap** it for the profile list:

- **Every profile**, with a ✓ on the active one and its linked devices in brackets. Tapping one
  makes it active and switches to Manual.
- **New Profile…**: creates a profile from the current curve and links it to the current device.
- **Manage "<name>"…**: actions for the active profile (Use for / Unlink from <device>, Make
  Default, Rename, Duplicate, Delete).

**Hold** the row to jump straight to the Manage menu. Everything about profiles is in
[section 9](#9-profiles-and-devices).

### Parametric Filters (row)

Opens the filter editor for the active profile. The value on the right says how many filters are
switched on, or "None". See [section 6](#6-parametric-filters).

### Presets (row)

Opens the preset list. Tapping a preset **replaces the active profile's ten band values** with the
preset's. Filters and preamp are untouched. There's no undo, so **Duplicate** a profile you care
about before experimenting. The presets themselves are covered in
[section 5](#5-the-presets-one-by-one).

### Saving

There's no Save button: every change is saved automatically within half a second. Profiles survive
app updates. Clearing the app's storage resets the EQ to its two starting profiles.

---

## 4. The graphic EQ

The graphic EQ is ten fixed **peak filters** (bells), one per octave, each with a Q of 1.41 (one
octave wide), adjustable from **−12 to +12 dB**. It's the fast, intuitive half of the equalizer:
find the band, push it up or down.

### Using the sliders

| Gesture | Result |
|---|---|
| Drag up or down | Changes the gain. The slider moves *relative* to your finger, so touching it slightly off the thumb doesn't make it jump |
| Drag through the middle | Snaps to exactly 0 dB with a haptic tick |
| Double-tap | Resets that band to 0 dB |

Gains are continuous; the label shows them to 0.1 dB. To type an exact value, or set a frequency
that isn't on the grid, use a parametric filter instead.

### The bands, one by one

Each band is centred on its frequency and still has half its effect half an octave either side.
A +6 dB move at 1 kHz is still +3 dB at 707 Hz and at 1.4 kHz, and only fades to nothing about
two octaves away.

**31 Hz: the sub-bass**
- *What's there:* the deepest kick and 808 energy, synth subs, organ pedals, film and live-room rumble.
- *Boost* (+2 to +6) on headphones or a subwoofer system that can actually play it, for physical weight.
- *Cut* (−3 to −12) on small speakers, cheap earbuds and phone speakers that can't reproduce it.
  Boosting there just eats headroom and makes the driver flap. Also cut it for rumbly live recordings.
- *Careful:* this is the band most likely to set the anti-clip cut, because boosts here are common
  and costly.

**62 Hz: the punch**
- *What's there:* kick drum thump, bass guitar fundamentals, the bottom of male voices.
- *Boost* (+2 to +5) for punch and weight; this is the "bass" most people mean.
- *Cut* (−2 to −6) when bass is boomy or one-note, or on speakers that rattle.
- *Careful:* together with 125 Hz it's where most "too much bass" lives.

**125 Hz: the warmth**
- *What's there:* bass body, low piano, floor toms, warmth of male vocals, the "chest" of a voice.
- *Boost* (+1 to +4) for warmth and fullness on thin, bright recordings or headphones.
- *Cut* (−2 to −5) for boominess and "boxed-in" sound, often in cars and small rooms.

**250 Hz: the mud zone**
- *What's there:* body of guitars, piano and snare, low vocal resonance, room build-up.
- *Boost* (+1 to +3) only to fatten a thin recording.
- *Cut* (−1 to −4) is one of the most useful moves in all of EQ: it clears mud and congestion and
  makes everything sound more open and separated.

**500 Hz: the box**
- *What's there:* the boxy, cardboard part of drums, guitars and voices.
- *Boost* (+1 to +3) for body and density.
- *Cut* (−1 to −4) for a hollow or "cardboard" sound. The classic "smile" EQ dips here.

**1 kHz: the nose**
- *What's there:* the core of nearly every instrument; nasal vocals, honky horns, guitar crunch, bass growl.
- *Boost* (+1 to +4) to bring things forward and "in your face". This is how radio and pop curves sound.
- *Cut* (−1 to −3) for a nasal, honky or telephone-like quality.
- *Careful:* big cuts here make music sound distant and scooped.

**2 kHz: the bite**
- *What's there:* vocal intelligibility, guitar and synth bite, snare crack.
- *Boost* (+1 to +3) for clarity when vocals or speech get lost.
- *Cut* (−1 to −3) when loud passages turn harsh and edgy.

**4 kHz: the presence**
- *What's there:* consonants, pick and stick attack, the "click" of a kick. Your ears are most
  sensitive around 3 to 4 kHz.
- *Boost* (+1 to +3) for detail and definition. Small moves go a long way.
- *Cut* (−2 to −5) for shouty, fatiguing sound, the most common cause of "I can't listen to this
  for long". Many headphones have a peak around here.

**8 kHz: the sizzle**
- *What's there:* sibilance (*s*, *t*, *sh*), hi-hats, cymbals, string zing.
- *Boost* (+1 to +4) for crispness on dull recordings or dark headphones.
- *Cut* (−2 to −5) for spitty esses and splashy cymbals. A narrow parametric cut between 5 and
  9 kHz is often better than this broad band.

**16 kHz: the air**
- *What's there:* shimmer, breath, ambience, and on old recordings, hiss.
- *Boost* (+1 to +4) for openness and sparkle. Subtle on most systems.
- *Cut* (−2 to −6) for tape hiss or brittle digital highs.
- *Note:* hearing above about 14 kHz fades with age, so judge this band by how the whole sound
  changes, not by whether you hear "something at 16k".

### The bands overlap, and the curve tells the truth

Because each band is an octave wide and the bands are an octave apart, neighbours overlap, so
**adjacent boosts add up.** Two neighbouring bands at +6 dB reach +7.2 dB; three reach +8.3 dB,
and the bumps between bands don't match any single slider.

![Neighbouring bands add up](eq/band-overlap.svg)

So:

- **Trust the curve over the slider labels.** The curve is the real response.
- **For a broad change, use smaller moves on several bands** (+2, +3, +2) rather than one big move.
  It sounds smoother, and the combined height is bigger than it looks.
- **The anti-clip cut follows the curve's peak, not the biggest slider.** That's why it can be
  larger than any single gain.
- **Alternating up/down bands make a lumpy curve** and usually sound phasey and unnatural. Smooth
  curves sound better.

### Graphic-EQ strategies

- **Start from Flat or a preset**, then make two or three moves, not ten.
- **Cut before you boost.** Try lowering what's too much before raising what's missing.
- **One octave is coarse.** If a problem sits *between* two bands, or is narrower than an octave
  (a single ringing resonance), use a parametric filter instead.

---

## 5. The presets, one by one

The presets are modelled on the iOS Music EQ list, rebuilt on Stemify's ten bands. Here's each one's
real combined curve, with the anti-clip preamp it needs (with anti-clipping on, it's applied for
you). The bigger that number, the quieter the preset plays.

![Every EQ preset](eq/presets.svg)

| Preset | 31 | 62 | 125 | 250 | 500 | 1k | 2k | 4k | 8k | 16k | Peak | Anti-clip |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Flat | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | off |
| Acoustic | +4.5 | +4.5 | +3.5 | +1 | +1.5 | +1.5 | +3 | +3.5 | +3 | +2 | +6.0 @ 62 Hz | −6.0 |
| Bass Booster | +5.5 | +4.5 | +3.5 | +2.5 | +1.5 | 0 | 0 | 0 | 0 | 0 | +6.5 @ 32 Hz | −6.5 |
| Bass Reducer | −5.5 | −4.5 | −3.5 | −2.5 | −1.5 | 0 | 0 | 0 | 0 | 0 | 0 | off |
| Classical | +4.5 | +3.5 | +3 | +2.5 | −1.5 | −1.5 | 0 | +2 | +3 | +3.5 | +5.3 @ 32 Hz | −5.3 |
| Dance | +3.5 | +6.5 | +5 | 0 | +2 | +3.5 | +5 | +4.5 | +3.5 | 0 | +8.1 @ 63 Hz | −8.1 |
| Deep | +5 | +3.5 | +1.5 | +1 | +3 | +2.5 | +1.5 | −2 | −3.5 | −4.5 | +5.7 @ 32 Hz | −5.7 |
| Electronic | +4 | +3.5 | +1 | 0 | −2 | +2 | +1 | +1.5 | +4 | +5 | +5.3 @ 16 kHz | −5.3 |
| Hip-Hop | +5 | +4 | +1.5 | +3 | −1 | −1 | +1.5 | −0.5 | +2 | +3 | +5.8 @ 32 Hz | −5.8 |
| Jazz | +4 | +3 | +1.5 | +2 | −1.5 | −1.5 | 0 | +1.5 | +3 | +3.5 | +4.6 @ 32 Hz | −4.6 |
| Late Night | +4.5 | +3 | +2 | −0.5 | +1.5 | +1.5 | +2 | +3 | +3.5 | +4 | +5.1 @ 32 Hz | −5.1 |
| Loudness | +6 | +4 | 0 | 0 | −2 | 0 | −1 | −5 | +5 | +1 | +6.8 @ 32 Hz | −6.8 |
| Piano | +3 | +2 | 0 | +2.5 | +3 | +1.5 | +3.5 | +4.5 | +3 | +3.5 | +5.7 @ 4 kHz | −5.7 |
| Pop | −1.5 | −1 | 0 | +2 | +4 | +4 | +2 | 0 | −1 | −1.5 | +5.2 @ 510 Hz | −5.2 |
| R&B | +2.5 | +7 | +5.5 | +1.5 | −2.5 | −1.5 | +2 | +2.5 | +3 | +3.5 | +8.5 @ 63 Hz | −8.5 |
| Rock | +5 | +4 | +3 | +1.5 | −0.5 | −1 | +0.5 | +2.5 | +3.5 | +4.5 | +5.9 @ 32 Hz | −5.9 |
| Small Speakers | +5.5 | +4.5 | +3.5 | +2.5 | +1.5 | 0 | −1.5 | −2.5 | −3.5 | −4 | +6.5 @ 32 Hz | −6.5 |
| Spoken Word | −3.5 | −0.5 | 0 | +0.5 | +3.5 | +4.5 | +4.5 | +4 | +2 | 0 | +6.2 @ 2 kHz | −6.2 |
| Treble Booster | 0 | 0 | 0 | 0 | 0 | +1.5 | +2.5 | +3.5 | +4.5 | +5.5 | +5.9 @ 16 kHz | −5.9 |
| Treble Reducer | 0 | 0 | 0 | 0 | 0 | −1.5 | −2.5 | −3.5 | −4.5 | −5.5 | 0 | off |
| Vocal Booster | −1.5 | −3 | −3 | +1.5 | +3.5 | +3.5 | +3 | +1.5 | 0 | −1.5 | +4.8 @ 1 kHz | −4.8 |

What each one is for, and how to adapt it:

**Flat.** Everything at 0. The recording as mastered (through your headphones' own sound). Load it
to reset the bands without touching filters or preamp, for example before importing an AutoEQ
correction.

**Acoustic.** A broad lift everywhere, heaviest in the lows (+4.5) and at 2 to 8 kHz (+3 to +3.5).
Body plus string and breath detail, for unplugged sets, folk and singer-songwriter music. Since
nearly everything goes up, it mostly adds warmth and sparkle relative to the low mids. *Tweak:*
on bright headphones, take 4k and 8k down to +1.5.

**Bass Booster.** A smooth slope from +5.5 at 31 Hz down to 0 at 1 kHz: more weight without
touching the mids or treble. *Tweak:* on earbuds and small speakers, set 31 Hz to 0 (they can't play
it) and you get the same audible effect with 2 to 3 dB more headroom.

**Bass Reducer.** The mirror image. Needs no preamp because it only cuts. For boomy rooms, cars
with big subwoofers, bass-heavy headphones, or podcasts recorded with too much low end.

**Classical.** Lows up (+4.5 to +2.5), a slight midrange dip (−1.5 at 500 Hz and 1 kHz), highs up
(+2 to +3.5): a gentle "smile" that adds concert-hall weight and air without crowding the
midrange where orchestral instruments fight. Good for orchestral, choral and film scores at moderate
volume.

**Dance.** Two humps: a big low one (+6.5 at 62, +5 at 125) with a deliberate gap at 250 Hz to
keep the kick clean, then a broad presence lift from 1 to 8 kHz (+3.5 to +5). Very hyped. It has the
second-biggest anti-clip cut (−8.1 dB), so turn the volume up after loading it. Good for EDM,
house and club pop on headphones that can handle it.

**Deep.** Lows and lower mids up, highs rolled off (−2 at 4k to −4.5 at 16k): dark, warm and
smooth. Good for harsh or bright recordings, fatiguing headphones, long listening sessions, and
lo-fi.

**Electronic.** Lows up, a dip at 500 Hz (−2), a small 1 kHz bump, then a big top end (+4, +5).
Scooped and sparkly: synth sub-bass and shimmering highs with the boxy middle pulled back.

**Hip-Hop.** Big sub and kick (+5, +4), an extra +3 at 250 Hz for 808 harmonics and kick body
(which helps the bass come through on small drivers), a mid dip, then gentle highs for hats. Good
for hip-hop and trap.

**Jazz.** Warm lows, a slight mid dip, smoothly rising highs: upright bass weight, cymbal air, and
horns that aren't honky. Good for small-group jazz, acoustic and vintage recordings.

**Late Night.** Lows +4.5 tapering, a dip at 250 Hz, a mild mid lift, and highs rising to +4.
This compensates for how your ears lose bass and treble at low volume (the equal-loudness effect),
so quiet listening still sounds full. Good at low volume. At normal volume it sounds over-hyped.

**Loudness.** A stronger take on the same idea, with a twist: +6 at 31, a −5 dB cut at 4 kHz (the
most fatiguing region) and +5 at 8 kHz. Rich and crisp but never shouty. Good for background
listening and the car. *Careful:* that deep 4 kHz cut buries vocals in some mixes.

**Piano.** Gentle lift everywhere except 125 Hz, peaking at 4 kHz (+4.5): body (250 to 500 Hz) plus
hammer attack and clarity (2 to 4 kHz). Also good for acoustic instruments in general.

**Pop.** The midrange bump: +4 at 500 Hz and 1 kHz, both extremes slightly down. Vocal-forward,
dense and "radio"-like. Good for vocal-led pop, and for making a busy mix intelligible on cheap
speakers. On good headphones it can sound boxy.

**R&B.** The biggest bass boost in the list: +7 at 62 Hz, +5.5 at 125 Hz, a firm dip at 500 Hz
(−2.5) and rising highs. Thick, smooth low end with airy vocals. It needs the biggest anti-clip
cut (−8.5 dB), so expect to raise the volume.

**Rock.** The classic smile: lows +5 to +3, mids slightly down (−0.5, −1), highs +2.5 to +4.5.
Drums with weight and snap, guitars with bite, without midrange congestion. A good default for
rock, metal and most guitar music.

**Small Speakers.** More bass (+5.5 to +2.5 through 250 Hz), less treble (−1.5 to −4 from 2 kHz up).
It's the starting profile for the phone speaker, where it fills in missing body and softens harsh
highs. *Tweak:* a phone speaker can't play 31 or 62 Hz at all, so setting those to 0 costs nothing
audible and gives back about 2 dB of headroom. For an even better phone-speaker setup, see the
[phone speaker recipe](#phone-speaker-that-doesnt-distort).

**Spoken Word.** Rumble cut (−3.5 at 31 Hz) and a broad lift across 500 Hz to 4 kHz (+3.5 to
+4.5), where speech intelligibility lives. For podcasts, audiobooks and talk radio, especially over
road or kitchen noise.

**Treble Booster.** A rising slope from +1.5 at 1 kHz to +5.5 at 16 kHz. Brightens dull recordings
and dark headphones. Pairs well with older or worn-out recordings. Go easy on already bright ones.

**Treble Reducer.** The mirror image. No preamp needed. Tames harsh recordings and bright
headphones, and helps with listening fatigue. A gentler alternative to Deep.

**Vocal Booster.** Bass cut (−3 at 62 and 125 Hz), body and presence lifted (+3 to +3.5 from 500 Hz
to 2 kHz), top slightly trimmed. Pulls voices forward. In Stemify you also have the vocal stem
fader, which is usually the cleaner way to make vocals louder. Use this preset when a song has
no stems yet, or to make lyrics clearer over noise.

---

## 6. Parametric filters

Graphic bands are fixed bells at fixed spots. A **parametric filter** lets you choose its **type**
(bell, shelf, cutoff and more), its exact **frequency**, its **gain**, its **width** (Q) or **slope**,
and which **channel** it applies to. You can add as many as you need, and they run *after* the ten
bands as part of the same profile.

Reach for parametric filters when:

- the problem sits between two graphic bands, or is narrower than an octave;
- you need a cutoff (high pass, low pass) or a shelf, which graphic bands can't do;
- you're loading or building a headphone correction;
- one channel needs different treatment from the other.

### The Filters page

Settings → Equalizer → **Parametric Filters**. From top to bottom:

**The curve** is the whole profile (bands and filters), 20 Hz to 20 kHz, ±18 dB, with each
parametric filter drawn as a **numbered handle**.

- Handles sit at the filter's frequency and gain. Filters without gain (passes, notch, band pass,
  all pass) sit on the 0 dB line.
- **Blue** handles are on, **grey** are off, **orange** apply to the right channel only.
- **Tap a handle** (or anywhere near it horizontally) to open that filter.
- A dashed line shows the curve after the preamp, when the total is negative.

**The ••• menu (top right), "Equalizer APO / AutoEQ":**
- **Paste APO Config** imports filters from the clipboard ([section 10](#10-autoeq-and-equalizer-apo)).
- **Copy as APO Config** exports the profile to the clipboard.
- **Remove All Filters** deletes every parametric filter in this profile, after a confirmation.
  The ten bands stay as they are.

**+ (top right)** adds a filter.

**Preamp** is the profile's **manual preamp**, from −24 to +24 dB in 0.1 dB steps. It snaps to 0
with a haptic tick, and you can tap the value to type it. With anti-clipping on, the line under it
spells out the sum: "Anti-clipping adds −3.0 dB on top, so the total is −6.0 dB."
[Section 8](#8-gain-staging-preamp-anti-clipping-the-limiter-and-volume) explains when to touch it.

**Filters** is the list, one row per filter:

- The title is the number (matching the handle), type and frequency: `2. High Shelf · 8 kHz`.
- The subtitle has the details: gain, slope, Q and channel, for example `−3.0 dB · Q 0.71 · Left`.
- The switch turns that filter on or off without losing its settings. It's the best way to hear
  what a single filter is doing.
- **Tap** the row to edit the filter. **Hold** it to **Duplicate** or **Delete** it.
- **Add Filter…** at the bottom does the same as **+**.

### Adding a filter

Tap **+** or **Add Filter…** and choose a type. The new filter starts at a sensible, mildly
audible setting, and the app opens its page straight away:

| Type | Starts at |
|---|---|
| Peak | 1 kHz, +3 dB, Q 1.41 |
| Low Shelf | 105 Hz, +3 dB, Q 0.71 |
| High Shelf | 8 kHz, +3 dB, Q 0.71 |
| Low Pass | 16 kHz, 12 dB/oct, Q 0.71 |
| High Pass | 30 Hz, 24 dB/oct |
| Band Pass | 1 kHz, Q 0.71 |
| Notch | 60 Hz, Q 4 |
| All Pass | 1 kHz, Q 0.71 |

### A filter's page

The title is the filter's number and type ("3. Peak"). Its ••• menu has **Duplicate** (which
opens the copy) and **Delete Filter**.

**The curve** shows the whole profile, with *this* filter's own contribution shaded underneath and
its handle highlighted. Other filters' handles are shown too, for orientation.

**Dragging on the curve.** Put a finger anywhere on the graph and drag. The filter follows your
finger: its frequency is wherever you are horizontally, its gain wherever you are vertically
(±18 dB here; type a value for up to ±24). For filters without gain, only the frequency follows.
While dragging, frequencies tidy to three significant figures (1.23 kHz, not 1,234.57 Hz).

Below the curve are the controls. All of them change the sound live.

#### Type

Tap to choose from the eight types ([section 7](#7-the-filter-types-in-depth)). Changing type
keeps the frequency, gain, Q and slope. Two details:

- **Gain is remembered but ignored** for types without gain, so switching Peak → Notch → Peak
  gets your gain back.
- **Switching to Notch sets Q to 4**, a sensible notch width, unless it was already a notch.

#### On

The same as the switch in the list. Off filters keep their settings, draw grey handles, and do
nothing to the sound.

#### Frequency

**Range:** 10 Hz to 22 kHz. **Slider:** logarithmic, so each octave gets the same travel.
**Tap the value** to type an exact number in Hz.

What the frequency *means* depends on the type:

| Type | The frequency is… |
|---|---|
| Peak | the centre of the bell (maximum boost or cut) |
| Low / High Shelf | the midpoint of the step, where the shelf reaches half its gain |
| Low / High Pass | the cutoff, where the level is down 3 dB (at Q 0.71 or any steeper slope) |
| Band Pass | the centre, the only point left at full level |
| Notch | the frequency removed completely |
| All Pass | the point of maximum phase shift (180°) |

Filters above about 49% of the sample rate (21.6 kHz at 44.1 kHz) are held just below it.

#### Gain (Peak and Shelves only)

**Range:** −24 to +24 dB. The slider moves in 0.1 dB steps and snaps to 0 with a haptic tick.
**Tap the value** to type one.

A Peak or Shelf at 0 dB is inactive: it does nothing and costs nothing. That's handy for parking
a filter while you think.

#### Q

Shown for Peak, Shelves, Band Pass, Notch, All Pass, and for Low/High Pass at 12 dB/oct.
**Range:** 0.1 to 20. **Slider:** logarithmic. **Tap the value** to type it (the hint reminds you
that "One octave is 1.41").

Q is **how narrow** the filter is: higher Q, narrower. Its exact effect depends on the type, and
each type in [section 7](#7-the-filter-types-in-depth) shows it with a graph. For a peak, Q
translates into bandwidth like this:

| Q | Bandwidth | Character |
|---|---|---|
| 0.3 | about 4.3 octaves | A tilt more than a bell |
| 0.5 | about 2.5 octaves | Very broad and gentle; very musical |
| 0.71 | about 1.9 octaves | Broad. Also the "no bump" standard for shelves and passes |
| 1.0 | about 1.4 octaves | Broad-ish |
| 1.41 | 1 octave | Same as a graphic band |
| 2.0 | about 0.7 octave | Medium |
| 2.87 | ½ octave | Focused |
| 4.32 | ⅓ octave | Narrow |
| 6 to 10 | ¼ to ⅐ octave | Surgical: a single resonance or note |
| 20 | about 1/14 octave | As narrow as it gets: hums and whistles |

#### Slope (Low Pass and High Pass only)

A five-way choice: **6, 12, 24, 36 or 48 dB per octave**, which is how fast the level falls beyond
the cutoff. The **Q** control appears only at 12 dB/oct. See
[Low Pass and High Pass](#low-pass-and-high-pass) for what each one sounds like.

#### Channel

**Both** (default), **Left** or **Right**. See [Left, Right or Both](#left-right-or-both).

### Does the order of filters matter?

No. Every filter here is linear, so their effects add up (in dB) in any order: two +3 dB peaks at
the same frequency and Q make one +6 dB peak. The numbers are just labels for the handles. Arrange
the list however reads best to you.

### Graphic bands and filters together

They're simply added together, and the curve shows the total. Two sensible ways to use both:

- **Filters for correction, bands for taste.** Put a headphone correction (AutoEQ) in the filters,
  then use the bands for your personal seasoning on top: "neutral, plus 2 dB of bass".
- **Bands only, or filters only.** Keep the bands flat and do everything parametrically. That's
  the most precise approach, and exports cleanly to APO.

---

## 7. The filter types in depth

### Peak

**What it is:** a bell-shaped boost or cut centred on one frequency, leaving everything far from it
alone. It's the same shape as a graphic band, but you set the frequency, gain and width.

**Parameters:** frequency (the centre), gain (−24 to +24 dB), Q (the width).

**What gain does:** sets the height (or depth) at the centre. Boosts and cuts are exact mirror
images, so a +6 then a −6 at the same settings cancel out perfectly.

![Peak filter: what gain does](eq/peak-gain.svg)

**What Q does:** sets the width. At low Q the bell spreads across several octaves; at high Q it's a
spike.

![Peak filter: what Q does](eq/peak-q.svg)

How much of a +6 dB peak at 1 kHz is left as you move away from the centre:

| Q | ⅙ oct away | ⅓ oct | ½ oct | 1 oct | 2 oct |
|---|---|---|---|---|---|
| 0.5 | +5.9 | +5.7 | +5.3 | +3.8 | +1.3 |
| 0.71 | +5.8 | +5.4 | +4.7 | +2.8 | +0.7 |
| 1.41 | +5.4 | +4.1 | +3.0 | +1.1 | +0.2 |
| 2.87 | +4.1 | +2.2 | +1.2 | +0.3 | +0.1 |
| 4.32 | +3.0 | +1.2 | +0.6 | +0.1 | 0 |
| 10 | +1.0 | +0.3 | +0.1 | 0 | 0 |

**When to use it:** almost every tonal fix. A broad boost for presence (Q 0.7 to 1.4), a narrow cut
for a resonance (Q 4 to 10), a medium cut for harshness (Q 2 to 3).

**Rules of thumb:**
- **Boost wide, cut narrow.** Wide boosts sound natural. Narrow boosts sound like ringing, because
  they *are* ringing.
- **Small gains at high Q are almost inaudible.** A +2 dB peak at Q 10 changes very little; narrow
  filters need depth to matter.
- **A peak at 0 dB does nothing.** Use that to park it.

### Low Shelf and High Shelf

**What they are:** a step. A **low shelf** raises or lowers *everything below* its frequency by the
gain; a **high shelf** does the same to *everything above*. Unlike a peak, the effect doesn't come
back to 0 at the far end: the whole region shifts.

**Parameters:** frequency (the step's midpoint), gain, Q (the shape of the corner).

**What the frequency means:** it's where the shelf is **halfway there**. A +6 dB low shelf at 100 Hz
is +3 dB at 100 Hz, reaches the full +6 dB about two octaves lower, and fades to nothing about
two octaves higher. Pick the frequency where you want the change to be "half on".

![High shelf: three corner frequencies](eq/high-shelf.svg)

**What Q does:** shapes the corner.

- **0.71 is the standard:** the smoothest, steepest step with no bumps.
- **Below 0.71** (0.3 to 0.5) the step is gentler and spread across more octaves. Good for a broad tilt.
- **Above 0.71** the shelf **overshoots**: a bump just before the step and a dip just after it. At
  Q 1.41, a +6 dB low shelf at 100 Hz peaks at +7.3 dB around 70 Hz and dips to −1.3 dB around
  140 Hz. That can sound punchier and more "produced", but it's no longer a clean shelf.

![Low shelf: what Q does](eq/shelf-q.svg)

| Low shelf +6 dB at 100 Hz | 25 Hz | 50 Hz | 71 Hz | 100 Hz | 141 Hz | 200 Hz | 400 Hz |
|---|---|---|---|---|---|---|---|
| Q 0.5 | +5.6 | +4.8 | +4.0 | +3.0 | +2.0 | +1.2 | +0.4 |
| Q 0.71 | +6.0 | +5.6 | +4.8 | +3.0 | +1.2 | +0.4 | 0 |
| Q 1.0 | +6.2 | +6.4 | +5.9 | +3.0 | +0.1 | −0.4 | −0.2 |
| Q 1.41 | +6.3 | +7.1 | +7.3 | +3.0 | −1.3 | −1.1 | −0.3 |
| Q 2.0 | +6.3 | +7.5 | +8.8 | +3.0 | −2.8 | −1.5 | −0.3 |

**When to use a shelf:**
- **Overall bass level** (low shelf at 80 to 150 Hz). The cleanest way to say "more bass" or "less
  bass" without picking a note.
- **Overall brightness** (high shelf at 3 to 10 kHz). Tames bright headphones, or lifts dull ones.
- **Tilting the whole balance:** a low shelf down plus a high shelf up (or the reverse) at the same
  frequency tilts the spectrum around it.

**Shelf or peak for more bass?** A shelf lifts everything below its frequency, including sub-bass
that small drivers can't play. A peak at 50 to 80 Hz lifts the punch and leaves the deep sub alone.
Big headphones and subwoofers suit a shelf; earbuds and small speakers often suit a peak.

![Shelf or peak for more bass?](eq/shelf-vs-peak.svg)

### Low Pass and High Pass

**What they are:** cutoffs. A **high pass** (also called a low cut) lets the highs through and
removes everything *below* its frequency. A **low pass** (high cut) does the opposite. They have
no gain: the passband stays at 0 dB and the stopband falls away for good.

**Parameters:** frequency (the cutoff), slope, and Q at 12 dB/oct.

**What the frequency means:** the point where the level is down 3 dB. Above it (for a high pass)
the music is essentially untouched; below it, it falls away at the chosen slope.

**What the slope does:** sets how quickly the level falls past the cutoff.

![High pass: the five slopes](eq/pass-slopes.svg)

| High pass at 100 Hz | 200 Hz (1 oct above) | 141 Hz (½ oct above) | 100 Hz | 50 Hz (1 oct below) | 25 Hz (2 oct below) |
|---|---|---|---|---|---|
| 6 dB/oct | −1.0 | −1.8 | −3 | −7.0 | −12.3 |
| 12 dB/oct | −0.3 | −1.0 | −3 | −12.3 | −24.1 |
| 24 dB/oct | 0 | −0.3 | −3 | −24.1 | −48.2 |
| 36 dB/oct | 0 | −0.1 | −3 | −36.1 | −72.2 |
| 48 dB/oct | 0 | 0 | −3 | −48.2 | −96.3 |

| Slope | Character | Use it for |
|---|---|---|
| **6 dB/oct** | Very gentle, a slow fade with a long reach into the passband | A tilt rather than a cut; softly darkening (low pass) or thinning (high pass). No Q control |
| **12 dB/oct** | Classic, gentle, the only slope with a **Q** control | General use; resonant synth-style filters (with high Q) |
| **24 dB/oct** | Firm: flat right up to the cutoff, then a clean drop | **The everyday choice** for removing rumble or protecting small speakers. The default for a new High Pass |
| **36 dB/oct** | Steep | Tighter protection when the cutoff must sit close to wanted content |
| **48 dB/oct** | Brick wall | Hard limits: "absolutely nothing below 40 Hz goes to this speaker" |

Slopes steeper than 12 dB/oct are **Butterworth** filters, the flattest possible passband for their
steepness, so they have no Q. (The page's footer says so when you pick one.)

**What Q does (12 dB/oct only):** shapes the knee at the cutoff.

| Q | At the cutoff | Shape |
|---|---|---|
| 0.5 | −6 dB | Soft, rounded knee that starts early |
| 0.71 | −3 dB | Flat to the corner (Butterworth). **The default and usually right** |
| 1.0 | 0 dB | A slight +1.2 dB bump just before the corner |
| 1.41 | +3 dB | A clear resonant bump (+3.6 dB max) |
| 2 | +6 dB | Strongly resonant |
| 5 | +14 dB | A whistling resonance: an effect, not a correction |

![Low pass at 12 dB/oct: what Q does](eq/pass-q.svg)

**When to use them:**
- **High pass at 20 to 40 Hz, 24 dB/oct:** removes inaudible rumble that wastes headroom and makes
  drivers flap. Safe on nearly everything.
- **High pass at 80 to 200 Hz:** protects phone speakers, laptop speakers and tiny Bluetooth
  speakers from bass they can't play, which reduces distortion and rattling.
- **Low pass at 8 to 12 kHz:** tames hiss on old tapes and vinyl rips.
- **Low pass at 3 to 5 kHz** (with a high pass at 300 Hz): the "telephone" or "radio in the next
  room" effect.

**Careful:** a high pass costs real bass. Too high a cutoff makes music thin. Start low and raise
it until the problem stops, then stop.

### Band Pass

**What it is:** keeps a band around its frequency at full level and rolls everything else off on
both sides. It's a high pass and a low pass at once. It **never boosts**: the centre stays at 0 dB.

**Parameters:** frequency (the centre), Q (how narrow the kept band is).

![Band pass: what Q does](eq/band-pass.svg)

| Band pass at 1 kHz | ½ oct away | 1 oct away | 2 oct away | 3 oct away |
|---|---|---|---|---|
| Q 0.5 | −0.5 | −1.9 | −6.6 | −12.2 |
| Q 0.71 | −1.0 | −3.3 | −9.1 | −15.1 |
| Q 1.41 | −3.0 | −7.4 | −14.6 | −21.0 |
| Q 4 | −9.6 | −15.7 | −23.6 | −30.0 |
| Q 10 | −17.1 | −23.6 | −31.5 | −37.9 |

**When to use it:**
- **Effects:** telephone voice (around 1.5 kHz, Q 0.7), megaphone, "AM radio".
- **Listening into one region:** isolate 2 to 4 kHz to hear how harsh a recording really is, or
  sweep a narrow band pass to find a resonance by ear.
- *Not* for everyday listening: it removes most of the music.

### Notch

**What it is:** a very deep, narrow cut that removes one frequency entirely, theoretically to
silence, while leaving the rest essentially untouched.

**Parameters:** frequency (what to remove), Q (how much around it is affected). It has no gain:
the depth is always "all of it".

**What Q does:** the width of the notch at −3 dB is roughly *frequency ÷ Q*. At 60 Hz, Q 4 is a
15 Hz wide notch; Q 20 is 3 Hz.

![Notch: what Q does](eq/notch.svg)

| Notch at 60 Hz | −3 dB width | Level at 55 Hz | Level at 50 Hz | Level at 120 Hz |
|---|---|---|---|---|
| Q 1 | 60 Hz | −15.3 | −9.3 | −1.6 |
| Q 2 | 30 Hz | −9.7 | −4.6 | −0.5 |
| Q 4 (default) | 15 Hz | −4.9 | −1.7 | −0.1 |
| Q 10 | 6 Hz | −1.2 | −0.3 | 0 |
| Q 20 | 3 Hz | −0.3 | −0.1 | 0 |

**When to use it:**
- **Mains hum** on live recordings, old vinyl rips and home recordings: 60 Hz in the Americas,
  50 Hz in Europe and most of the rest of the world, plus its harmonics (120/180 Hz or 100/150 Hz).
- **A single whistle or tone**: a CRT whine (15.7 kHz), feedback ringing, a squeaky pedal.
- **Use the highest Q that still kills the problem**, so the least music is touched.

**Not for tone shaping:** a notch is all-or-nothing. For "a bit less of this region", use a Peak
cut.

### All Pass

**What it is:** a filter that changes **phase** (timing) around its frequency without changing
**level**. The curve stays flat at 0 dB, and the page says so: "An all-pass changes phase around
its frequency, not level, so the curve stays flat."

**Parameters:** frequency (where the phase shift is greatest, 180°), Q (how quickly the phase turns
around that point).

**When to use it:** almost never. It exists because Equalizer APO has it and some imported
configs contain one. On both channels it's practically inaudible. On **one** channel it smears
the stereo image and sounds phasey, which is occasionally useful as an effect, but rarely what you
want.

### Left, Right or Both

Every filter can apply to **Both** channels (default), only **Left**, or only **Right**.

![A filter on one channel](eq/channels.svg)

As soon as any filter is single-channel, the curves draw the right channel separately in
**orange**, and right-only handles turn orange.

**When to use it:**
- **Headphones whose sides don't match.** Some AutoEQ results and some measurement rigs give
  per-side corrections.
- **Hearing that differs between ears.** A high shelf on the weaker side, raised until a centred
  vocal sounds centred again.
- **Hard-panned stereo** (many 1960s records put the drums on one side and the vocals on the other)
  where one side is harsh or boomy.
- **A single-sided speaker problem**, like a car door speaker with a resonance the other side
  doesn't have.

**Careful:** channel-specific EQ shifts the stereo image. A high-frequency boost on one side pulls
centred sounds toward it. That's the point when correcting an imbalance, and a side effect
otherwise. Anti-clipping uses whichever channel peaks higher.

---

## 8. Gain staging: preamp, anti-clipping, the limiter and volume

This is the section that explains why the EQ sometimes makes things quieter, and how to get the
most level without distortion.

### The problem

Digital audio has a ceiling: **0 dBFS** (full scale). Almost all modern music is mastered to peak
right at it. Boost 62 Hz by 6 dB and every bass peak wants to go 6 dB past the ceiling. It can't,
so it's either **clipped** (flattened, which makes harsh, crackly distortion) or something has to
turn it down.

The fix is to **turn the signal down before the boost**, by as much as the boost, so the boosted
frequencies come back up to the ceiling and no further. That's a **preamp**.

### The two preamps

Stemify has two, applied together at the very start of the EQ:

**1. The manual preamp** (Filters page, −24 to +24 dB)
- Part of each profile, like Equalizer APO's `Preamp:` line.
- AutoEQ imports set it for you.
- You'd set it yourself to turn a profile down by a fixed amount, to match levels between
  profiles, or when anti-clipping is off.

**2. The anti-clipping preamp** (the switch on the main screen, global)
- Calculated from the curve: the highest point of the combined response, on whichever channel is
  higher, **with the manual preamp included**.
- Cuts by exactly enough that a full-scale signal at that peak frequency still peaks at 0 dBFS.
- **Only ever cuts.** If the curve is all cuts, or the manual preamp is already low enough, it adds
  nothing.
- Recalculated live as you edit.

Where you see them:
- The main screen's **Preamp** readout shows the **total**.
- The Filters page shows your manual preamp on the slider, and the sum underneath.
- The curves' dashed line is the EQ shape after the total preamp.

### Worked examples (anti-clipping on)

| Settings | Curve peak | Manual | Anti-clip adds | Total | What happens |
|---|---|---|---|---|---|
| Flat | 0 | 0 | 0 | off | Nothing; the EQ is bypassed |
| 1 kHz band +6 | +6 | 0 | −6 | −6 | 1 kHz is where it was; everything else is 6 dB quieter |
| 500 Hz + 1 kHz bands +6 | +7.2 | 0 | −7.2 | −7.2 | Overlap makes the peak (and the cut) bigger than either slider |
| Bass Booster | +6.5 | 0 | −6.5 | −6.5 | See the graph below |
| Treble Reducer | 0 | 0 | 0 | off | Cuts only: no preamp needed, no level lost |
| 1 kHz band +6 | +6 | −6 | 0 | −6 | Your manual preamp already covers it |
| 1 kHz band +6 | +6 | −3 | −3 | −6 | Anti-clip tops it up |
| 1 kHz band +6 | +6 | **+4** | −10 | −6 | A positive manual preamp is cancelled out |
| AutoEQ file, peak +5.8, preamp −6.2 | +5.8 | −6.2 | 0 | −6.2 | AutoEQ's own preamp is enough; no double cut |

![What anti-clipping does](eq/anti-clip.svg)

### Why the EQ makes things quieter, and why that's right

Look at the orange line in the graph: with Bass Booster, the bass stays where it was and
**everything else goes down 6.5 dB.** At full scale, "more bass relative to the rest" can only
mean "less of the rest". The tonal balance is exactly what you asked for; it's just quieter.

**So turn the volume up.** Use your phone's volume buttons; the app's own master volume tops out at
unity (100% means no change), so it can't add level.

### Anti-clipping is the worst case

It assumes a full-scale sine wave exactly at your curve's highest frequency. Real music rarely puts
peak energy precisely there, so the cut is often more than strictly necessary. That's the price of
a guarantee. If you understand the trade-off, you can claw some level back:

### Turning anti-clipping off

With anti-clipping **off**, only your manual preamp applies, and the **limiter** is all that stands
between a boost and clipping.

**The limiter** sits at the very end of the chain, after the master volume, on every song whatever
the EQ is doing:
- **Ceiling:** about −0.13 dBFS, just under full scale.
- **Attack:** instant. A peak never gets through, so the output can never clip.
- **Release:** about 120 ms. After it turns a peak down, it lets go smoothly over roughly an eighth
  of a second.

When it's working occasionally you can't hear it. When it's working constantly you can: on every
kick drum the whole mix ducks and swells back, which is **pumping**, and dense music sounds
squashed and lifeless.

| Setup | Result |
|---|---|
| Anti-clipping **on** (default) | Clean. Quieter by the size of your biggest boost. The limiter only catches fader boosts |
| Off, boosts of 1 to 3 dB | Louder, and the limiter shaves the odd peak inaudibly. A reasonable choice for gentle curves |
| Off, boosts of 6 dB or more | Louder and pumping, especially with bass boosts. Not recommended |
| Off, with a **manual preamp** of, say, −3 for a +6 curve | A middle ground: 3 dB more level than anti-clip, and only the biggest peaks are limited |

### The stem faders are part of this too

Each stem fader tops out at about **+3.9 dB** (unity is the detent at 80%). Pushing several stems up
can take the *mix* past full scale before it even reaches the EQ, and anti-clipping only knows
about the EQ curve. The limiter catches it. If a pushed-up mix sounds squashed, pull the other
faders down instead of pushing one up. It's the same idea as "cut instead of boost".

### Matching levels between profiles

When switching profiles (say, "Flat" and "Rock"), anti-clipping makes the one with bigger boosts
quieter. That's fair for comparison, but annoying if you switch casually. To make them land at about
the same loudness, turn the *louder* one down: give Flat a manual preamp of around −5 dB so it sits
near Rock's −5.9 dB anti-clip cut.

---

## 9. Profiles and devices

### What's in a profile

A **profile** is a complete EQ setup:
- the ten band gains,
- the parametric filters (each with its on/off state),
- the manual preamp,
- the devices it's linked to.

The Equalizer and Anti-Clipping switches and the Automatic/Manual choice are **global**, not per
profile.

**Out of the box** there are two: **Flat** (the default profile) and **Small Speaker Tune** (the Small
Speakers preset), linked to the phone speaker.

### How the active profile is chosen

```
Manual mode, and you picked one?  ──yes──▶  that profile
        │ no
        ▼
A profile linked to the current output?  ──yes──▶  that profile
        │ no
        ▼
The default profile
```

The output is re-checked whenever Android reports a change: headphones plugged or unplugged,
Bluetooth connecting, the car taking over. The switch happens mid-song, instantly.

### How devices are told apart

- **Phone speaker:** one entry for the built-in speaker and earpiece, whatever the phone.
- **Everything else is identified by its type plus the product name Android reports.** Your earbuds
  and your Bluetooth speaker are separate devices. So are a USB DAC and a USB-C headset.
- **Two identical devices with the same name count as one.** Two pairs of the same earbuds share a
  profile, which is usually what you want.
- **Wired headphones** through the jack or a generic USB-C dongle usually show as "Wired
  Headphones". Headsets that report their own name show that instead.
- **The same product on two connection types is two devices.** A headset used over Bluetooth and
  over USB gets two entries, so link the same profile to both if you want.

Each **device** has at most **one** linked profile. A **profile** can be linked to **many**
devices, for example one "IEMs" profile for your wired and USB in-ears.

### Every profile action

| Action | Where | What it does |
|---|---|---|
| **Select** | Profile → tap a name | Makes it active, and switches to **Manual** |
| **New Profile…** | Profile sheet | Asks for a name (blank becomes "Profile"). Copies the **current** bands, filters and preamp. **Links it to the current device.** Doesn't change the mode |
| **Use for <device>** | Manage menu | Links this profile to the current output (unlinking it from any other profile) |
| **Unlink from <device>** | Manage menu | The device falls back to the default profile |
| **Link from the Output row** | Main screen → Output | The same as Use for, choosing the profile from a list. **Use Default** unlinks |
| **Make Default** | Manage menu | This profile plays on any device without a link. Only shown when it isn't already the default |
| **Rename…** | Manage menu | Renames it. A blank name keeps the old one |
| **Duplicate** | Manage menu | Copies bands, filters and preamp as "<name> Copy". **Not** linked to anything and not selected, so it's a safe backup |
| **Delete Profile** | Manage menu | Deletes it, after a confirmation. Its device links go with it. Deleting the default makes the first remaining profile the default. You can't delete the last profile |

The Manage menu is **Profile → Manage "<name>"…**, or hold the Profile row. It always acts on the
**active** profile.

### Common workflows

**A profile for new headphones**
1. Connect them and start a song.
2. In **Automatic** mode, tap **Profile → New Profile…** and name it after them. It copies the
   current sound and is linked and active straight away.
3. To start from scratch instead, see the next workflow.

**A profile that starts flat** (for an AutoEQ import, or a clean slate)
1. Connect the device.
2. **Profile → Flat** (this switches to Manual).
3. **Profile → New Profile…** and name it. It's a flat copy, linked to the device.
4. Tap **Automatic**. The new profile is linked, so it becomes the active one. Check the editor's
   header shows the new name.

**A situational profile** ("Karaoke", "Late Night")
1. Build it, and don't link it to anything: **Duplicate** an existing profile, or create one and
   then **Unlink** it.
2. When you want it, pick it from **Profile** (which switches to Manual).
3. When you're done, tap **Automatic**.

**Editing a profile for a device that isn't connected**
1. Pick it from **Profile** (Manual).
2. Edit away; the editor header shows its name with "(manual)".
3. Tap **Automatic** to return.

**Trying a preset without losing your work**
1. Manage → **Duplicate**, then pick the copy.
2. Load presets into the copy. Keep it, or delete it.

---

## 10. AutoEQ and Equalizer APO

### What AutoEQ is

[AutoEQ](https://github.com/jaakkopasanen/AutoEq) has measured frequency-response corrections for
thousands of headphones and IEMs. Each one is a handful of parametric filters that nudge that
model toward a neutral target curve, published in the
[Equalizer APO](https://sourceforge.net/projects/equalizerapo/) text format that Stemify reads.

A correction looks something like this: a few broad moves for the overall balance, a couple of
narrow ones for specific resonances, and a negative preamp to make room for the boosts.

![What an AutoEQ correction looks like](eq/autoeq.svg)

### Loading a correction

1. **Find your model.** Use the [AutoEQ web app](https://autoeq.app) (choose **Equalizer APO** as the
   equalizer app) or the GitHub repo's `results` folder. Pick the measurement source and target
   you like; the defaults are fine.
2. **Copy the parametric EQ text** (the `ParametricEQ.txt` contents) to your phone's clipboard. It
   starts with `Preamp:` and has `Filter 1:`, `Filter 2:`... lines.
3. **Make a flat profile for the headphones:** see
   [A profile that starts flat](#common-workflows). This matters, because the import doesn't touch
   the ten bands and anything on them would stack on top.
4. **Parametric Filters → ••• → Paste APO Config.** A confirmation tells you what it found ("Loads 10
   filters and preamp −6.2 dB into “Galaxy Buds”, replacing its 0 filters") and how many lines it
   will skip.
5. Tap **Replace**. A "Imported …" message confirms it.
6. **Listen, then season to taste with the bands.** A neutral target is a reference, not a law: plenty
   of people prefer it with +2 to +4 dB of bass and a touch less treble.

### What the importer understands

| Line | Example | Imported as |
|---|---|---|
| `Preamp:` | `Preamp: -6.2 dB` | The manual preamp. Several Preamp lines add up, as in APO |
| `Filter N:` | `Filter 1: ON PK Fc 2300 Hz Gain -3.1 dB Q 1.41` | One filter. `ON`/`OFF` sets its switch; `OFF` filters come in switched off, not thrown away |
| `Fc` | `Fc 105 Hz` | Frequency. Required: a filter without one is skipped |
| `Gain` | `Gain 6.0 dB` | Gain, for peaks and shelves (ignored for other types) |
| `Q` | `Q 0.70` | Q |
| `BW Oct` | `BW Oct 1.0` | Bandwidth in octaves, converted to Q |
| Slope | `LP 24dB Fc 80 Hz`, `HPQ 12dB …` | Slope for low/high pass, 6 to 48 dB/oct |
| `Channel:` | `Channel: L`, `Channel: R`, `Channel: all` | Filters after it apply to that channel. `L R` together, or any other channel name (surround channels), counts as Both |
| `#` | `# Sennheiser HD 600` | Comment; everything after `#` on a line is ignored |

Filter type codes:

| Code(s) | Stemify type |
|---|---|
| `PK`, `PEQ`, `Modal` | Peak |
| `LSC`, `LS`, `LSQ` | Low Shelf |
| `HSC`, `HS`, `HSQ` | High Shelf |
| `LPQ`, `LP` | Low Pass |
| `HPQ`, `HP` | High Pass |
| `BP` | Band Pass |
| `NO` | Notch |
| `AP` | All Pass |

Defaults when a value is missing: Q 1.41 for peaks and 0.71 for everything else; slope 12 dB/oct.

**Skipped:** `GraphicEQ:`, `Delay:`, `Copy:`, `Include:`, `Device:`, `Stage:`, `Eval:` and anything
else that isn't Preamp, Channel or Filter. Shelf slopes given in dB (APO's `LS 6dB` variable-slope
shelves) import as standard shelves with Q 0.71.

**Nothing usable on the clipboard?** You get "No Equalizer APO filters on the clipboard" and nothing
changes.

**What gets replaced:** the profile's **parametric filters** (all of them) and, if the text has a
`Preamp:` line, the **manual preamp**. The **ten bands are not touched.**

### Exporting to Equalizer APO

**Parametric Filters → ••• → Copy as APO Config** puts the profile on the clipboard:

```
# Galaxy Buds, from Stemify
Preamp: -6.2 dB
Filter 1: ON PK Fc 62.5 Hz Gain 2 dB Q 1.41
Filter 2: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.71
Filter 3: ON PK Fc 2900 Hz Gain 3.5 dB Q 2.2
...
```

- The **manual preamp** comes first. **Anti-clipping is a Stemify feature and isn't exported.**
  If you rely on it, set APO's preamp to the *total* shown in Stemify.
- **Non-zero graphic bands come next, as Peak filters** at their centre frequencies with Q 1.41.
- **Then the parametric filters**, with `Channel:` lines inserted wherever the channel changes.
- **Off filters** are exported as `OFF`.
- **6 dB/oct passes** become a gentle 12 dB/oct filter with Q 0.5, since APO has no first-order
  filter. A comment line says so.
- **24, 36 and 48 dB/oct passes** become 2, 3 or 4 stacked APO filters with Butterworth Q values,
  which is the same response.

Paste it into Equalizer APO's `config.txt`, or into Peace or any other tool that reads APO configs.

> **Careful with round trips.** If you export a profile and paste it straight back into Stemify,
> the graphic bands come back as *parametric* Peak filters, while the profile's real bands are
> still there. Every band then counts twice. After pasting, load **Flat** into the bands.

---

## 11. How to EQ by ear

A method that works, whether you're tuning headphones or fixing a recording.

### Set up

1. **Pick three reference songs you know inside out**, ideally different styles: one bass-heavy,
   one vocal-led and acoustic, one dense and loud.
2. **Use the device you'll actually listen on**, at your normal volume.
3. **Start from Flat** (or a duplicate of your current profile).

### The loop

1. **Name the problem in words first.** "Boomy", "harsh on loud parts", "vocals far away", "thin".
   The frequency table in [section 1](#what-lives-where) turns words into regions.
2. **Make one move.** Usually a cut, usually 2 to 3 dB.
3. **Toggle it.** Use that filter's switch, or the Equalizer switch, and listen a few times.
4. **Keep it, adjust it, or undo it.** If you can't tell the difference, undo it.
5. **Next problem.** Stop after three or four moves and listen to a full song.

### Finding a frequency: the sweep

When you can hear a problem but can't place it (a ring, a harshness, a boom on one note):

1. Add a **Peak** filter, **+8 to +10 dB, Q 6 to 8**.
2. **Drag it slowly sideways** across the region you suspect, while the problem plays.
3. When the problem suddenly gets **much worse**, you've found it.
4. **Flip the gain negative** (−3 to −6 dB) and **lower the Q** (2 to 4) until the problem stops
   bothering you and the music around it still sounds natural.

![Boost to find, cut to fix](eq/recipe-resonance.svg)

### Habits

- **Small moves.** Over ±6 dB is rarely the answer outside corrections.
- **Cut more than you boost.** Cuts fix problems, cost no headroom, and age better.
- **Match loudness when comparing.** A setting that's only "better" because it's louder isn't better.
- **Take breaks.** Ears adapt within minutes. What sounded bright at first can sound normal ten minutes
  later, so judge after a pause.
- **Judge across songs.** A curve that fixes one song often breaks another. Profiles are for
  *systems* (headphones, rooms); a song-specific fix belongs in a situational profile.
- **Remember the stems.** If the fix is "less of one instrument", use its fader. Use the EQ for what
  the faders can't do.

---

## 12. Recipes

Each recipe shows the curve before and after, with the exact settings. "Peak 2.5 kHz, +2, Q 0.7"
means: add a Peak filter, frequency 2.5 kHz, gain +2 dB, Q 0.7.

### Just make my earbuds sound better

1. Connect them. Make a flat profile for them ([workflow](#common-workflows)).
2. Paste their AutoEQ `ParametricEQ.txt` (Parametric Filters → ••• → Paste APO Config).
3. Listen for a day. Then add taste on the ten bands: +2 at 62 Hz for more bass, −1 to −2 at 8 kHz
   if it's a little bright.

Anti-clipping covers everything; AutoEQ's own preamp usually means it adds little or nothing.

### Phone speaker that doesn't distort

A phone speaker can't play much below about 200 Hz. Boosting there only wastes headroom and makes it
rattle.

- **High Pass 150 Hz, 24 dB/oct**, which removes what it can't play anyway.
- **Peak 1.5 kHz, +2, Q 0.71**, for broad clarity where small speakers are strongest.
- Leave the bands **Flat**, or set **31 and 62 Hz** to 0 if you start from the Small Speakers preset.

Strangely, it usually sounds *louder* and fuller: the speaker stops wasting effort on bass it
can't play. Anti-clip only needs −2.0 dB.

![Recipe: phone speaker](eq/recipe-speaker.svg)

### Karaoke that doesn't sound like a hole in the mix

Muting the vocal stem leaves a gap in the 1 to 4 kHz region. Fill it with the instruments:

- Vocal stem **muted** (Now Playing → mixer).
- **Peak 2.5 kHz, +2, Q 0.7**, a broad lift that brings guitars and keys forward.
- Keep it as an unlinked **"Karaoke"** profile, pick it by hand while you sing, then tap Automatic.

![Recipe: karaoke](eq/recipe-karaoke.svg)

### Learning a bass line

- **Solo the bass stem**, or pull the others down.
- **High Shelf 1 kHz, +4, Q 0.71**: the finger and pick attack lives up there, and lifting it makes
  rhythm and fingering much easier to follow.
- **High Pass 40 Hz, 24 dB/oct**: removes sub-boom that blurs the notes.

The same idea works for other instruments: solo the stem, lift its attack region
([anchor table](#what-lives-where)), and cut its boom.

![Recipe: learning a bass line](eq/recipe-bassline.svg)

### Late-night listening without waking anyone

At low volume your ears lose the extremes first. Either:
- load **Late Night** (the blue curve), or
- use two filters: **Low Shelf 100 Hz, +4, Q 0.71** and **High Shelf 10 kHz, +3, Q 0.71** (orange).
  This version is smoother and costs less headroom.

![Recipe: late night](eq/recipe-late-night.svg)

### Mains hum on an old live recording

- **Notch 60 Hz, Q 10**, **Notch 120 Hz, Q 10**, **Notch 180 Hz, Q 10**.
- Where the mains is 50 Hz: **50, 100 and 150 Hz**.
- Raise the Q until the hum just stays gone. Higher Q touches less music.
- Keep it in a situational profile; you don't want this on everything.

![Recipe: mains hum](eq/recipe-hum.svg)

### Harsh, fatiguing headphones

- **Peak 3 to 4 kHz, −3, Q 2**. Sweep it (see [section 11](#finding-a-frequency-the-sweep)) to
  find your headphones' exact peak.
- If cymbals are splashy too: **Peak 6 to 8 kHz, −3, Q 4**.
- Or, more broadly: **High Shelf 4 kHz, −2, Q 0.71**.

### Muddy, congested sound (cars and small rooms)

- **Peak 250 Hz, −3, Q 1.4** clears the mud.
- **Peak 125 Hz, −2, Q 1** if it's boomy too.
- Small rooms and cars have strong resonances. If one bass note booms, find it with the sweep and
  cut it narrow (Q 4 to 8).

### Bass that's big but not boomy

- **Peak 60 Hz, +4, Q 1.41** for punch.
- **Peak 200 Hz, −2, Q 1.41** to keep the low mids clean.
- **High Pass 25 Hz, 24 dB/oct** so the boost doesn't waste headroom on rumble.

### Spitty vocals (sibilance)

- Sweep between 5 and 9 kHz with a narrow boost until the *s* sounds jump out.
- Settle on **Peak ~7 kHz, −3 to −5, Q 4**.
- A broad high shelf would also dull the cymbals. The narrow cut targets just the esses.

### Telephone / old radio effect

- **High Pass 300 Hz, 24 dB/oct**
- **Low Pass 3.4 kHz, 24 dB/oct**
- Optional: **Peak 1.5 kHz, +4, Q 1** for that nasal honk.

### One ear hears less treble

- **High Shelf 4 kHz, +3, Channel: Left** (or Right).
- Play a mono or centre-panned vocal and adjust until it sounds centred.

![A filter on one channel](eq/channels.svg)

---

## 13. Troubleshooting and FAQ

**Nothing changes when I move things.**
Check that the **Equalizer** switch is on. Check the editor's header: is the profile you're
editing the active one? The Now Playing output label (`device · profile`) shows what's playing right
now. A Peak or Shelf at 0 dB, or a filter that's switched off, does nothing.

**My profile keeps switching back.**
You're in **Automatic**, so the current device's linked profile wins. Either link the profile you
want to this device (tap the **Output** row), or use **Manual**.

**My edits went into the wrong profile.**
Bands, presets, filters and imports always edit the **active** profile, the one in the editor's
header. Picking Flat to start a new profile switches to Manual, so a profile created right after
isn't active until you tap **Automatic**.

**It's quieter with the EQ on.**
That's anti-clipping doing its job ([section 8](#why-the-eq-makes-things-quieter-and-why-thats-right)).
Raise the phone volume. Or reduce your biggest boost, or replace it with a cut elsewhere.

**It pumps, ducks or sounds squashed.**
The limiter is working hard: anti-clipping is off with big boosts, or stem faders are pushed well
above unity. Turn anti-clipping on, reduce boosts, or pull faders down instead of up.

**It crackles or distorts.**
The app's output can't clip, so the distortion is downstream: a speaker or headphone driver being
asked for bass it can't play. Cut the deep bass (High Pass 30 to 150 Hz depending on the device) and
reduce bass boosts.

**My AutoEQ correction sounds wrong or too bassy.**
The ten bands are probably not flat and are stacking on top. Load **Flat** into the profile; it only
resets the bands. Also check you picked the right model, and the right measurement for your ear tips
or pads.

**Paste APO Config says there's nothing on the clipboard.**
Copy the text itself, not a link to it, and make sure it has `Filter` or `Preamp` lines.

**My headphones show up as a different device than before.**
Devices are identified by connection type and name. The same headphones over Bluetooth and over a
cable are two devices. Link the same profile to each.

**Can I EQ just one stem?**
No: the EQ works on the whole mix. Use the stem faders for per-instrument balance. Combining a soloed
stem with the EQ gets you very close to per-stem EQ for practice.

**Does the EQ affect downloads or the files on the server?**
No. It's applied live at playback. Files are never changed.

**Does it work in Android Auto?**
Yes. The car is just another output, so link a profile to it like any other device.

**Is there an undo?**
No. Every change saves immediately. Use **Duplicate** before experimenting, and **Copy as APO
Config** to keep a text backup of a profile you've perfected.

**Can I share a profile?**
Yes. **Copy as APO Config** gives you text to send; the other person uses **Paste APO Config**. Remember
the bands come across as filters, so they should paste into a profile with flat bands.

---

## Reference card

| Control | Range | Default | Notes |
|---|---|---|---|
| Graphic band gain | −12 to +12 dB | 0 | Snaps to 0; double-tap resets |
| Graphic band frequencies | 31.25, 62.5, 125, 250, 500 Hz, 1, 2, 4, 8, 16 kHz | | Peak filters, Q 1.41 |
| Filter frequency | 10 Hz to 22 kHz | Per type | Log slider; tap to type |
| Filter gain | −24 to +24 dB | Per type | Peak and shelves only; 0.1 dB steps |
| Filter Q | 0.1 to 20 | Per type | Log slider; hidden for 6/24/36/48 dB/oct passes |
| Pass slope | 6, 12, 24, 36, 48 dB/oct | 12 (24 for a new High Pass) | 24 and up are Butterworth |
| Filter channel | Both, Left, Right | Both | Right drawn in orange |
| Manual preamp | −24 to +24 dB | 0 | Per profile, Filters page |
| Anti-clipping | On / Off | On | Global; cancels positive manual preamp |
| Limiter | ceiling −0.13 dBFS | Always on | Instant attack, ~120 ms release |
| Stem fader | silent to +3.9 dB | Unity at 80% | Before the EQ |
| Master volume | 0 to 100% | | After the EQ; 100% = unity, never boosts |
| Profiles | Unlimited | Flat (default), Small Speaker Tune (phone speaker) | One profile per device; many devices per profile |
| Mode | Automatic, Manual | Automatic | Picking a profile switches to Manual |

| Q | Bandwidth | Q | Bandwidth |
|---|---|---|---|
| 0.5 | 2.5 oct | 2.87 | ½ oct |
| 0.71 | 1.9 oct | 4.32 | ⅓ oct |
| 1.0 | 1.4 oct | 6 | ¼ oct |
| 1.41 | 1 oct | 10 | ⅐ oct |
