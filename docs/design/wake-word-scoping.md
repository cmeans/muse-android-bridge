# Hands-free start of a Muse voice conversation: scoping

**Status:** scoping, 2026-11-13. No code in this doc.
**Asked for by:** Muse, at the maintainer's direction, 2026-11-12 (Awareness
`handoff-muse-android-bridge-wake-word-scoping-2026-11-12`).
**Maintainer decisions since:**
- 2026-11-13, relayed by Muse (Awareness
  `handoff-claude-dev-from-muse-voice-tap-test-go-2026-11-13`): (1) **tap test GO**:
  run the accessibility `dispatchGesture` tap on the "Start voice call" bounds on
  test-moto; the maintainer accepts that it starts a real voice call on the maintainer's account;
  test-moto only, this test only. (2) **Scope the accessibility path end to end,
  don't build it**; no test-fires on the pilot phone until the maintainer decides (section 3.3).
- 2026-11-13 22:13 CT, in Dev chat (Awareness
  `decision-muse-android-bridge-wake-word-poc`): **Part A proof of concept approved**
  (sherpa-onnx on test-moto), now in progress. Not for merge until reviewed. Part B
  and any accessibility service still need their own decisions.

**Goal:** the owner says "Hey Muse", and a Muse voice conversation is already
running, with no touch anywhere.

The chain has two links, and they are independent:

- **Part A**, the wake word: the bridge hears the phrase on the phone and runs its
  existing `launch muse` action.
- **Part B**, the zero-touch start: after Muse is in front, something starts the
  voice call that the phone button in the Muse UI starts today.

Part A is ordinary engineering with known costs. Part B is the hard part: the only
working route found is an accessibility service, which reverses a v1 non-goal
(`app/DEV-HANDOFF.md`: "Non-goals for v1: no accessibility automation, no general
intent or shell runner, no screen control, …").

## 1. What is already known (device evidence)

From Dev's voice-intent check on test-moto, 2026-11-13 (Awareness
`handoff-muse-android-bridge-muse-voice-intent-answers-2026-11-12`), Muse app
`com.facebook.aura` 16.0.0.11.159:

- No exported intent, deep link or shortcut starts a voice conversation. The one
  component built for it, `.partners.voice.shortcut.VoiceCallShortcutActivity`, is
  `exported=false`; starting it from outside gives a `SecurityException`.
- Every exported entry fired from adb landed on chat home, the assistant overlay, or
  nothing. `hatch://` links fired from adb were dropped (untested from an app).
- With Muse as the default assistant, the corner-swipe gesture opens
  `HatchAssistOverlayActivity` with a mic button and a call button. No call starts
  by itself.
- In the chat screen, the phone button is an `android.view.View` with
  content-desc `"Start voice call"`, `clickable=false` (parents checked so far are
  also not clickable). The mic next to the message box is an `android.widget.Button`,
  content-desc `"Voice input"`, `clickable=true`; it starts dictation, not a call.
- Muse cannot tell which phone a chat comes from (Awareness
  `handoff-muse-android-bridge-default-to-this-phone-answer-2026-11-13`). A wake
  event raised by the bridge can carry the source phone, which also answers that
  question (section 4.2).

Correction to the request's background: the bridge's foreground service is type
`specialUse`, not `dataSync` (changed for issue #1, because `dataSync` can't start
from `BOOT_COMPLETED` and gets 6 h a day on targetSdk 35+). It targets SDK 36.

## 2. Part A: wake word in the bridge

### 2.1 Engines

