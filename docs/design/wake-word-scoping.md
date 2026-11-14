# Hands-free start of a Muse voice conversation: scoping

**Status:** scoping only, 2026-11-13. No code. Implementing either part needs the
maintainer's explicit go, and an accessibility service needs its own decision.
**Asked for by:** Muse, at the maintainer's direction, 2026-11-12 (Awareness
`handoff-muse-android-bridge-wake-word-scoping-2026-11-12`).
**Goal:** the owner says "Hey Muse", and a Muse voice conversation is already
running, with no touch anywhere.

The chain has two links, and they are independent:

- **Part A**, the wake word: the bridge hears the phrase on the phone and runs its
  existing `launch muse` action.
- **Part B**, the zero-touch start: after Muse is in front, something starts the
  voice call that the phone button in the Muse UI starts today.

Part A is ordinary engineering with known costs. Part B is the hard part: the only
working route found is an accessibility service, which reverses a v1 non-goal.

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

| Engine | License | "Hey Muse" path | Android | False accepts quoted | Verdict |
| --- | --- | --- | --- | --- | --- |
| [microWakeWord](https://github.com/OHF-Voice/micro-wake-word) | Apache-2.0 | Train a model with the project's notebook on synthetic samples from Piper TTS plus published negative features; "intended for advanced users" | TFLite (quantized streaming model, inference every 30 ms). Shipped inside the Home Assistant Android app, which [says](https://www.home-assistant.io/voice_control/android/) detection "works even when your device is locked or the app is in the background" | No per-model figure published; training targets a false-accepts-per-hour ceiling first, then accuracy ([docs](https://docsearch.algolia.com/mcp/docs/repo/ohf-voice/micro-wake-word)). One community model: ~1.2 false wakes/h, 15.7% misses at cutoff 0.99 ([thread](https://community.home-assistant.io/t/custom-microwakeword-models-needed-vance-hex-mochi-personal-voice-samples-included/1009193)) | **Recommended** for production |
| [sherpa-onnx KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html) | Apache-2.0 ([repo](https://github.com/k2-fsa/sherpa-onnx)) | No training: open vocabulary. Write the phrase as tokens (`text2token`) with a boost score and trigger threshold | Kotlin/Java API, Android arm64/arm32; prebuilt KWS APK. English model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` | None published; only the threshold trade-off | **Recommended for the proof of concept**: "Hey Muse" works on day one |
| [openWakeWord](https://github.com/dscripka/openWakeWord) | Code Apache-2.0; **pretrained models CC BY-NC-SA 4.0** | Colab notebook trains a basic model in under an hour on synthetic clips | ONNX/TFLite; README mentions no Android or mobile runtime | Target "<0.5/hour false-accept, <5% false-reject" with tuning; a competitor measured 8.5/h ([LiveKit](https://github.com/livekit/livekit-wakeword), vendor claim) | Possible, but more work on Android than microWakeWord and no Android precedent |
| Porcupine (Picovoice) | Commercial | Console trains a phrase in seconds | Android SDK | 97% acceptance at 1 false alarm per 10 h ([benchmark](https://picovoice.ai/docs/benchmark/wake-word/), Raspberry Pi) | **Ruled out.** Picovoice ended the Free Tier on 2026-06-30, disabled its keys, says "the SDK does not run without a valid key" and "there is no non-commercial tier planned" ([report quoting Picovoice](https://community.home-assistant.io/t/fyi-picovoice-confirmed-free-tier-accesskeys-will-stop-working-after-june-30-2026/1012744)). A third party lists paid plans from $6,000 a year ([Activepieces](https://www.activepieces.com/blog/picovoice-pricing-2026-free-foundation-enterprise), not confirmed on Picovoice's page) |
| Android's own hotword API (`AlwaysOnHotwordDetector`, `HotwordDetectionService`) | n/a | n/a | Only the current voice-interaction service (the default assistant) can use it, and it needs `CAPTURE_AUDIO_HOTWORD`, which the system grants ([AOSP](https://android.googlesource.com/platform/frameworks/base/+/335af8b598b3d94c537045d8207b773a56faeeae)) | n/a | **Ruled out.** The bridge isn't the assistant, and the low-power DSP path isn't open to third-party apps |

Library size isn't published for either recommended engine; measure the APK growth in
the proof of concept (test plan T1).

### 2.2 Android rules that shape the design

Sources: [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15).

1. **Permissions:** `RECORD_AUDIO` (runtime, granted by the owner) and
   `FOREGROUND_SERVICE_MICROPHONE`; the service declares
   `foregroundServiceType="specialUse|microphone"` and calls `startForeground` with
   the microphone type only while listening.
2. **While-in-use rule (Android 14+):** a foreground service with the microphone
   type can't be *created* while the app is in the background, and can't be started
   from `BOOT_COMPLETED` (since Android 14 for microphone). Calling it in the
   background throws `SecurityException`.
   - Exemptions that fit the bridge: the start comes from **the owner tapping a
     notification action**, or happens while the bridge's own screen is visible.
   - The bridge's `SYSTEM_ALERT_WINDOW` exemption doesn't cover while-in-use
     permissions, and on targetSdk 35+ it needs a visible overlay window anyway.
   - Consequence: **after every reboot, listening stays off until the owner taps
     "Listen" on the status notification or opens the bridge.** Command handling
     still starts at boot as today. This matches issue #14's boundary and can be
     said in the same place in the docs.
3. **Once running, listening continues** in the background and on the lock screen
   (the Home Assistant app does this with microWakeWord). Not yet verified for this
   bridge on test-moto (T3).
4. **Privacy indicator:** on Android 12+ a non-system app using the microphone shows
   the mic indicator whenever the mic is unmuted ([AOSP](https://source.android.com/docs/core/permissions/privacy-indicators)).
   While listening, the green dot is on all the time. That is honest and can't be
   avoided; the owner must be told before turning listening on.
5. **No time limit:** the `dataSync` and `mediaProcessing` 6-hour limits don't apply
   to `specialUse` or `microphone` services.
6. **Sharing the mic with Muse** ([Android](https://developer.android.com/media/platform/sharing-audio-input)):
   two ordinary apps never capture at once. The app with a visible UI wins over a
   background app, and a privacy-sensitive source (`VOICE_COMMUNICATION`, usually
   used by voice calls) wins over both. So once Muse's call starts, the bridge would
   get silence, not an error. The bridge still **stops its own `AudioRecord` before
   launching Muse**, so Muse never competes, and it registers an
   `AudioRecordingCallback` to see `isClientSilenced()` as a signal.
   Which audio source Muse's call uses is not verified (T5).

### 2.3 When to listen (gates)

| Gate | Battery | Covers the goal | Note |
| --- | --- | --- | --- |
| Always on | Highest | Fully | Green dot always on |
| Screen on (unlocked or not) | Low | Partly: not with the screen off | Simple, uses `ACTION_SCREEN_ON/OFF` |
| Charging only | No battery cost | At a desk or bedside only | Good for a family phone on a stand |
| Armed for N minutes | Low | Only after a tap | Defeats hands-free |
| Owner's schedule (e.g. 7:00 to 22:00) | Medium | In the window | Combine with charging |

Battery: no published figure for a phone running a TFLite wake model on the CPU. The
Home Assistant docs only say it "has a noticeable impact on battery life", more than
"Ok Google", because third-party apps keep the CPU on instead of using the low-power
hardware. One developer report went from ~25%/h (naive) to ~0.02%/min (~1.2%/h)
after optimization, with several models at once ([DEV](https://dev.to/programmingcentral/the-secret-to-always-on-audio-ai-why-your-android-app-needs-a-dsp-and-how-to-implement-it-5589)).
Planning figure, to be replaced by measurement: **1 to 3% of battery per hour of
listening**. Always-on would cost about 25 to 70% of a charge a day, so the
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

- **Threshold** tuned per phone from a short calibration: the owner says the phrase
  five times; the bridge logs scores (not audio) and suggests a threshold.
- **Second check:** require two consecutive positive frames (microWakeWord's sliding
  window) and a 3-second refractory period after a trigger.
- **False accept cost** is small: Muse opens. Every trigger is announced and logged,
  so the owner sees them; a counter in the status report shows the rate.
- **False reject** shows as "I said it and nothing happened". The log keeps a
  near-miss count (score above half the threshold), so tuning has data.
- Target from the engines' own targets: under 0.5 false accepts per hour of speech
  around the phone, under 10% misses at 1 m in a quiet room (T4 measures both).

### 2.5 STOP, announce, log, receipt

- **Owner control:** a new owner switch **"Listen for Hey Muse"**, default off. Only
  the owner turns it on; it's not settable by any command. Turning it on shows what
  the green dot means.
- **Stop everything** also stops listening: `AudioRecord` stopped and released, and
  the service calls `startForeground` with `specialUse` only, so the mic indicator
  goes off at once. Turning Stop everything off doesn't resume listening; the owner
  turns the switch on again (same rule as STOP: re-enabling is the owner's act).
- **Stop family** doesn't affect the wake word: it's the owner's own voice, the
  owner channel.
- **Announce:** the same short announcement as a `launch muse` command ("Opening
  Muse"), so a false trigger is visible.
- **Log:** `ok (local) wake launch muse score=<0.00-1.00>` in the on-phone log.
- **Receipt / event:** the bridge publishes an event on its ack topic, so Muse knows
  which phone the conversation is starting on (section 4.2).

### 2.6 Protocol

- A local trigger isn't a command, so it gets no receipt today. Proposal: a new
  **event** line on `mab-<device>-ack`, for example
  `event wake launch-muse id=<local id> at=<time> result=<ok|cannot-launch|blocked>`.
- mcp-phone-commands plans **protocol 0.4 for receipt ids**. Ship the wake event in
  the same 0.4 bump: an event carries a bridge-generated id in the same format, so
  one id scheme covers both, and the server needs one parser change, not two.
- `status report` gains `wake=<on|off|gated>` and a 24 h `wakes=<n>` counter.
- PROTOCOL.md §7 gains the listening switch and its STOP rule; §8 gains the mic in
  the threat model (below).

### 2.7 Privacy invariant: no audio stored or sent

**Rule:** audio lives only in a fixed in-memory ring buffer (about 1.5 s) inside the
wake module, is overwritten continuously, and is never written to disk, logged, or
sent. Only the score and a timestamp leave the module.

Enforce it by structure and tests, since the app still needs `INTERNET` for ntfy:

- The wake code is one package whose only output is a callback with a score; it has
  no reference to the network, file or log classes.
- A unit test reads that package's sources and fails on any import of `java.io.File*`,
  `java.net`, `okhttp`, `android.util.Log` or the log writer (same style as
  `ManifestQueriesTest`).
- No debug "save sample" feature, in debug builds either.
- Device check (T6): `run-as` file listing before and after an hour of listening shows
  no new files; network bytes from the bridge stay at the ntfy baseline.

### 2.8 Threat model change (Part A)

The bridge gains the microphone. A compromised bridge build could record. The command
path doesn't change: no command can turn listening on, change the threshold, or read
scores. The green dot and the owner switch are the owner's assurance. For family
phones, listening stays off unless the owner (and helper) choose it.

## 3. Part B: start the voice call with no touch

### 3.1 Options, ranked

1. **An exported intent or deep link: no.** None exists in 16.0.0.11.159 (section 1).
   Re-check on every Muse update (cheap: the same `dumpsys` and manifest read).
2. **An accessibility service in the bridge (works, highest cost).**
   - Mechanism: after `launch muse` and a confirmed foreground (the bridge already
     checks with usage access), find the node with content-desc `"Start voice call"`
     in Muse's window. Since it's `clickable=false`, tap its bounds center with
     `dispatchGesture` (needs `canPerformGestures`, API 24+). `ACTION_CLICK` on a
     clickable parent is worth trying first, but none was found.
   - Never act on any other app: the service config lists `packageNames` =
     `com.facebook.aura`, it acts only within 10 s of a wake trigger, and only on that
     one node.
   - **Restricted settings:** on Android 13+, an accessibility service in a
     sideloaded app is blocked until the owner opens App info, menu, "Allow
     restricted settings", and confirms with the screen lock
     ([Bayton](https://bayton.org/android/android-13-restricted-permissions/)); Android
     15's CDD keeps this on by default, and Enhanced Confirmation Mode may tighten it.
     Whether an adb or F-Droid install counts as sideloaded here is not verified (T7).
   - **Robustness:** depends on Muse keeping that content-desc. Expect breakage on Muse
     updates; the bridge must fail safe (no tap if the node isn't found) and say so
     in the receipt. Fold and split screen: bounds come from the live node, so a
     layout change is handled, but the Razr+ cover screen and split screen are
     untested (T9).
   - **Security cost:** an accessibility service can read all screen content and tap
     anywhere. It reverses the v1 non-goal "launch only, no UI control". On a family
     phone it's exactly the capability scams look for, and enabling it trains the
     owner to accept the "restricted settings" warning. Mitigations: the narrow
     package filter, no `canRetrieveWindowContent` beyond that package, no command
     can trigger it (only the local wake path), and a separate owner switch.
3. **Tasker + AutoInput proof of concept.** AutoInput is an accessibility-based Tasker
   plugin, free with ads or paid ([Play data](https://appgoblin.info/apps/com.joaomgcd.autoinput)),
   that clicks by text or coordinates. Proves option 2's reliability on test-moto in
   about a day before any native work. Not for family phones (another app with
   accessibility rights, and Tasker was removed from the pilot phone).
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

1. Wake detected (score over threshold, not in refractory, STOP all off).
2. Stop and release the `AudioRecord`; announce "Opening Muse".
3. `launch muse` through the existing path; wait up to 3 s for Muse in front.
4. (Part B) Wait for the `"Start voice call"` node, up to 3 s; tap it.
5. Watch for the call: another app capturing (`AudioManager.getActiveRecordingConfigurations()`
   shows a client) within 3 s means started.
6. Publish the event with the result; log it.
7. Resume listening only after no other app has captured for 10 s (the call ended).

| Failure | What happens | Event `result=` |
| --- | --- | --- |
| Phone locked | The bridge can't unlock. Launching from the lock screen is not verified (T8); expected: Muse opens behind the keyguard, so the owner sees the unlock prompt. Part B does nothing. Announce "Unlock to talk to Muse" | `locked` |
| Muse not in front after 3 s | No tap | `muse-not-foreground` |
| Node not found (Muse updated) | No tap; announce "Tap the phone button" | `button-not-found` |
| Mic busy (call, other recorder) | Listening was silenced; nothing to do. Don't trigger while `MODE_IN_CALL`/`MODE_IN_COMMUNICATION` | none |
| Tap sent, no capture seen | Logged for tuning | `no-call` |
| STOP everything on | Not listening | none |

## 4. Recommendation

1. **Build Part A now, in two steps,** if the maintainer agrees: a proof of concept on
   test-moto with sherpa-onnx (no training, "Hey Muse" at once), then a trained
   microWakeWord model for production. Owner switch default off; default gate
   "while charging or screen on"; Stop everything stops listening and the mic.
2. **Part A alone already helps:** "Hey Muse" opens Muse and the event tells Muse
   which phone, so Muse can greet the owner by voice on the right phone. With the
   spoken prompt fallback, the owner needs one tap.
3. **Part B: prove it with Tasker + AutoInput on test-moto first.** Only if it's
   reliable over a week of Muse updates, put the native accessibility service to the
   maintainer as a separate decision, with the threat-model change in section 3.1.
   Recommend it for the maintainer's own phones; for family phones, recommend the
   one-tap fallback unless the maintainer decides otherwise.
4. **Protocol:** fold the wake event into protocol 0.4 with the receipt ids.

### 4.1 Effort (Dev time, estimates)

| Piece | Effort |
| --- | --- |
| sherpa-onnx proof of concept on test-moto (FGS type, permission, gate, log) | 2 to 3 days |
| microWakeWord "Hey Muse" model training and tuning | 1 to 2 days, plus a GPU or Colab |
| Production wake module, owner switch, STOP, event, privacy test, docs | 4 to 6 days |
| Battery runs (calendar, mostly unattended) | about 1 week |
| Tasker + AutoInput Part B proof of concept | 1 day |
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
| T2 | Microphone FGS: starting from the notification action works in the background; from `BOOT_COMPLETED` throws, as documented | test-moto |
| T3 | Listening keeps working screen off, locked, and in Doze for 6 h | test-moto |
| T4 | False accepts over 8 h of TV and talk at 2 m; misses over 50 tries at 1 m and 3 m | test-moto |
| T5 | Which audio source Muse's call uses; that the bridge gets silence, not an error, if it were still capturing | test-moto, with the maintainer's OK for a real call |
| T6 | No audio files written, no extra network bytes, after 1 h of listening | test-moto |
| T7 | Restricted settings for the bridge's accessibility service, for an adb install and an F-Droid-style install | test-moto |
| T8 | `launch muse` while locked: what the owner sees | test-moto |
| T9 | `"Start voice call"` node and tap on the Razr+ main and cover screens, and split screen | pilot phone, only with the maintainer's go |
| T10 | Battery runs, section 2.3 | test-moto, then the pilot phone with the maintainer's go |
| T11 | AutoInput tap on `"Start voice call"`: reliability over 50 tries | test-moto, real calls need the maintainer's OK (the phone is signed in to the maintainer's Muse account) |

## 6. Open questions for the maintainer

1. Go for the Part A proof of concept on test-moto?
2. Default gate: "while charging or screen on", or always on for your own phone?
3. Is a permanent mic indicator acceptable while listening?
4. May Dev start real Muse voice calls on test-moto for T5 and T11 (your account)?
5. Part B: try Tasker + AutoInput on test-moto first, as recommended?
6. Long term, is an accessibility service in the bridge acceptable on your own phones?
   On family phones?
7. The wake phrase: "Hey Muse", or something less likely in normal speech (and less
   likely to collide with Muse's own name in conversation)?
8. Fold the wake event into protocol 0.4 together with receipt ids?
