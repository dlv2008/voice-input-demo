# 端侧语音转录助手：架构与技术说明

[简体中文](ARCHITECTURE.zh-CN.md) | [English](ARCHITECTURE.en.md)

本文面向准备阅读、修改或移植代码的开发者。它描述 v0.1.0 阶段的 Android 架构、线程与资源约束、事件语义和后续扩展边界。

## 1. 设计目标与边界

### 1.1 当前目标

- 在 Android 设备上持续采集 16 kHz 单声道 PCM；
- 一边录音，一边显示低延迟的流式识别结果；
- 每个 segment 结束后运行 SenseVoice 第二遍识别；
- 将在线草稿和二遍结果明确区分；
- 支持较长会话、Home/息屏、停止排空、波形和停止后编辑；
- ASR 核心不依赖 Android SpeechRecognizer 或云端服务。

### 1.2 当前非目标

- 生产级语义纠错；
- 独立 VAD；
- 音频/笔记持久化；
- 说话人分离；
- LLM、同步和账号；
- iOS 实现。

这些非目标应通过新增边界实现，而不是把逻辑塞进 `MainActivity` 或录音适配器。

## 2. 系统上下文

~~~mermaid
flowchart TD
    U["用户"] --> UI["MainActivity / 显示与操作"]
    UI <--> SVC["VoiceRecognitionService / 会话所有者"]
    SVC --> MIC["Android AudioRecord"]
    SVC --> RT["sherpa-onnx Runtime"]
    RT --> OM["Streaming Zipformer"]
    RT --> FM["SenseVoice"]
~~~

Android SDK 只负责设备能力和生命周期：

- `AudioRecord` 获取 PCM；
- `Service`、Notification 和 WakeLock 支持后台录音；
- Activity 渲染和接收用户操作；
- sherpa-onnx、模型和转录数据结构负责识别核心。

## 3. 组件与职责

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

| 组件 | 唯一职责 | 不应承担 |
|---|---|---|
| `MainActivity` | 权限、按钮、颜色、滚动、编辑、快照渲染 | 模型加载、PCM 队列、识别资源 |
| `VoiceRecognitionService` | 会话所有权、状态机、前台服务、调度、停止排空 | 具体 View 操作 |
| `StreamPcmRecorder` | 将 AudioRecord PCM16 转成归一化 Float PCM 帧 | VAD、Endpoint、ASR、UI |
| `AndroidOnlineAsrEngine` | 在线 Recognizer/Stream 适配、Partial、Endpoint | Android 生命周期和 UI |
| `AndroidVoiceCore` | SenseVoice Recognizer 复用、每段二遍识别 | 分段策略和 UI |
| `TranscriptDocument` | 不可变的稳定段与当前 Partial | 录音和模型调用 |
| `WaveformView` | 根据 peak/RMS 绘制动画 | 判断是否有人声 |

## 4. 数据流

~~~mermaid
flowchart TD
    A["PCM16 / AudioRecord"] --> B["FloatArray / [-1, 1]"]
    B --> C["AudioFrame / 约 100 ms"]
    C --> D["Semaphore 有界入口"]
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

### 4.1 核心参数

| 参数 | 当前值 | 含义 |
|---|---:|---|
| Sample rate | 16,000 Hz | 两个识别器和录音适配器统一采样率 |
| Channel | mono | 单声道 |
| Source encoding | PCM 16-bit | AudioRecord 输入 |
| Internal samples | FloatArray | 归一化到约 [-1.0, 1.0] |
| Frame size | 约 100 ms | 每次回调给 Service 的 PCM 粒度 |
| ASR threads | 2 | Online 与 Offline 配置中的推理线程数 |
| Queue permits | 20 | 尚未处理的 AudioFrame 最大槽位数 |
| Permit wait | 200 ms | 背压超过该时间后明确失败 |
| Safety segment | 20 s | 强制结束当前 segment，不是会话上限 |
| UI progress refresh | 约 250 ms | 降低无意义界面刷新 |
| Notification refresh | 约 5 s | 减少通知更新 |