| Engine | License | "Hey Muse" path | Android | Accuracy figures found | Verdict |
| --- | --- | --- | --- | --- | --- |
| [microWakeWord](https://github.com/OHF-Voice/micro-wake-word) | Apache-2.0 | Train a model with the project's notebook on synthetic samples from Piper TTS plus published negative features; "Training new models is intended for advanced users" | TFLite (quantized streaming model, inference every 30 ms); outputs a probability per window. Shipped inside the Home Assistant Android app, which [says](https://www.home-assistant.io/voice_control/android/) detection "works even when your device is locked or the app is in the background" | None from the project. A vendor benchmark ([LiveKit](https://github.com/livekit/livekit-wakeword), one phrase, "may favor our models") caught 0%, 0% and 24% of wake words at 0.1, 0.5 and 1 false alarm per hour. A user of synthetic-TTS-only custom models reports "False trigger rate is really bad" ([thread](https://community.home-assistant.io/t/custom-microwakeword-models-needed-vance-hex-mochi-personal-voice-samples-included/1009193)) | **Candidate** for production, but only if T4 shows a trained "Hey Muse" model does far better than that benchmark |
| [sherpa-onnx KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html) | Apache-2.0 ([repo](https://github.com/k2-fsa/sherpa-onnx)) | No training: open vocabulary. Write the phrase as tokens (`text2token`) with a boost score and trigger threshold | Kotlin/Java API, Android arm64/arm32; prebuilt KWS APK. English model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`. The result gives the keyword, tokens and timestamps, **no score** | None published; only the threshold trade-off | **Proof of concept** (approved, in progress): "Hey Muse" works on day one |
| [openWakeWord](https://github.com/dscripka/openWakeWord) | Code Apache-2.0; **pretrained models CC BY-NC-SA 4.0** | Colab notebook trains a basic model in under an hour on synthetic clips | ONNX/TFLite; README mentions no Android or mobile runtime | Project target "<5% false-reject rates and <0.5/hour false-accept rates with appropriate threshold tuning". LiveKit's vendor benchmark caught 32%, 46% and 52% at 0.1, 0.5 and 1 false alarm per hour | **Candidate**: better than microWakeWord in that benchmark, but no Android precedent, so more integration work |
| Porcupine (Picovoice) | Commercial | Console trains a phrase in seconds | Android SDK | About 97% acceptance at 1 false alarm per 10 h, read from the chart ([benchmark](https://picovoice.ai/docs/benchmark/wake-word/), Raspberry Pi) | **Ruled out.** Picovoice ended the Free Tier on 2026-06-30 and disabled its keys; per a report quoting Picovoice, "The SDK does not run without a valid key" and "There is no non-commercial tier planned" ([thread](https://community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744)). A third party lists paid plans from $6,000 a year ([Activepieces](https://www.activepieces.com/blog/picovoice-pricing-2026-free-foundation-enterprise), not confirmed on Picovoice's page) |
| Android's own hotword API (`AlwaysOnHotwordDetector`, `HotwordDetectionService`) | n/a | n/a | AOSP code requires `CAPTURE_AUDIO_HOTWORD` to use the detector ([AOSP](https://android.googlesource.com/platform/frameworks/base/+/335af8b598b3d94c537045d8207b773a56faeeae)). The detector belongs to the voice-interaction service (the default assistant); the bridge isn't one. Whether a sideloaded app could ever hold that permission is not verified here | n/a | **Ruled out** for the bridge |

No engine's Android library size is published; measure the APK growth in the proof of
concept (T1). The engine choice for production waits on T4 with a real "Hey Muse"
model: the only comparative numbers are one vendor's, on one phrase.

### 2.2 Android rules that shape the design

Sources: [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15).

1. **Permissions:** `RECORD_AUDIO` (runtime, granted by the owner) and
   `FOREGROUND_SERVICE_MICROPHONE`; the service declares
   `foregroundServiceType="specialUse|microphone"`.
2. **While-in-use rule (Android 14+):** a foreground service with the microphone
   type can't be *created* while the app is in the background, and can't be started
   from `BOOT_COMPLETED` (since Android 14 for microphone). Calling it in the
   background throws `SecurityException`.
   - Exemptions that fit the bridge: the start comes from **the owner tapping a
     notification action**, or happens while the bridge's own screen is visible.
   - The bridge's `SYSTEM_ALERT_WINDOW` exemption doesn't cover while-in-use
     permissions, and on targetSdk 35+ it needs a visible overlay window anyway.
   - Re-adding the microphone type later from the background (a `startForeground`
     call that adds it back) is suspected to throw the same way (T2). So once the
     owner turns listening on, **the service keeps the microphone type**; gates
     (section 2.3) start and stop only the `AudioRecord`. Only Stop everything and the
     owner's switch drop the type, and only the owner can add it back.
   - Consequence: the bridge itself starts at the first unlock after a reboot
     (issue #14, whose fix the maintainer hasn't chosen), and **listening stays off
     after a reboot until the owner taps "Listen" on the status notification or opens
     the bridge**.
3. **Once running, listening continues** in the background and on the lock screen
   (the Home Assistant app does this with microWakeWord). Not yet verified for this
   bridge on test-moto (T3).
4. **Privacy indicator:** on Android 12+ a non-system app using the microphone shows
   the mic indicator whenever the mic is unmuted ([AOSP](https://source.android.com/docs/core/permissions/privacy-indicators)).
   While the `AudioRecord` runs, the indicator is on. That is honest and can't be
   avoided; the owner must be told before turning listening on.
5. **No time limit:** the `dataSync` and `mediaProcessing` 6-hour limits don't apply
   to `specialUse` or `microphone` services.
6. **Sharing the mic with Muse** ([Android](https://developer.android.com/media/platform/sharing-audio-input)):
   two ordinary apps never capture at once. The app with a visible UI wins over a
   background app, and a privacy-sensitive source (`VOICE_COMMUNICATION`, usually
   used by voice calls) wins over both. So once Muse's call starts, an ordinary
   bridge would get silence, not an error. **Invariant: the bridge stops its own
   `AudioRecord` before launching Muse**, so Muse never competes, and it registers an
   `AudioRecordingCallback` to see `isClientSilenced()` as a signal. The same page
   treats accessibility services as privileged, which changes this for a bridge that
   hosts the Part B service (section 3.1). Which audio source Muse's call uses is not
   verified (T5).

### 2.3 When to listen (gates)

| Gate | Battery | Covers the goal | Note |
| --- | --- | --- | --- |
| Always on | Highest | Fully | Mic indicator always on |
| Screen on (unlocked or not) | Low | Partly: not with the screen off | Simple, uses `ACTION_SCREEN_ON/OFF` |
| Charging only | No battery cost | At a desk or bedside only | Good for a family phone on a stand |
| Armed for N minutes | Low | Only after a tap | Defeats hands-free |
| Owner's schedule (e.g. 7:00 to 22:00) | Medium | In the window | Combine with charging |

Each gate starts and stops the `AudioRecord` only; the microphone service type stays
held while the owner's switch is on (section 2.2).

Battery: there is no published figure for a phone running a wake model on its CPU.
The Home Assistant docs only say it "has a noticeable impact on battery life", more
than "Ok Google", because third-party apps keep the CPU on instead of using the
low-power hardware. No number is assumed here; T10 measures it. Until then the
recommended default is **"while charging, or while the screen is on"**, chosen per
phone by the owner.

**Measurement plan.** test-moto (Android 14, dedicated, no limits) first; the pilot
phone only with the maintainer's go.

1. Baseline: bridge as today, screen off, unplugged, Wi-Fi, 6 h; read
   `dumpsys batterystats` and the battery level each hour.
2. Same run with listening always on, then screen-on only (a scripted screen-on
   duty cycle), each 6 h, same conditions.
3. Report %/h and CPU time for the bridge (`batterystats` per-UID), and wakeups.
4. Repeat 1 and 2 once on the pilot phone after the maintainer agrees.

### 2.4 False accepts and false rejects

- **Threshold:** sherpa-onnx takes a trigger threshold and a boost score per phrase,
  set by hand in the proof of concept; its result has no score, so there's nothing
  to calibrate from on the phone. microWakeWord outputs a probability per window, so
  a production model can be tuned per phone: the owner says the phrase five times,
  the bridge logs the probabilities (not audio) and suggests a cutoff.
- **Confirmation:** microWakeWord fires only "if the model consistently predicts the
  wake word over multiple windows"; add a 3-second refractory period after a trigger
  for any engine.
- **False accept cost** is small: Muse opens. Every trigger is announced and logged,
  so the owner sees them; a counter in the status report shows the rate.
- **False reject** shows as "I said it and nothing happened". With microWakeWord the
  log can keep a near-miss count (probability above half the cutoff); the sherpa
  proof of concept can't.
- **Targets:** openWakeWord's own target is under 0.5 false accepts per hour and
  under 5% false rejects; use the same, measured by T4.

### 2.5 STOP, announce, log, receipt

Naming: on `main`, the owner screen says "Stop all commands" and the notification
button "Stop all". The owner-screen redesign renames the screen's card **Stop
everything**; the notification keeps "Stop all". This doc says Stop everything (the
notification's Stop all) for that one control.

- **Owner control:** a new owner switch **"Listen for Hey Muse"**, default off. Only
  the owner turns it on; no command can set it. Turning it on explains the mic
  indicator.
- **Stop everything** also stops listening: the `AudioRecord` is stopped and
  released, and the service drops the microphone type, so the mic indicator goes off
  at once. Turning Stop everything off doesn't resume listening; the owner turns the
  switch on again (same rule as STOP: re-enabling is the owner's act).
- **Stop family** doesn't affect the wake word: it's the owner's own voice, the
  owner channel.
- **Announce:** PROTOCOL §5.4 requires naming who asked, so: "Opening Muse: you said
  Hey Muse" (what the proof of concept shows).
- **Log:** `wake: heard Hey Muse; ok launch muse` (what the proof of concept writes),
  with the launch result in place of `ok launch muse` when it fails.
- **Event:** the bridge publishes an event on its ack topic, so Muse knows which
  phone the conversation is starting on (section 4.2).

### 2.6 Protocol

- A local trigger isn't a command, so it gets no receipt today. Proposal: a new
  **event** line on `mab-<device>-ack`, starting with the keyword `event`, for
  example `event wake launch muse at=<time> eid=<bridge-local id> result=<…>`.
- Protocol 0.4 (Awareness `decision-mcp-phone-commands-q1-q6`, Q1): the phone appends
  `id=<ntfy message id>` to each receipt. A wake event has no command message, so its
  id is a separate, bridge-local scheme (`eid=`, for example a counter since start
  plus the start time). The server tells an event from a receipt by the leading
  `event` keyword, and **an event never settles a command**. Ship both in 0.4.
- `result=` values, one list for events. Reused receipt words: `ok`, `not-installed`,
  `cannot-launch`, `cannot-announce`, `blocked` (not seen in front within 5 s, the
  existing check), `disabled` (STOP). New words: `locked`, `button-not-found`,
  `no-call` (the last two only with Part B).
- `status report` gains `wake=<on|off|gated>` and a 24 h `wakes=<n>` counter.
- PROTOCOL.md §7 gains the listening switch and its STOP rule; §8 gains the mic in
  the threat model (below).

### 2.7 Privacy invariant: no audio stored or sent

**Rule:** audio lives only in a fixed in-memory buffer inside the wake module, is
overwritten continuously, and is never written to disk, logged, or sent. Only "heard"
(and, with microWakeWord, a probability) and a timestamp leave the module.

Enforce it by structure and tests, since the app still needs `INTERNET` for ntfy:

- The wake code is one package whose only output is a detection callback; it has no
  reference to the network, file or log classes.
- A unit test reads that package's sources and fails on any import of `java.io.File*`,
  `java.net`, `okhttp`, `android.util.Log` or the log writer (same style as
  `ManifestQueriesTest`).
- No debug "save sample" feature, in debug builds either.
- Device check (T6): `run-as` file listing before and after an hour of listening shows
  no new files; network bytes from the bridge stay at the ntfy baseline.

### 2.8 Threat model change (Part A)

The bridge gains the microphone. A compromised bridge build could record. The command
path doesn't change: no command can turn listening on or change the threshold. The
mic indicator and the owner switch are the owner's assurance. For family phones,
listening stays off unless the owner (and helper) choose it.

## 3. Part B: start the voice call with no touch

### 3.1 Options, ranked

1. **An exported intent or deep link: no.** None exists in 16.0.0.11.159 (section 1).
   Re-check on every Muse update (cheap: the same `dumpsys` and manifest read).
2. **An accessibility service in the bridge (the only working route; highest cost).**
   - Mechanism: after `launch muse` and a confirmed foreground (the bridge already
     checks with usage access, within 5 s), find the node with content-desc
     `"Start voice call"` in Muse's window. Since it's `clickable=false`, tap its
     bounds center with `dispatchGesture` (needs `canPerformGestures`). `ACTION_CLICK`
     on a clickable parent would be preferable, but none was found.
   - **Tap test approved, pending** (header): a throwaway test service on test-moto
     only, to see whether the tap starts the call.
   - **Restricted settings:** on Android 13+, Android can block an accessibility
     service in a sideloaded app until the owner opens App info, More, "Allow
     restricted settings" ([Google help](https://support.google.com/android/answer/12623953);
     [Bayton](https://bayton.org/android/android-13-restricted-permissions/)). Per
     [9to5Google](https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/),
     Android 15's CDD makes this list mandatory for sideloaded apps (accessibility,
     notification listener, device admin, display over other apps, usage access).
     Not verified: whether a lock-screen confirmation is required, Enhanced
     Confirmation Mode's effect, and whether an adb or F-Droid-style install counts as
     sideloaded (the bridge already holds overlay and usage access after an adb
     install) (T7).
   - **Robustness:** depends on Muse keeping that content-desc. Expect breakage on Muse
     updates; the bridge must fail safe (no tap if the node isn't found) and say so
     in the event. Fold and split screen: bounds come from the live node, so a layout
     change is handled, but the Razr+ cover screen and split screen are untested
     (T9).
   - **Security cost:**
     - It reverses the v1 non-goal quoted above ("no accessibility automation, …
       no screen control").
     - **What Android grants is reading and tapping any foreground window.** The
       service's `packageNames` filter only limits which apps' events it receives,
       and the service can change it at runtime; `canRetrieveWindowContent` is one
       on/off for the active window. So "only Muse, only that node, only after a
       wake" is a promise of the bridge's own code, not something Android enforces.
       A compromised bridge build gets the full capability.
     - **Audio:** Android treats accessibility services as privileged for audio
       capture. During a call an accessibility service can still capture, and with
       the service's UI on top both it and the other app receive audio
       ([Android](https://developer.android.com/media/platform/sharing-audio-input)).
       So a bridge that hosts this service could keep hearing during Muse's call. The
       "stop `AudioRecord` before launch" invariant (section 2.2) is what keeps it
       from doing so; T5 checks it.
     - On a family phone it's exactly the capability scams look for, and enabling it
       trains the owner to accept the "restricted settings" warning.
     - Code-level limits that still help: the package filter, acting only within
       10 s of a wake, no command can trigger it (only the local wake path), and a
       separate owner switch.
3. **Tasker + AutoInput.** AutoInput is an accessibility-based Tasker plugin, free with
   ads or paid ([Play data](https://appgoblin.info/apps/com.joaomgcd.autoinput)), that
   clicks by text or coordinates. With the native tap test approved, it's now only a
   fallback way to measure reliability over many tries without writing the service.
   Not for family phones (another app with accessibility rights, and Tasker was
   removed from the pilot phone).
4. **ADB / Shizuku: ruled out for a frozen family deployment.** Non-root Shizuku has
   to be restarted after every reboot with wireless debugging turned on
   ([Shizuku manual](https://shizuku.rikka.app/guide/setup/)); developer options stay
   on, which is a larger hole than an accessibility service. An external controller
   with adb needs a cable or wireless debugging. Fine for test-moto, not for an owner.
5. **Fallbacks (not equivalent, labeled as such):**
   - Wake, launch Muse, and a spoken prompt "Tap the phone button to talk". One touch.
   - Muse as default assistant plus the corner-swipe: opens the overlay, still one tap.
   - Bridge-native voice capture (record a question, send a transcript onward):
     breaks the "no audio sent" rule and duplicates Muse; not recommended.

### 3.2 Sequence and timing

1. Wake detected (not in refractory, Stop everything off).
2. Stop and release the `AudioRecord`; announce "Opening Muse: you said Hey Muse".
3. `launch muse` through the existing path; the existing check waits up to 5 s for
   Muse in front.
4. (Part B) Wait for the `"Start voice call"` node, up to 3 s; tap it.
5. Watch for the call: another app capturing (`AudioManager.getActiveRecordingConfigurations()`
   shows a client) within 3 s means started.
6. Publish the event with the result; log it.
7. Resume the `AudioRecord` only after no other app has captured for 10 s (the call
   ended).

| Case | What happens | Event `result=` |
| --- | --- | --- |
| Launched, in front (Part A only) | Done | `ok` |
| Muse not installed | Nothing announced, as for a command | `not-installed` |
| "Display over other apps" off | Nothing done | `cannot-launch` |
| Can't show the announcement | Nothing done | `cannot-announce` |
| Muse not in front within 5 s | No tap | `blocked` |
| Phone locked | The bridge can't unlock. Launching behind the lock screen is not verified (T8); expected: the owner sees the unlock prompt. Part B does nothing. Announce "Unlock to talk to Muse" | `locked` |
| Node not found (Muse updated) | No tap; announce "Tap the phone button" | `button-not-found` |
| Tap sent, no capture seen within 3 s | Logged for tuning | `no-call` |
| Call started (Part B) | Done | `ok` |
| Stop everything on | Not listening, so no wake | (`disabled` if a wake raced the switch) |
| Mic busy (a call, another recorder) | Don't trigger while `MODE_IN_CALL`/`MODE_IN_COMMUNICATION` | none |

### 3.3 Accessibility path, end to end

Scoped at the maintainer's request (header, item 2). Not built.

**Enrollment: personal phone.** The owner (or helper) installs the bridge, turns the
service on in Settings > Accessibility, passes "Allow restricted settings" if
Android blocks it (section 3.1, T7), and accepts Android's own warning that the
service can see the screen and act for them. Google's help says such access lets an
app "read screen content and interact with apps on your behalf"
([Google help](https://support.google.com/android/answer/12623953)). There's no way to
turn it on without a person in Settings, and the owner can turn it off any time.

**Enrollment: managed phone.** A device owner or work-profile admin can restrict which
accessibility services may run: `permittedAccessibilityServices` "Specifies
permitted accessibility services. If the field is not set, any accessibility service
can be used", and on a work profile it "affects both the personal profile and the
work profile" ([Android Management API](https://pub.dev/documentation/googleapis/13.1.0/androidmanagement_v1/Policy/permittedAccessibilityServices.html)).
That's an allowlist, not a grant: no documented API was found that turns a service on
without the user (not verified further). For a family phone, making the bridge's
helper a device owner would need a factory reset and gives the helper far more than
this feature; not recommended.

**What the service must see and do.** Receive window events for `com.facebook.aura`
only; read Muse's active window to find the `"Start voice call"` node and its bounds;
dispatch one tap gesture. Nothing else: no text reading beyond that node, no other
app, no global actions. Android won't hold the service to that (section 3.1).

**Play policy** ([Google Play](https://support.google.com/googleplay/android-developer/answer/10964491)):
the bridge isn't on Play, but this decides whether it ever could be.
- `isAccessibilityTool` is only for apps whose primary purpose is helping people with
  disabilities; "automation tools, assistants" are named as not qualifying. The bridge
  wouldn't qualify.
- So it would need an in-app prominent disclosure with affirmative consent, separate
  from other disclosures, and a Play Console declaration with a video of that flow.
- Play prohibits use that lets an app "autonomously initiate, plan, and execute
  actions or decisions"; deterministic, rule-based automation following a fixed,
  human-defined script is allowed. A wake word that taps one fixed button fits the
  second, but Play's review would decide. No 2026-dated change was found on the page.

**Android restrictions.** Restricted settings for sideloaded installs (section 3.1);
the service can be killed or turned off by the owner, Android, or battery tools, so the
bridge must check it's enabled before relying on it and say so in the event; Android
15+ may add confirmation steps (T7).

**Security surface.** Section 3.1: the real capability is reading and tapping any
foreground window, plus privileged audio capture. The bridge's command path must never
reach the service, and the code limits are promises only.

**Maintenance across Muse updates.** The node's content-desc and class are Muse's
internals and can change in any update, as can the screen the launch lands on.
- Each Muse update needs a re-check on test-moto: the voice-intent enumeration
  (section 1) and a tap test.
- The bridge fails safe (`button-not-found`) and the event says so, so Muse and the
  owner learn of a break at once.
- Expect a fix every few Muse releases; each is small (a new selector), but each needs
  a release of the bridge to every phone.

**Effort.** Native service 3 to 5 days, plus QA and a security review; enrollment docs
for helpers 1 day; per Muse update about half an hour of checks, plus a release when it
breaks.

## 4. Recommendation

1. **Part A** (proof of concept approved, in progress): sherpa-onnx on test-moto, then
   pick the production engine from T4 (microWakeWord, or openWakeWord if microWakeWord
   is as weak as LiveKit's benchmark suggests). Owner switch default off; default gate
   "while charging or screen on"; Stop everything stops listening and drops the mic.
2. **Part A alone already helps:** "Hey Muse" opens Muse and the event tells Muse
   which phone, so Muse can greet the owner by voice on the right phone. With the
   spoken prompt fallback, the owner needs one tap.
3. **Part B:** run the approved tap test on test-moto. If the tap reliably starts the
   call, the maintainer decides on the native service from section 3.3. Recommend it
   for the maintainer's own phones only; for family phones, the one-tap fallback,
   unless the maintainer decides otherwise.
4. **Protocol:** add the wake event to protocol 0.4 alongside the receipt ids.

### 4.1 Effort (Dev time, estimates)

| Piece | Effort |
| --- | --- |
| sherpa-onnx proof of concept on test-moto (FGS type, permission, gate, log) | 2 to 3 days |
| Production model training and tuning (microWakeWord or openWakeWord) | 1 to 2 days, plus a GPU or Colab |
| Production wake module, owner switch, STOP, event, privacy test, docs | 4 to 6 days |
| Battery runs (calendar, mostly unattended) | about 1 week |
| Part B tap test (approved) | half a day |
| Native accessibility service (if approved) | 3 to 5 days, plus QA and a security review |
| Protocol 0.4 text and server-side parser (with mcp-phone-commands) | 1 day |

### 4.2 How this answers "which phone"

Muse can't tell which phone a chat comes from. The wake event names the phone (the
topic is per device, and the event can add `device=<slug>`), and it arrives seconds
before the call starts. Muse can match the two by time, so "launch X" during that
conversation defaults to that phone.

## 5. Test plan (none of this is verified yet)

| # | Check | Where |
| --- | --- | --- |
| T1 | APK size growth and native libraries for sherpa-onnx and the TFLite runtime | build |
| T2 | Microphone FGS: starting from the notification action works in the background; from `BOOT_COMPLETED` throws, as documented; re-adding the microphone type from the background after dropping it throws (suspected) | test-moto |
| T3 | Listening keeps working screen off, locked, and in Doze for 6 h | test-moto |
| T4 | False accepts over 8 h of TV and talk at 2 m; misses over 50 tries at 1 m and 3 m; per engine | test-moto |
| T5 | Which audio source Muse's call uses; that an ordinary bridge gets silence, not an error, if still capturing; that the bridge's `AudioRecord` is stopped during the call, including with the accessibility service enabled | test-moto, during the approved tap test's call |
| T6 | No audio files written, no extra network bytes, after 1 h of listening | test-moto |
| T7 | Restricted settings for the bridge's accessibility service: adb install vs F-Droid-style install; whether a lock-screen confirmation or Enhanced Confirmation Mode applies | test-moto |
| T8 | `launch muse` while locked: what the owner sees | test-moto |
| T9 | `"Start voice call"` node and tap on the Razr+ main and cover screens, and split screen | pilot phone, only after the maintainer decides |
| T10 | Battery runs, section 2.3 | test-moto, then the pilot phone with the maintainer's go |
| T11 | Tap reliability beyond the approved single tap test (repeated real calls) | test-moto, needs the maintainer's OK: each starts a real call on the maintainer's account |

## 6. Open questions for the maintainer

1. Default gate: "while charging or screen on", or always on for your own phone?
2. Is a permanent mic indicator acceptable while listening?
3. After the approved tap test: may Dev start repeated real Muse calls on test-moto
   for T11?
4. Part B: after reading section 3.3, is the native accessibility service acceptable
   on your own phones? On family phones?
5. The wake phrase: "Hey Muse", or something less likely in normal speech (and less
   likely to collide with Muse's own name in conversation)?
6. Add the wake event to protocol 0.4 together with receipt ids?
