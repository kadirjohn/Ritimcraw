# Timeline audio plan — placed PCM, the sample-grid mapping, and the stereo mix

C1, night research 2026-11-16. This document is the plan half of the
C1 package: the design for timeline audio — how a placed clip's audio
maps onto the timeline's sample grid, the per-clip and master audio
state, the bound-vs-independent shapes, and the contract that carries
the mix to the export's AAC leg. The model committed alongside it is
`app/src/main/java/com/ritim/ritimcraw/data/timeline/AudioPlan.kt`
(+ `AudioPlanTest.kt`, 35 host tests). The model is implemented and
host-tested; everything in THIS document beyond §1 is a proposal for
the integrator — review-pending, the owner reads it. No timeline UI or
export integration is in this package (the C package's later wave).

Evidence vocabulary (same as COLOR_ROUTING_PLAN.md): §1's current-state
map is **code-verified** (every citation was read in this worktree at
HEAD 278dccf); the design sections are **planned**; the model's
behavior is **host-tested** (BUILD SUCCESSFUL, suite 2295 tests,
0 failures, at the package's commit).

---

## 1. Current state (code-verified)

The brief's guess was "the timeline model probably knows nothing about
audio — verify." It knows more: a placed audio clip is a first-class
TimelineClip, bound pairs move together, and the length resolver counts
the audio tail. What does NOT exist anywhere (verified by reading and
by grep): pan, mute, master mix state, multi-clip mixing, resampling,
a mix pass, or any sample-time placement math.

### 1.1 What the timeline model already knows

An AUDIO track is one of the three track kinds:

```
app/.../data/timeline/TimelineModel.kt:49
    enum class TrackKind { VIDEO, AUDIO, TEXT }
```

A clip's audio BINDING is a plain id field on the video clip:

```
app/.../data/timeline/TimelineModel.kt:143
    /** Linked audio clip id when this video clip has attached audio (TL-05). */
    val linkedAudioId: String? = null,
```

The link is honored by the ops, so a bound clip's audio already follows
every timeline edit: trim drags the linked audio by the same edge/delta
(`TimelineOps.kt:144-152`), split cuts the audio at the same edit point
and re-links both pieces (`TimelineOps.kt:281-303`, with the audio left
UNSPLIT when the cut falls outside it), and the pair-add is ONE
transaction (`TimelineEdit.kt:66-99`, `AddClipPair` — the apply sets the
link by construction, the inverse removes both). An INDEPENDENT audio
clip is already representable: it is simply an AUDIO-track TimelineClip
no video clip points at. Duplicate drops the link
(`TimelineOps.kt:63-66`). The length resolver already runs the movie to
the audio tail: `resolveLength` = max(lastAudioEnd, lastVideoEnd,
lastTextEnd, projectEnd) with the backgroundDuration floor
(`TimelineOps.kt:401-423`) — QA-12's "ses sonuna kadar sürer" (the
sound lasts to its end) is half-true today: the VIDEO renders to that
length, as trailing black (TimelineExport.kt:228-232), but no audio
plays under it.

The timebase contract (the whole design hangs on it): every range value
is a frame number in the PROJECT's frame grid as Double, `sourceRange`
expresses source frames on the same grid, ranges are half-open
(`TimelineModel.kt:106-118`), and `sourceRange.duration ==
timelineRange.duration` is enforced (`TimelineModel.kt:145-149`).

### 1.2 What the editor plays today — ONE clip, source-rate, no mix

The timeline screen owns exactly one audio controller, keyed to the
clip under the playhead:

```
app/.../ui/timeline/TimelineScreen.kt:638
    val audioController = remember(clipAt?.sourceId) { EditorAudioPlayback() }
```

It plays only while the playhead's clip has an ATTACHED link state
(`TimelineScreen.kt:715-718`), and the position provider maps the
preview plan's SOURCE frame to source microseconds through the SOURCE
fps:

```
app/.../ui/timeline/TimelineScreen.kt:723-728
    val at = pl.atPlayhead(playheadFrame)
    val f = at.frame as? TimelinePreviewPlan.Frame.Clip ?: return@start 0L
    if (f.sourceId != c.sourceId) return@start 0L
    (f.sourceFrame * 1_000_000.0 / sourceFps).roundToLong()
```

So the editor's audio is a single-clip stream at the source's own rate
— gain/fades from the AudioClip row are NOT in this path, and two
clips never mix. The engine underneath (`engine/EditorAudio.kt`) is a
per-source controller with a floor-rule sample offset
(`EditorAudio.kt:132`, `frameOffsetAt = positionUs * sampleRate /
1_000_000L`), a 1024-frame chunk size (`EditorAudio.kt:284`), and a
pause->flush->seek->play re-prime cycle (`EditorAudio.kt:225-227`) —
AUD-08's seek behavior, already built for one clip.

### 1.3 The export's audio leg — built, unused by the timeline

The clip exporter's writer has a complete AAC leg: ONE AAC-LC codec at
128 kbps with a 64KB input buffer, configured only when a real rate and
channel count arrive:

```
app/.../export/MediaCodecMp4Writer.kt:187-188
    if (sampleRate > 0 && channels > 0) {
        audioCodec = configure("audio/mp4a-latm", audioFormat(sampleRate, channels))
app/.../export/MediaCodecMp4Writer.kt:682-683
    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
```

Its input is ONE continuous PCM stream, `writeAudioBlock(ptsUs, pcm)
` (`MediaCodecMp4Writer.kt:920`) — no mixing, no per-clip routing; the
single-clip exporter feeds it the whole clip and interleaves by pts.
The TIMELINE exporter, however, starts the writer video-only and logs
the honest marker:

```
app/.../export/TimelineMp4Exporter.kt:228-239
    if (plan.audioSkipped) { stageLog?.invoke("timeline audio: skipped ...") }
    // Video-only: 0/0 sample rate/channels (the writer contract).
    writer.start(partial.absolutePath, plan.width, plan.height, 0, 0, 0)
```

and `TimelineExportPlan.audioSkipped` is set whenever any AUDIO-track
clip exists (`TimelineExport.kt:182`). The plan's KDoc says it
outright: "Mixing placed PCM at per-clip rates/gains/fades is a later
milestone (AUD-05/07)" (`TimelineExport.kt:63-67`).

### 1.4 PtsTimeline's arithmetic — the convention to reuse

The export's PTS math is the audio clock convention this plan reuses,
not forks. `audioBlockPts = blockIndex * samplesPerBlock * 1_000_000L /
sampleRate` (`export/PtsTimeline.kt:61-62`) means: sample k lands at
k * 1e6 / rate us past the shared origin — one origin, one rate, and
the first video pts normalized to 0 (`PtsTimeline.kt:39-53`) so A/V
hang off the same clock. Interleaving stable-sorts by pts, video first
on ties (`PtsTimeline.kt:70-89`). The same shape exists in data/audio:
`AudioTrackOps.samplesToMs(samples, rate) = samples * 1000.0 / rate`
is documented as the SINGLE conversion point, with the resample
mission slotting in there — "with the SOURCE rate for source-domain
samples and the MIX rate for mix-domain ones"
(`AudioTrackOps.kt:233-246`); `exportStartMs(frames, fps) = frames *
1000.0 / fps` (`AudioTrackOps.kt:202`) converts the project grid to
ms; `isTestedRate` accepts 44100 or 48000 (`AudioTrackOps.kt:261`).
AUD-06's own row: "44.1 kHz, 48 kHz ve test edilen diğer kaynakları
zaman damgasına göre ortak mix rate'e resample et. Kaynak sample
count'tan zaman türetirken yanlış sabit 48 kHz varsayımı yapma."
(STEPS sartname line 179 — resample to a common mix rate BY TIMESTAMP;
never assume a fixed 48 kHz when deriving time from sample counts.)

### 1.5 The per-clip audio state that already exists

`data/audio/AudioTrackModel.kt` carries the per-source link states
(ATTACHED/DETACHED/REMOVED/LINKED_EDITED, `AudioTrackModel.kt:79-98`,
canonical non-ATTACHED-only persistence, REMOVED never resurrects) and
per-clip AudioClip rows (`AudioTrackModel.kt:133-169`) joined by
id == the audio TimelineClip's id, carrying NO range (the PLACEMENT
OWNERSHIP BOUNDARY, `AudioTrackModel.kt:127-132`): `sampleRateHz`
REQUIRED (`require(sampleRateHz > 0)`, the AUD-06 anchor,
`AudioTrackModel.kt:163-165`), `gainDb` dBFS, `fadeInMs`/`fadeOutMs`
as STORED INTENT (a fade longer than the clip clamps at render time;
trim never rewrites it, `AudioTrackModel.kt:153-160`). An absent row is
unity (`AudioTrackModel.kt:184-185`). AudioStore persists them
omit-when-default (`AudioStore.kt:162-163`) — the precedent for the new
fields in §3. `AudioTrackOps.setSettings` creates the row on demand and
preserves settings across split via `afterSplit`
(`AudioTrackOps.kt:120, 174`).