20 个槽位乘以约 100 ms，代表大约 2 秒待处理音频容量。它和 20 秒 segment 安全边界是两个不同概念。

## 5. 线程模型

~~~mermaid
flowchart TD
    MAIN["Main thread / Activity / Service 生命周期"] -->|"start/stop command"| SVC["Service 协调"]
    REC["Recorder thread / AudioRecord.read"] -->|"AudioFrame"| Q["Semaphore"]
    Q --> WORK["onlineWorker / 单线程顺序执行"]
    WORK -->|"immutable snapshot"| REF["AtomicReference"]
    REF -->|"coalesced post"| MAIN
~~~

| 执行环境 | 主要操作 | 原因 |
|---|---|---|
| Main thread | 生命周期、权限、绑定、按钮、通知协调、View 更新 | Android UI 规则 |
| Recorder thread | 阻塞读取 PCM、归一化、peak 计算 | 不阻塞 UI |
| `onlineWorker` | Online accept/decode、分段、SenseVoice 二遍、Stream 释放 | 保证 native 对象串行访问 |

### 5.1 线程不变量

1. 模型加载和 decode 不得在主线程执行；
2. Online Recognizer/Stream 的可变调用只进入 `onlineWorker`；
3. 当前二遍识别也进入同一个 Worker，因此不会和 Online Stream 并发访问 native 状态；
4. Recorder 不直接修改转录集合；
5. Activity 只读取不可变 `VoiceSessionSnapshot`；
6. Binder 方法并不天然运行在 Service 后台线程，入口必须显式转交到正确线程。

## 6. 服务生命周期

`VoiceRecognitionService` 同时具有 Started、Foreground 和 Bound 三种身份。

~~~mermaid
stateDiagram-v2
    [*] --> Created: bindService(BIND_AUTO_CREATE)
    Created --> Bound: Activity visible
    Bound --> StartedFG: user starts recording
    StartedFG --> StartedOnly: Activity unbinds / Home
    StartedOnly --> StartedFG: Activity binds again
    StartedFG --> Bound: session stopped
    Bound --> Destroyed: Activity unbinds and no start identity
    StartedOnly --> Destroyed: stopSelf and no binding
~~~

| 身份 | 作用 |
|---|---|
| Bound | Activity 接收快照、调用停止/清空 |
| Started | Activity 解绑后 Service 仍可存在 |
| Foreground | 录音期间显示持续通知，降低后台限制风险 |
| Partial WakeLock | 息屏时维持 CPU 完成必要 PCM 与识别工作 |

Foreground Service 不等于后台线程，也不等于永远不会被系统终止。应用仍须处理异常、进程死亡和权限限制。

## 7. 会话状态机

~~~mermaid
stateDiagram-v2
    [*] --> MODEL_LOADING
    MODEL_LOADING --> READY: both recognizers ready
    MODEL_LOADING --> ERROR: initialization failed
    READY --> STARTING: user start
    STARTING --> STREAMING: Stream + recorder ready
    STARTING --> STOPPING: stop requested
    STREAMING --> STOPPING: user / notification stop
    STREAMING --> ERROR: recorder or decoder failure
    STOPPING --> READY: tail finalized
    ERROR --> STARTING: retry when resources valid
    READY --> CLOSED: Service destroy
    ERROR --> CLOSED: Service destroy
~~~

`STOPPING` 不能直接变成 `READY`，因为系统还要：

- 停止并释放 AudioRecord；
- 等待已提交的 PCM 帧按顺序完成；
- 调用 Online Stream 的 `inputFinished()`；
- 对尾部 PCM 做第二遍识别；
- 清空 Partial 并发布最后快照；
- 释放 Stream、WakeLock 和前台身份；
- 检查 Semaphore 是否恢复为 20/20。

## 8. 一次流式会话

