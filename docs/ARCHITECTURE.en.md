# On-device Voice Transcription Assistant: Architecture

[简体中文](ARCHITECTURE.zh-CN.md) | [English](ARCHITECTURE.en.md)

This document is for developers who want to read, modify, or port the v0.1.0 Android implementation. It describes component ownership, threading, resource lifetimes, event semantics, and extension boundaries.

## 1. Goals and scope

The current implementation:

- captures continuous 16 kHz mono PCM on Android;
- displays low-latency streaming hypotheses while recording;
- runs SenseVoice as a second pass after each segment;
- visually distinguishes provisional and second-pass text;
- supports long sessions, Home/screen-off operation, stop-and-drain, waveform feedback, and editing after stop;
- does not use Android SpeechRecognizer or a cloud ASR service.

Independent VAD, persistence, diarization, LLM correction, and iOS are future work. Those features should be introduced behind new interfaces instead of being added to `MainActivity` or the microphone adapter.

## 2. System context

~~~mermaid
flowchart TD
    U["User"] --> UI["MainActivity / rendering and actions"]
    UI <--> SVC["VoiceRecognitionService / session owner"]
    SVC --> MIC["Android AudioRecord"]
    SVC --> RT["sherpa-onnx runtime"]
    RT --> OM["Streaming Zipformer"]
    RT --> FM["SenseVoice"]
~~~

Android-specific code provides device access and lifecycle support:

- `AudioRecord` captures PCM;
- Service, notification, and WakeLock support continued recording;
- Activity handles visual rendering and user actions;
- sherpa-onnx, models, and transcript types implement the recognition pipeline.

## 3. Components and ownership

~~~mermaid
classDiagram
    class MainActivity {
        +render(snapshot)
        +start/stop/copy/new
    }
    class VoiceRecognitionService {
        +own session
        +publish snapshots
        +control foreground state
    }
    class StreamPcmRecorder {
        +start(callbacks)
        +stop()
    }
    class AndroidOnlineAsrEngine {
        +startSession()
        +accept(frame)
        +stopSession()
    }
    class AndroidVoiceCore {
        +decodePcm(samples, rate)
    }
    MainActivity --> VoiceRecognitionService : LocalBinder
    VoiceRecognitionService *-- StreamPcmRecorder
    VoiceRecognitionService *-- AndroidOnlineAsrEngine
    VoiceRecognitionService *-- AndroidVoiceCore
~~~

| Component | Owns | Must not own |
|---|---|---|
| `MainActivity` | permissions, controls, colors, scrolling, editing, rendering | model loading, PCM queues, recognizer state |
| `VoiceRecognitionService` | session, state machine, foreground state, scheduling, drain/stop | View mutation |
| `StreamPcmRecorder` | PCM16 to normalized Float frames | VAD, endpoint, ASR, UI |
| `AndroidOnlineAsrEngine` | Online Recognizer/Stream, Partial, endpoint | Android lifecycle |
| `AndroidVoiceCore` | reusable SenseVoice Recognizer and per-segment decoding | segmentation and UI |
| `TranscriptDocument` | immutable stable segments and current Partial | recording and inference |
| `WaveformView` | peak/RMS animation | speech detection |

## 4. End-to-end data flow

~~~mermaid
flowchart TD
    A["PCM16 / AudioRecord"] --> B["FloatArray / [-1, 1]"]
    B --> C["AudioFrame / about 100 ms"]
    C --> D["Bounded Semaphore"]
    D --> E["onlineWorker"]
    E --> F["Online Partial"]
    E --> G["SegmentPcmBuffer"]
    G --> H["Endpoint / 20 s"]
    H --> I["SenseVoice Final"]
    F --> J["TranscriptDocument"]
    I --> J
    J --> K["VoiceSessionSnapshot"]
    K --> L["MainActivity"]
~~~

### Core parameters

| Parameter | Value | Meaning |
|---|---:|---|
| Sample rate | 16,000 Hz | Shared by capture and both recognizers |
| Channels | mono | One input channel |
| Capture encoding | PCM 16-bit | AudioRecord input |
| Internal samples | FloatArray | Normalized to roughly [-1.0, 1.0] |
| Frame duration | about 100 ms | Recorder-to-Service callback granularity |
| Inference threads | 2 | Configured per recognizer |
| Queue permits | 20 | Maximum submitted but unfinished frames |
| Permit wait | 200 ms | Explicit backpressure failure threshold |
| Segment safety limit | 20 s | Closes one segment; not the session |
| UI progress interval | about 250 ms | Avoid excessive rendering |
| Notification interval | about 5 s | Avoid excessive notification updates |