What is MISSING (grep-verified, nowhere in the app): pan, per-clip or
master mute, master gain, any mix/sum arithmetic, resampling, and the
sample-grid placement math. Those are §2-§3.

---

## 2. The placed-PCM design (planned — the model implements the math)

### 2.1 The resample policy: one project mix rate, 48000 default, resample by timestamp

THE DECISION: the project carries ONE mix rate setting,
`AudioPlan.DEFAULT_MIX_RATE_HZ = 48000`, and every placed clip's audio
is rendered onto that one grid; sources at other rates resample into
it BY TIMESTAMP (the AUD-06 wording). Preview and export share the
same grid — what you preview is what you export.

Why 48000 as the default: the camera's own sources are 48 kHz, so the
DOMINANT case is passthrough — zero resampling, zero quality loss,
zero CPU. A 44.1 kHz source resamples 44.1 -> 48 (QA-12's exact case);
a 44.1-only project can set the mix rate to 44100 and never resample
at all. The rate is a PROJECT SETTING, never an assumption: the model
requires the source's real rate everywhere (the RATE_UNKNOWN honesty
gate skips a clip rather than substitute 48000 — §2.4), and no time is
ever DERIVED from a sample count using the mix rate (the AUD-06
prohibition holds: source-domain math uses the source rate).