~~~mermaid
sequenceDiagram
    participant A as MainActivity
    participant S as VoiceRecognitionService
    participant R as StreamPcmRecorder
    participant O as Online Engine
    participant V as SenseVoice

    A->>S: startForegroundService(ACTION_START)
    S->>O: startSession()
    S->>R: start(onFrame, onStopped, onError)
    loop each ~100 ms
        R->>S: AudioFrame(samples, 16000, timestamp)
        S->>O: accept(frame)
        O-->>S: Partial(segmentId, text), endpoint?
        S-->>A: snapshot(partial, peak, rms)
    end
    O-->>S: endpoint = true
    S->>O: resetAfterBoundary(segmentId)
    S->>V: decodePcm(segmentSamples, 16000)
    V-->>S: Final text + metrics
    S-->>A: snapshot(committed segment)
~~~

### 8.1 Partial 与 Final

~~~mermaid
flowchart TD
    P1["Partial #7 / 今天下午"] --> REPLACE["替换当前草稿"]
    P2["Partial #7 / 今天下午三点开会"] --> REPLACE
    REPLACE --> F["Final #7"]
    F --> APPEND["追加稳定段并清空草稿"]
~~~

- Partial 是同一句话的逐步假设，因此只替换；
- Final 是一个已完成 segment，因此追加；
- `segmentId` 把 Partial、对应 PCM 和 Final 对齐；
- Final 携带来源，使 UI 能区分二遍结果和在线兜底。

## 9. 分段与二遍识别

~~~mermaid
flowchart TD
    PCM["持续 PCM"] --> BUFFER["SegmentPcmBuffer"]
    BUFFER --> CHECK{"到达边界？"}
    CHECK -->|"否"| PCM
    CHECK -->|"Online endpoint"| EP["ENDPOINT 计数 + 1"]
    CHECK -->|"20 秒"| MAX["FORCED 计数 + 1"]
    EP --> PASS["SenseVoice 二遍"]
    MAX --> PASS
    PASS -->|"非空"| OFF["OFFLINE_SECOND_PASS"]
    PASS -->|"异常或空"| FALL["ONLINE_FALLBACK"]
~~~

20 秒的边界只清空当前 segment 缓冲并重置 Online 状态。Recorder 和整个会话继续运行，因此它不是 20 秒录音上限。

当前设计在 `onlineWorker` 上同步执行二遍识别。二遍期间新 PCM 会在 Semaphore 允许的槽位内排队；若 200 ms 内无法取得槽位，系统明确报告背压失败，而不是静默丢帧。

## 10. 背压与故障可见性

~~~mermaid
flowchart TD
    IN["Recorder 新帧"] --> TRY{"200 ms 内取得 permit？"}
    TRY -->|"是"| SUBMIT["提交 onlineWorker"]
    SUBMIT --> WORK["处理 Online / segment"]
    WORK --> FIN["finally: release permit"]
    TRY -->|"否"| ERROR["ERROR / backpressureTimeouts + 1"]
~~~

`release()` 必须位于 `finally`，否则任意异常都会永久减少容量，最终造成“队列越来越小”的隐蔽故障。

需要持续观察的指标：

| 指标 | 健康期望 |
|---|---|
| `pendingFrames` | 短时波动，停止后回到 0 |
| `maxPendingFrames` | 小于 20，越低越好 |
| `backpressureTimeouts` | 0 |
| `availablePermits` | 停止后 20/20 |
| `endpointSegments` | 随自然停顿增加 |
| `forcedSegments` | 长时间不停顿时增加 |

## 11. 不可变快照与 UI 刷新

~~~mermaid
flowchart TD
    W1["Recorder/Worker 状态变化"] --> CAS["AtomicReference CAS / copy 新快照"]
    CAS --> FLAG{"dispatchPosted？"}
    FLAG -->|"否"| POST["post 一次主线程通知"]
    FLAG -->|"是"| SKIP["不再积压通知"]
    POST --> LATEST["读取最新 snapshot"]
    LATEST --> UI["Activity render"]