Twenty permits at about 100 ms per frame represent roughly two seconds of queued audio. This is unrelated to the separate 20-second segment boundary.

## 5. Thread model

~~~mermaid
flowchart TD
    MAIN["Main thread / Activity / Service lifecycle"] -->|"commands"| SVC["Service coordination"]
    REC["Recorder thread / AudioRecord.read"] -->|"AudioFrame"| Q["Semaphore"]
    Q --> WORK["onlineWorker / single-threaded"]
    WORK -->|"immutable snapshot"| REF["AtomicReference"]
    REF -->|"coalesced post"| MAIN
~~~

| Execution context | Work |
|---|---|
| Main thread | lifecycle, permissions, binding, controls, notification coordination, View updates |
| Recorder thread | blocking PCM reads, normalization, peak calculation |
| `onlineWorker` | online decode, segmentation, SenseVoice second pass, Stream release |

Thread invariants:

1. Model loading and decode never run on the main thread.
2. Mutable Online Recognizer/Stream operations are confined to `onlineWorker`.
3. The second pass currently uses the same worker, preventing native-state races.
4. The recorder never mutates transcript collections.
5. Activity reads immutable snapshots only.
6. Binder calls execute on the caller's thread unless explicitly handed off.

## 6. Service identities and lifecycle

`VoiceRecognitionService` combines bound, started, and foreground identities.

~~~mermaid
stateDiagram-v2
    [*] --> Created: bindService(BIND_AUTO_CREATE)
    Created --> Bound: Activity visible
    Bound --> StartedFG: user starts recording
    StartedFG --> StartedOnly: Activity unbinds / Home
    StartedOnly --> StartedFG: Activity binds again
    StartedFG --> Bound: session stops
    Bound --> Destroyed: unbound and not started
    StartedOnly --> Destroyed: stopSelf and no binding
~~~

| Identity | Responsibility |
|---|---|
| Bound | snapshots and commands between Activity and Service |
| Started | Service may remain after Activity unbinds |
| Foreground | visible recording notification and background-policy compliance |
| Partial WakeLock | keeps required CPU work active while the screen is off |

A foreground Service is not a worker thread and is not immortal. Process death, permission restrictions, and device policy still require explicit failure handling.

## 7. Session state machine

~~~mermaid
stateDiagram-v2
    [*] --> MODEL_LOADING
    MODEL_LOADING --> READY: recognizers ready
    MODEL_LOADING --> ERROR: initialization failed
    READY --> STARTING: user start
    STARTING --> STREAMING: Stream and recorder ready
    STARTING --> STOPPING: stop requested
    STREAMING --> STOPPING: user or notification stop
    STREAMING --> ERROR: capture or decode failure
    STOPPING --> READY: tail finalized
    ERROR --> STARTING: valid retry
    READY --> CLOSED: Service destroy
    ERROR --> CLOSED: Service destroy
~~~

`STOPPING` is required because the app still has to stop AudioRecord, process submitted frames, call `inputFinished()`, run the tail second pass, publish the final snapshot, release the Stream and WakeLock, leave foreground state, and verify that all Semaphore permits returned.

## 8. Streaming session sequence

~~~mermaid
sequenceDiagram
    participant A as MainActivity
    participant S as VoiceRecognitionService
    participant R as StreamPcmRecorder
    participant O as Online Engine
    participant V as SenseVoice

    A->>S: startForegroundService(ACTION_START)
    S->>O: startSession()
    S->>R: start(callbacks)
    loop every ~100 ms
        R->>S: AudioFrame(samples, 16000, timestamp)
        S->>O: accept(frame)
        O-->>S: Partial(segmentId, text), endpoint?
        S-->>A: snapshot(partial, peak, rms)
    end
    O-->>S: endpoint = true
    S->>O: resetAfterBoundary(segmentId)
    S->>V: decodePcm(segmentSamples, 16000)
    V-->>S: Final text and metrics
    S-->>A: snapshot(committed segment)
~~~

### Partial and Final

~~~mermaid
flowchart TD
    P1["Partial #7 / meeting at"] --> R["Replace draft"]
    P2["Partial #7 / meeting at three"] --> R
    R --> F["Final #7"]
    F --> A["Append stable segment / clear draft"]
~~~

- Partial is a growing hypothesis for one segment and must replace the previous hypothesis.
- Final closes a segment and must append.
- `segmentId` links the hypothesis, buffered PCM, and Final.
- Final source lets the UI distinguish a second-pass result from an online fallback.

## 9. Segmentation and second pass