The alternatives, weighed and rejected:

- PER-SOURCE PASSTHROUGH (each clip plays at its own rate, no mix
  grid): works for the single-clip editor, breaks for a mix — the
  writer's AAC leg takes ONE rate (`MediaCodecMp4Writer.kt:187-188`
  configures one codec), and a 44.1 clip followed by a 48 clip has no
  single-clock answer. This is exactly why v1 export skipped audio.
- DEVICE RATE (AudioTrack's native rate): silent quality loss for the
  48 kHz majority on 44.1-kHz devices, and it makes preview and export
  DISAGREE (export has no device rate). Rejected for the same
  what-you-preview-is-what-you-export reason as above.
- MIX AT 44100 ALWAYS: resamples the dominant case. Rejected.

The resampler ITSELF is a later mission (the real interpolation
kernel — linear or windowed-sinc — slots into AudioTrackOps'
documented single conversion point, `AudioTrackOps.kt:233-246`). The
model's v1 pull is NEAREST-SAMPLE by timestamp: the source sample at
the same real time as the mix sample. This is honest for the model's
purpose (the placement/mix contract), and the kernel upgrade changes
the pull's interior, never its contract. The plan's recommendation:
linear interpolation for the first wired release (cheap, click-free at
these rate ratios), windowed-sinc if ABX tests ever demand it — the
QA-12 row measures "başlangıç ve seek click/crackle" (start and seek
clicks/crackles), which nearest-sample pull CAN click at 44.1->48; the
wired release must not ship nearest-sample as the audible path.

### 2.2 The mapping rule: project frames -> samples, through the single conversion point

A project-grid position g (frames) at rate R is the sample
`round(g * R / fps)`. The model implements it as the COMPOSITION of
the two existing AUD-06 functions — `exportStartMs(g, fps)` then
`msToSamples(ms, R)` — never a private multiply:

```
app/.../data/timeline/AudioPlan.kt (sampleAt)
    val ms = AudioTrackOps.exportStartMs(projectFrames, projectFps) ?: throw ...
    return AudioTrackOps.msToSamples(ms, rateHz)
```

so the future resample mission that slots into those functions is
inherited for free, and the closed form is pinned as its algebraic
identity by test (`sampleAt equals the closed form round g times rate
over fps`). This is the AUDIO TWIN of the video export's frame-nearest
mapping `round((sourceRange.start + j) * sourceFps / projectFps)`
(`TimelineExport.kt:21-29`) — same shape, sample domain instead of
frame domain. EDGE-NEAREST: each range edge maps independently, the
span is the difference; at 30 fps both 44.1 and 48 kHz divide the frame
exactly (1470 / 1600 samples — pinned by test), at other fps a span is
off by at most one sample (honest, documented, and the same rounding
the video grid already accepts).

### 2.3 The two windows: source-domain and mix-domain

A placed clip's audio is TWO half-open windows built by
`AudioPlan.placementOf`:

- The SOURCE window `[sourceStartSample, sourceEndSample)` at the
  SOURCE's own rate, from `sourceRange` — which source samples the
  trims kept. A head trim moves its start (30 frames trimmed = 48000
  samples at 48 kHz/30 fps — pinned by test, and the test verifies it
  equals the window the ops' own `TimelineOps.trim` produces).
- The MIX span `[mixStartSample, mixEndSample)` at the mix rate,
  absolute on the project grid, from `timelineRange` — where they play.
  A timeline MOVE shifts this and never the source window (pinned by
  test — `TimelineOps.move`'s contract, in samples).

SPLIT REJOIN (pinned by test): two pieces of a clip split at the
midpoint have contiguous source windows and mix spans that reproduce
the unsplit clip's exactly — the sample-grid arithmetic inherits the
frame-grid's split precision for free, because both windows derive
from the same ranges. Within a clip, mix offset n pulls source sample
`sampleAt(sourceRange.start + n * fps / mixRate)` — offset 0 hits the
window start exactly; EQUAL RATES are the pure identity
`sourceStartSample + n` (passthrough, no resampler runs — pinned by
test); unequal rates pull by timestamp (a 44.1 source in a 48 mix:
1600 mix samples = 1/30 s = 1470 source samples — pinned by test).

The consumer clamps the source window to the source's real decoded PCM
length (an unknown length is the consumer's gate — the
FRAME_COUNT_UNKNOWN twin; the model never guesses bounds) and
intersects the mix span with `[0, totalMixSamples)` (the video walk's
outStart/outEnd clamp twin, `TimelineExport.kt:204-206`).

### 2.4 The honesty gates

`AudioPlan.mixPlan` walks the AUDIO-track clips in timeline order and
refuses to guess, exactly like the video plan (`TimelineExport.kt:
31-44`): no source id -> `SOURCE_MISSING`; a rate the lookup cannot
answer -> `RATE_UNKNOWN` — NEVER a 48000 substitution (AUD-06). A
skipped clip's span renders SILENT (the audio twin of a black skip
span) and the movie still runs to the audio-resolved length (both
pinned by test). A clip whose window rounds empty contributes nothing
— no entry, no skip (the zero-duration rule's twin).

---

## 3. The gain/pan/mute/fade reference (planned; the math is host-tested)

### 3.1 The state

Per clip (`AudioPlan.ClipAudioState` — the reference shape of AUD-07's
"klip gain, stereo pan, track mute"): `gainDb` dBFS (0 = unity),
`pan` -1..1 (0 = center), `muted`, `fadeInMs`/`fadeOutMs` stored
intent. gain/fades mirror the persisted AudioClip fields; PAN and MUTE
are the proposed additions — the migration is to add them to
`data/audio/AudioClip` (with `AudioStore`'s omit-when-default
persistence, the fadeInMs precedent, `AudioStore.kt:162-163`) and
delete `ClipAudioState.of`'s honest defaults. Master
(`AudioPlan.MasterAudioState`): `gainDb`, `muted` — proposed to live
on AudioProject, same persistence precedent. AUD-07's "Dışa aktarmada
mute ve track seçimi proje snapshot'ından alınır" (export takes mute
and track selection from the project snapshot) is satisfied by
construction: the state IS the project, the mix reads it at plan time.

### 3.2 The pan law: CONSTANT-POWER, and why

THE DECISION: constant-power. pan p maps to the angle
(p + 1) * pi / 4; left = cos, right = sin. Center is sqrt(2)/2 per
channel — -3.01 dB, UNITY POWER SUM: left^2 + right^2 = 1 at EVERY pan
position (pinned by test across the whole sweep), so the perceived
loudness stays constant as the knob sweeps. Full left/right hands one
channel 1.0 and the other 0.

The alternatives, weighed and rejected:

- LINEAR (left = (1-p)/2, right = (1+p)/2): center is -6.02 dB per
  channel, power sum 0.5 — the loudness DROPS 3 dB at center. Feels
  like the sound thins out as you pan toward center. Rejected.
- -4.5/-6 dB "broadcast" center-attenuation laws: a compromise shape
  with a tunable constant; a knob sweep is still not power-flat
  between the constant's region and the edges. Rejected — a reference
  model should have one principled law, not a tunable.

Why constant-power wins for THIS app: the mix sums clips — a power-
flat pan keeps each clip's ENERGY contribution independent of its pan
knob, so the sum's loudness behaves predictably, and a center-panned
mono source (the camera's own audio, the dominant case) sits at -3 dB
per channel with a correct stereo image instead of -6 dB thinness or
0 dB double-energy (the -0 dB "no law" option, which makes a
center-panned clip 3 dB HOTTER than either edge — the worst option,
rejected outright; it is nevertheless what a naive L=R=1 mix does).

### 3.3 The render contract: gain -> pan -> fades -> sum, hard saturation

The per-clip chain at one mix offset (all amplitude scalars, so the
product commutes — the ORDER is the contract's naming, not a
constraint): `linearGain(gainDb)` (10^(dB/20)) -> `panGains(pan)` ->
`fadeGain(...)`; MUTE short-circuits to silence before any of it.
The fade envelope is a LINEAR ramp, stored intent clamped to the placed
span (a 10 s fade on a 1 s clip ramps across the whole clip — pinned
by test): the in-ramp is 0 at offset 0 and 1 at the fade length; the
out-ramp is 1 until (end - fadeOut) and reaches 0 AT the exclusive end
edge — the last real sample keeps 1/fadeOutSamples (pinned by test);
overlapping in/out fades MULTIPLY (each is an amplitude scalar —
pinned by test at the midpoint 0.5 * 0.5 = 0.25).

The mix tail: sum every contribution's channels (double accumulation),
apply the master gain, then SATURATE — HARD CLIP at +/-1.0
(`coerceIn`, pinned by test even at Float.MAX_VALUE): a summed sample
beyond full scale clamps, it never wraps, and no soft limiter runs
(v1). The unsaturated sum is returned so the consumer can count
clamped samples — AUD-07's "peak hold ve clipping ölçümü" (peak hold
and clipping measurement) hangs off exactly that comparison.
Master mute silences the whole mix (pinned by test).

### 3.4 The undo integration (a proposal the integrator owns)

Audio settings edits today are NOT undo-recorded — the settings sheet
calls `AudioTrackOps.setSettings` directly (`TimelineScreen.kt:1146-
1161`), outside TimelineHistory. The proposal: settings edits become
TimelineEdit transactions (a `SetClipAudio` carrying the new state,
its inverse restoring the pre-edit row — the SetClipColor precedent,
`TimelineEdit.kt:262-274`), recorded in the SAME history as cuts and
trims. QA-13's "Undo bir önceki state'i döndürür" (undo returns the
previous state) then covers "sese ait editler kalır" (the clip's
edits survive) symmetrically: the edits survive BECAUSE they are rows
joined by id that ride every timeline edit, and undo returns them
because they are transactions. The REMOVED link state's no-resurrection
rule stays outside transaction undo (whole-record replacement is the
documented exception, `AudioTrackModel.kt:84-90`).

---

## 4. Bound vs independent audio (planned; the shapes are host-tested)

### 4.1 The two shapes, one placement rule

The model must represent both, and it already does — the shapes
differ ONLY in the join, never in placement:

- BOUND (QA-13 "bağlı ses"): a video clip's `linkedAudioId` points at
  the audio clip. The bound audio MOVES WITH the video — the ops'
  trim/split rules already drag it (`TimelineOps.kt:144-152, 281-303`)
  — and the join is BY ID, so it survives any move of either half
  (`AudioPlan.boundVideoOf` resolves it; pinned by test: move the video
  half, the join still binds; move the audio half independently, the
  join STILL binds — binding is the id, not the position).
- INDEPENDENT (QA-13 "bağımsız"): an AUDIO-track clip no video points
  at — a music bed, a voiceover. It places alone at its own
  `timelineRange` and edits alone (the ops' linked-follow code simply
  finds no video pointing at it, so nothing drags it).

`AudioPlan.mixPlan` places both IDENTICALLY (pinned by test): the walk
is uniform over AUDIO-track clips, and `boundVideoId` is recorded on
each placed clip as the JOIN FACT, not a placement rule. This is the
design's point: boundness affects which EDITS drag a clip (the ops'
job, already built), never how its audio maps onto the sample grid
(this model's job) — one mapping, one mix, no special cases.

### 4.2 QA-13's three cases against this design

- "Bağlı sesin kesilip taşındığı timeline" (bound audio cut and
  moved): split re-links both pieces (`TimelineOps.kt:282-303`), a
  move of either half keeps the id join, the settings row rides by id
  — the clip's audio plays at its own timelineRange wherever it is,
  with its gain/fades intact (`afterSplit` preserves settings,
  `AudioTrackOps.kt:174`).
- "Bağımsız split" (independent split): an independent clip splits by
  the same TimelineOps.split rule — no video is involved, nothing to
  drag, the pieces are independent too.
- "Kaldırılmış ses tekrar oluşmaz" (removed audio does not come back)
  and "yeniden açma" (reopen): the REMOVED link state's no-resurrection
  invariant (`AudioTrackModel.kt:84-98`, persisted verbatim) is
  per-SOURCE; it already survives reopen. The mix plan's part in it:
  a source whose link state is REMOVED/DETACHED simply has no placed
  AUDIO-track clip to mix (the pair-add only creates the audio clip
  when metadata says audio is present, `TimelineEdit.kt:66-83`) —
  the mix never resurrects what the link state removed. The gate the
  wired version needs: the mix plan should ALSO consult the link
  state (skip an audio clip whose source is DETACHED/REMOVED), the
  same honest-skip shape as RATE_UNKNOWN. RECOMMENDED for the wiring.

### 4.3 The UI's minimal first wave (proposal)

1. The timeline already renders the audio lane and pairs; the first
   wave adds the SETTINGS surface: per-clip gain slider (dBFS), pan
   knob (-1..1), mute toggle, fade in/out ms — on the existing
   settings sheet (`AudioTrackOps.setSettings`'s caller,
   `TimelineScreen.kt:1146-1161`), extended with pan/mute once §3.1's
   migration lands.
2. Playback stays the single-clip path for v1 of this wave (it already
   plays the clip under the playhead); the MIX playback engine (a
   multi-clip mixer over the mix grid) is the next wave with its own
   mission — this package's model is its contract.
3. Master gain/mute in the timeline's output settings, next to fps
   (TimelineOutput's neighborhood).
4. The bound-vs-independent UI gesture (drag a bound audio clip off
   its video = make independent; nothing new in the model — dragging
   moves the audio clip and the id join survives, which IS
   independence of placement while the link persists; an explicit
   "detach from video" command would clear linkedAudioId, a one-field
   edit transaction).

---

## 5. The export contract (planned — the integrator's biggest decision)

### 5.1 The shape required

The writer's AAC leg takes ONE continuous interleaved PCM stream at
ONE rate (`MediaCodecMp4Writer.kt:187-188, 920`), and the timeline
exporter currently starts it video-only (`TimelineMp4Exporter.kt:
236-239`). A multi-clip, multi-rate, mixed-gain timeline therefore
needs a MIX PASS: a walk over the mix grid that pulls each placed
clip's source samples (per the §2.3 mapping), applies the §3 chain,
sums, saturates, and hands the writer one stereo stream at the mix
rate, in `writeAudioBlock`-sized blocks with `PtsTimeline.
audioBlockPts` convention pts (sample k at k * 1e6 / rate us — the
same clock the video pts share, `PtsTimeline.kt:61-62`). The model's
`AudioMixPlan` is that pass's recipe — the audio twin of
TimelineExportPlan's "plan-is-the-recipe": the exporter consumes the
plan, never re-derives placement.

### 5.2 The options, weighed

- A. NEW MIX PASS in the timeline exporter (RECOMMENDED): build the
  AudioMixPlan, decode each placed clip's source PCM once (the
  open-once source cache precedent, `TimelineMp4Exporter.kt`'s own
  cache), resample 44.1->48 as §2.1 decided, mix, feed the writer's
  existing AAC leg block by block. COST: a decode+resample+mix pipeline
  the single-clip path never needed. BENEFIT: the writer is UNTOUCHED
  (its contract already fits — one stream, one rate), preview and
  export share the same mix plan (what you preview is what you
  export), and the honesty gates travel with the plan.
- B. PER-CLIP PASSTHROUGH into the writer (each clip encoded as its
  own audio run, the single-clip shape repeated): REJECTED — the
  writer configures ONE codec at ONE rate for the whole file
  (`MediaCodecMp4Writer.kt:187-188`); a 44.1 clip and a 48 clip cannot
  share it, and overlapping clips (video + music) have no per-run
  answer at all. This option is exactly why v1 skipped audio.
- C. MIX IN THE WRITER (hand the writer per-clip blocks with gains):
  REJECTED — it moves placement/mix policy INTO the Android-bound
  writer, below the JVM-tested line, and forks the mix math from the
  preview's. The writer stays a dumb byte pipe; policy lives in plans.

Recommendation: A, with the mix pass reading the SAME AudioMixPlan the
future playback mixer reads — one recipe, two consumers (preview and
export), the TimelinePreviewPlan precedent of reusing export entries
verbatim ("WHAT YOU PREVIEW IS WHAT YOU EXPORT").

### 5.3 Where the mix pass lives (the integrator decides)

The recipe (AudioPlan) is in data/timeline, JVM-tested. The PASS that
walks it needs codecs and PCM sources — Android-bound. The natural
home is export/ next to TimelineMp4Exporter (a TimelineAudioMixer
that the exporter calls between the video walk and finalize), because
the decode side reuses the exporter's source cache and the write side
reuses the writer's existing leg. The alternative — engine/ next to
EditorAudio, sharing its PCM sources for a playback-and-export mixer —
becomes MORE attractive the moment the playback mix wave starts
(mission AUD-08's queue lifecycle). The plan's recommendation: export/
first (the export contract is this package's question), and the
playback mixer wraps the same plan later. THE INTEGRATOR OWNS THIS.

---

## 6. Acceptance tests (the research's list)

The model's 35 host tests already cover the placement/mapping/state
groups; the QA rows below are the DEVICE acceptance the sartname
names, plus the research list the plan adds. Status vocabulary:
host-tested (in this package), planned (the wiring's tests),
device-test-pending (needs a phone — NOT in this package).

### 6.1 QA-12 (sartname line 467): "44.1 kHz kaynak, 48 kHz kaynak,
video sonunu aşan ses" — waveform and heard section aligned; the sound
lasts to its end; start and seek click/crackle measured.

- 44.1 and 48 kHz placement on exact sample counts at 30 fps (1470/
  1600 per frame): HOST-TESTED (sampleAt, placement, cross-rate pull).
- The same real timeline position for both rates (timestamp pull,
  offset 0 exact): HOST-TESTED.
- Audio tail beyond the last video frame runs the movie to the
  resolved length (resolveLength's lastAudioEnd -> totalMixSamples):
  HOST-TESTED (the model); the EXPORT rendering trailing black under
  playing audio: PLANNED (the mix pass).
- Start/seek click/crackle: the wired resampler's kernel choice (§2.1)
  is the whole question — DEVICE-TEST-PENDING (needs audible output;
  the model pins only that offset 0 hits the window start exactly,
  which is the no-click NECESSARY condition, not the sufficient one).

### 6.2 QA-13 (sartname line 468): bound audio cut+moved, independent
split, reopen; edits survive, plays where moved, removed audio stays
gone, undo returns the previous state.

- Bound move keeps the id join (both halves movable): HOST-TESTED.
- Independent clip places alone (null boundVideoOf): HOST-TESTED.
- Split pieces tile the unsplit window exactly (rejoin precision):
  HOST-TESTED.
- Settings survive split (afterSplit) and ride by id: the row
  mechanics are CODE-VERIFIED (`AudioTrackOps.kt:174`); the mix
  rendering them: PLANNED (the mix pass).
- Removed audio never resurrects on reopen: the link state's
  invariant is CODE-VERIFIED (`AudioTrackModel.kt:84-98`); the mix
  consulting the link state (§4.2's gate): PLANNED.
- Undo returns the previous state: timeline edits are CODE-VERIFIED
  transactional; audio SETTINGS as transactions (§3.4): PLANNED.

### 6.3 The research list this plan adds

1. The resampler kernel decision (linear vs windowed-sinc) with an
   ABX/listening check on a 44.1->48 music clip — the click/crackle
   row is the acceptance. DEVICE-TEST-PENDING.
2. The mix pass's block scheduling vs the video walk (one pass
   interleaved, or audio-after-video) — measure the exporter's memory
   with a long music bed; the writer's 64KB input buffer bounds the
   block size (`MediaCodecMp4Writer.kt:683`). PLANNED.
3. The link-state gate in the mix plan (§4.2) — a DETACHED source's
   audio clip must skip silently in preview AND export. HOST-TESTED
   (2026-11-23): `AudioPlan.mixPlan(..., linkState)` skips DETACHED /
   REMOVED / LINKED_EDITED as `LINK_NOT_ATTACHED`; the wired overload
   `mixPlan(project, audio: AudioProject, ...)` joins states + gate from
   the persisted project. The consumers (preview mixer, export mix pass)
   are still the later wave.
4. Pan/mute persistence migration (§3.1) — AudioClip's new fields with
   omit-when-default, old files loading as center/unmuted. HOST-TESTED
   (2026-11-23): `AudioClip.pan/muted`, `AudioProject.masterGainDb/
   masterMuted`, AudioStore omit-when-default + clamp-on-load,
   `AudioTrackOps.setPan/setMuted/setMaster` (setSettings keeps pan/mute).
   No UI control yet (the sheet still edits gain/fades only).

---

## 7. Status

- The pure model (AudioPlan.kt + 35 tests): **implemented +
  host-tested** (BUILD SUCCESSFUL, `:app:testDebugUnitTest
  :app:assembleDebug`, suite 2295 tests 0 failures, at this package's
  commit).
- This plan document: **planned / review-pending** — the owner reads
  it; the integrator owns every §3.4/§4.3/§5.3 decision.
- Nothing in the app imports the model yet; no runtime behavior
  changed. Timeline playback still plays one clip at the source rate;
  timeline export still starts the writer video-only — wiring those
  to this contract is the C package's later wave.