~~~

`dispatchPosted` 合并的是“重复通知”，不是丢失最新业务状态。主线程真正执行回调时读取 `snapshotRef` 的最新值。这样波形高频更新不会造成 UI 消息队列无限堆积。

`VoiceSessionSnapshot` 与 `TranscriptDocument` 使用 `val`、不可变列表和 `copy()`，使 Activity 不会读到 Service 正在修改一半的集合。

## 12. 转录文档模型

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

`speakerId` 目前为未来多人会议预留，并不表示已经实现说话人分离。

### 12.1 文本清洗

~~~mermaid
flowchart TD
    RAW["原始 Final"] --> MARK["移除模型标记"]
    MARK --> SPACE["合并空白 + trim"]
    SPACE --> VALID{"含字母或数字？"}
    VALID -->|"否"| DROP["丢弃"]
    VALID -->|"是"| FILLER{"< 0.8 s 且为短语气词？"}
    FILLER -->|"是"| DROP
    FILLER -->|"否"| KEEP["保留稳定段"]
~~~

清洗保持保守：

- 不采用“一个字全部丢弃”；
- 不在基础层猜测同音字；
- 不凭空发明标点；
- 不修改原始识别来源语义。

同音字、错别字、上下文与标点优化应放在可选的后处理层，并同时保留原始文本。

## 13. 资源所有权

| 资源 | 创建 | 复用范围 | 释放 |
|---|---|---|---|
| Online Recognizer | Service 模型初始化 | Service 生命周期 | Service `onDestroy()` |
| Online Stream | 每个录音会话 | 当前会话及其 segment reset | 会话停止 |
| Offline Recognizer | Service 模型初始化 | Service 生命周期 | Service `onDestroy()` |
| Offline Stream | 每个二遍 segment | 单次 `decodePcm` | `finally` |
| AudioRecord | 开始录音 | 当前会话 | Recorder capture loop `finally` |
| WakeLock | Service 创建对象 | 只在录音会话持有 | 停止、失败和销毁 |

Recognizer 长期复用用于避免重复加载数十到数百 MB 模型。Stream 保存单次输入/解码状态，生命周期更短。

## 14. 源码目录

~~~text
app/src/main/
├── AndroidManifest.xml
├── assets/                         # 本地模型；默认不进普通 Git
├── java/com/trendbot/voiceinputdemo/
│   ├── MainActivity.kt             # 薄 UI
│   ├── VoiceRecognitionService.kt  # 会话与调度
│   ├── VoiceServiceContract.kt     # 状态、快照、监听器
│   ├── StreamPcmRecorder.kt        # 麦克风 PCM
│   ├── AndroidOnlineAsrEngine.kt   # 在线识别适配
│   ├── AndroidVoiceCore.kt         # SenseVoice 二遍适配
│   ├── WaveformView.kt             # 电平绘制
│   ├── LogTags.kt                  # 模块日志标签
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

## 15. 端点、VAD 与波形的关系

| 概念 | 当前是否存在 | 输入 | 输出/用途 |
|---|---|---|---|
| peak/RMS | 是 | PCM 幅度 | 波形动画；不能判定人声 |
| Online Endpoint | 是 | 识别器内部状态 | 自然语音段边界 |
| 20 秒边界 | 是 | segment 时长 | 防止无限缓存 |
| 独立 VAD | 否 | 声学帧 | 更精确的 speech/silence 边界 |

后续接入 VAD 时，应让 VAD 产生边界信号，不要让它直接拥有 Recognizer 或 View。

## 16. 未来扩展点

~~~mermaid
flowchart TD
    CORE["现有录音 + ASR Core"] --> DOC["TranscriptDocument"]
    DOC --> STORE["Note Repository / Room / 文件"]
    STORE --> LIST["笔记列表 / 搜索 / 标签"]
    DOC --> POST["PostProcessor 接口"]
    POST --> RULE["本地词典 / 标点"]
    POST --> LLM["可选 LLM 模板"]
    STORE --> SHARE["分享 / 导出 / 合并"]