~~~mermaid
flowchart TD
    PCM["Continuous PCM"] --> BUFFER["SegmentPcmBuffer"]
    BUFFER --> CHECK{"Boundary?"}
    CHECK -->|"no"| PCM
    CHECK -->|"online endpoint"| EP["endpoint count + 1"]
    CHECK -->|"20 seconds"| MAX["forced count + 1"]
    EP --> PASS["SenseVoice second pass"]
    MAX --> PASS
    PASS -->|"non-empty"| OFF["OFFLINE_SECOND_PASS"]
    PASS -->|"error / empty"| FALL["ONLINE_FALLBACK"]
~~~

The 20-second rule clears only the current segment buffer and resets online segment state. Capture continues, so it is not a 20-second recording limit.

The second pass currently blocks `onlineWorker`. New frames can queue within the 20 permits. Failure to acquire a permit within 200 ms is surfaced as an explicit error instead of silently dropping audio.

## 10. Backpressure

~~~mermaid
flowchart TD
    IN["New recorder frame"] --> TRY{"Permit within 200 ms?"}
    TRY -->|"yes"| SUBMIT["Submit to onlineWorker"]
    SUBMIT --> WORK["Decode / segment"]
    WORK --> FIN["finally: release permit"]
    TRY -->|"no"| ERROR["ERROR / timeout + 1"]
~~~

The release belongs in `finally`; otherwise any exception permanently reduces capacity.

| Metric | Healthy expectation |
|---|---|
| `pendingFrames` | transient changes, then 0 after stop |
| `maxPendingFrames` | below 20; lower is better |
| `backpressureTimeouts` | 0 |
| `availablePermits` | 20/20 after stop |
| `endpointSegments` | grows with natural pauses |
| `forcedSegments` | grows during uninterrupted speech |

## 11. Immutable snapshot publication

~~~mermaid
flowchart TD
    CHANGE["Recorder/worker change"] --> CAS["AtomicReference CAS / copy snapshot"]
    CAS --> FLAG{"dispatchPosted?"}
    FLAG -->|"no"| POST["Post one main-thread callback"]
    FLAG -->|"yes"| SKIP["Do not enqueue another callback"]
    POST --> LATEST["Read latest snapshot"]
    LATEST --> UI["Activity render"]
~~~

`dispatchPosted` coalesces notifications, not the latest state. The callback reads the newest value from `snapshotRef`. This keeps high-frequency waveform updates from flooding the main queue.

`val`, immutable lists, and `copy()` prevent Activity from observing a collection while Service is mutating it.

## 12. Transcript model

~~~mermaid
classDiagram
    class VoiceSessionSnapshot {
        +Long version
        +VoiceServiceState state
        +TranscriptDocument transcript
        +Float peak
        +Float rms
    }
    class TranscriptDocument {
        +List~TranscriptSegment~ committedSegments
        +Long? partialSegmentId
        +String partialText
        +Long revision
    }
    class TranscriptSegment {
        +Long segmentId
        +String text
        +FinalSource source
        +Double durationSeconds
        +String? speakerId
    }
    VoiceSessionSnapshot --> TranscriptDocument
    TranscriptDocument "1" o-- "0..*" TranscriptSegment
~~~

`speakerId` is reserved for a future diarization feature. Its presence does not mean diarization is currently implemented.

### Conservative sanitation

~~~mermaid
flowchart TD
    RAW["Raw Final"] --> MARK["Remove model markers"]
    MARK --> SPACE["Collapse whitespace"]
    SPACE --> VALID{"Contains letter/digit?"}
    VALID -->|"no"| DROP["Discard"]
    VALID -->|"yes"| FILLER{"< 0.8 s short filler?"}
    FILLER -->|"yes"| DROP
    FILLER -->|"no"| KEEP["Keep segment"]
~~~

The foundation does not discard every one-character result, invent punctuation, or guess homophones. Context correction belongs in an optional post-processing layer and must preserve the original recognition text.

## 13. Resource lifetimes

| Resource | Created | Reused for | Released |
|---|---|---|---|
| Online Recognizer | Service model initialization | Service lifetime | Service `onDestroy()` |
| Online Stream | recording-session start | active session and segment resets | session stop |
| Offline Recognizer | Service model initialization | Service lifetime | Service `onDestroy()` |
| Offline Stream | each second-pass segment | one `decodePcm` call | `finally` |
| AudioRecord | recording start | active session | capture-loop `finally` |
| WakeLock | object prepared at Service creation | held only during active session | stop, failure, destroy |

Recognizers are expensive model containers and are reused. Streams contain per-input decoding state and are shorter-lived.

## 14. Source layout