~~~

推荐新增的逻辑边界：

- `NoteRepository`：笔记、音频和元数据持久化；
- `PostProcessor`：输入原始转录，输出带版本的修订文本；
- `TemplateRepository`：会议、日记和技术文档模板；
- `SpeakerLabel`：将 diarization 结果映射到 segment；
- `ExportService`：剪贴板、Markdown、分享和备份。

基础识别层必须保存原始文本，后处理结果使用独立字段或修订记录，避免不可逆覆盖。

## 17. 为 iOS 保留的边界

~~~mermaid
flowchart TD
    SHARED["平台无关概念 / AudioFrame / Segment / Event"] --> AND["Android Adapter / AudioRecord + Service"]
    SHARED --> IOS["Future iOS Adapter / AVAudioEngine + background policy"]
    SHARED --> NATIVE["sherpa-onnx Core / C/C++ / ONNX"]
~~~

Android Service、Binder、Notification、WakeLock 无法跨平台复用。可复用的是：

- PCM 格式契约；
- Partial/Final/`segmentId` 语义；
- TranscriptDocument；
- 分段、背压、资源所有权原则；
- 性能指标和测试语料。

未来若追求最大复用，可把识别管线下沉为 C++ 核心；但在 Demo 阶段保持 Kotlin 适配层更省投入。

## 18. 安全、隐私与供应链

- 当前 Manifest 不需要 INTERNET；
- 不记录原始 PCM 到文件；
- Logcat 不应输出敏感完整转录到 Release 构建；
- keystore、密码、`local.properties`、APK、模型和 AAR 不进入普通 Git；
- sherpa-onnx 源码采用 Apache-2.0，但模型权重的许可证需要分别核对；
- 发布 APK 会构成 AAR 和模型的二进制再分发，必须保留适用 LICENSE/NOTICE；
- 新增 LLM 或同步后，必须重新设计网络权限、用户同意、保留期限和删除机制。

## 19. 回归与验收

### 19.1 每次提交

- `testDebugUnitTest` 通过；
- Debug APK 可构建；
- 应用启动无 native 崩溃；
- Partial 替换而不是重复追加；
- Final 后同段 Partial 消失；
- Stop 后 pending=0、permits=20/20；
- Home/息屏后录音继续；
- 自动滚动开关两种状态正确；
- Stop 完成后可编辑，录音期间不可编辑。

### 19.2 发布前

- 15–30 分钟真机连续测试；
- 普通话、数字、英文缩写、安静和噪声样本；
- 麦克风权限拒绝与重新授权；
- 通知停止；
- Home、锁屏、旋转/重建和返回；
- 连续开始/停止至少 10 次；
- Release APK 签名、SHA-256、全新安装和升级安装。

## 20. 关键设计判断

1. **Service 是会话所有者，Activity 是可替换的显示客户端。**
2. **在线一遍追求反馈速度，离线二遍追求稳定文本。**
3. **Partial 替换，Final 追加；segmentId 建立因果关系。**
4. **Recognizer 长期复用，Stream 按输入生命周期释放。**
5. **所有 native 解码顺序进入单 Worker，换取简单可靠。**
6. **Semaphore 提供有限缓冲，超载明确失败，不静默丢 PCM。**
7. **不可变快照隔离 Service 可变状态与 UI。**
8. **20 秒只限制单段缓存，不限制会话时长。**
9. **波形是反馈，不是 VAD。**
10. **原始识别与后处理修订必须可追溯。**

## 参考

- [Android Services](https://developer.android.com/develop/background-work/services)
- [Foreground services](https://developer.android.com/develop/background-work/services/fgs)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [sherpa-onnx Android documentation](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [Streaming Zipformer models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)
- [SenseVoice models](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)