~~~text
app/src/main/
├── AndroidManifest.xml
├── assets/                         # local models; excluded from normal Git
├── java/com/trendbot/voiceinputdemo/
│   ├── MainActivity.kt
│   ├── VoiceRecognitionService.kt
│   ├── VoiceServiceContract.kt
│   ├── StreamPcmRecorder.kt
│   ├── AndroidOnlineAsrEngine.kt
│   ├── AndroidVoiceCore.kt
│   ├── WaveformView.kt
│   ├── LogTags.kt
│   └── speech/
│       ├── AudioFrame.kt
│       ├── SegmentPcmBuffer.kt
│       ├── TranscriptDocument.kt
│       └── Transcription.kt
└── res/
    ├── layout/activity_main.xml
    ├── drawable/
    ├── mipmap-*/
    └── values/
~~~

## 15. Endpoint, VAD, and waveform

| Concept | Present? | Purpose |
|---|---|---|
| peak/RMS | yes | visual amplitude feedback; not speech detection |
| Online Endpoint | yes | model-driven segment boundary |
| 20-second boundary | yes | prevents unbounded segment buffering |
| Independent VAD | no | future speech/silence boundary source |

A future VAD should emit boundary events. It should not own the Recognizer or View.

## 16. Extension path

~~~mermaid
flowchart TD
    CORE["Existing capture + ASR"] --> DOC["TranscriptDocument"]
    DOC --> STORE["Note Repository / Room / files"]
    STORE --> LIST["List / search / tags"]
    DOC --> POST["PostProcessor"]
    POST --> RULE["Local lexicon / punctuation"]
    POST --> LLM["Optional LLM templates"]
    STORE --> SHARE["Share / export / merge"]
~~~

Recommended future boundaries:

- `NoteRepository` for note, audio, and metadata persistence;
- `PostProcessor` for versioned corrections;
- `TemplateRepository` for meeting, diary, and technical templates;
- `SpeakerLabel` for diarization output;
- `ExportService` for clipboard, Markdown, sharing, and backup.

Never overwrite the raw transcript irreversibly with a post-processed result.

## 17. iOS portability boundary

~~~mermaid
flowchart TD
    SHARED["Platform-neutral concepts / AudioFrame / Segment / Event"] --> AND["Android adapter / AudioRecord + Service"]
    SHARED --> IOS["Future iOS adapter / AVAudioEngine + background policy"]
    SHARED --> NATIVE["sherpa-onnx core / C/C++ / ONNX"]
~~~

Android Service, Binder, Notification, and WakeLock should not be shared with iOS. Reusable contracts include PCM format, Partial/Final/`segmentId` semantics, TranscriptDocument, segmentation and backpressure rules, resource ownership, metrics, and test corpora.

## 18. Security, privacy, and supply chain

- The current Manifest does not require INTERNET.
- Raw PCM is not written to a file.
- Release logs should not print full sensitive transcripts.
- Keystores, passwords, `local.properties`, APKs, models, and AARs stay out of normal Git history.
- sherpa-onnx source is Apache-2.0; model licenses must be reviewed separately.
- Publishing the APK redistributes the embedded runtime and model weights, so applicable LICENSE/NOTICE files must be preserved.
- Adding an LLM or synchronization requires a new privacy design, user consent, retention policy, and delete path.

## 19. Regression criteria

Every meaningful change should verify:

- unit tests and Debug APK build;
- no startup/native crash;
- Partial replaces instead of appending duplicates;
- Final clears the matching Partial;
- pending=0 and permits=20/20 after stop;
- Home/screen-off recording continues;
- both auto-scroll states work;
- editing is disabled during capture and enabled after finalization.

Before a release, also run a 15–30 minute physical-device session, quiet/noisy phrases, numbers and English abbreviations, denied/regranted permissions, notification stop, Home/lock screen, repeated start/stop, signed APK verification, fresh install, and upgrade install.

## 20. Design rules in one page

1. Service owns the session; Activity is a replaceable display client.
2. The online pass optimizes responsiveness; the offline pass stabilizes text.
3. Partial replaces, Final appends, and `segmentId` establishes causality.
4. Reuse Recognizers; release Streams according to input lifetime.
5. Serialize native decode on one worker for predictable ownership.
6. Use bounded buffering and explicit overload errors; never silently drop PCM.
7. Publish immutable snapshots across the Service/UI boundary.
8. Twenty seconds limits one segment, not the full session.
9. Waveform feedback is not VAD.
10. Preserve raw recognition when adding corrections.

## References

- [Android Services](https://developer.android.com/develop/background-work/services)
- [Foreground services](https://developer.android.com/develop/background-work/services/fgs)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [sherpa-onnx Android documentation](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [Streaming Zipformer models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)
- [SenseVoice models](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)

