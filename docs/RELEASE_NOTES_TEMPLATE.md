# Voice Input Demo v0.1.0

[简体中文](#简体中文) | [English](#english)

## 简体中文

首个公开技术验证版本：在 Android arm64-v8a 设备上进行端侧长时流式语音转录。

### 主要功能

- 在线 Zipformer 流式 Partial；
- SenseVoice 分段二遍识别；
- 黑色二遍 Final / 灰色在线结果；
- 前台 Service、息屏录音和 PCM 波形；
- 自动滚动、复制、新建、停止后编辑；
- 不依赖云端 ASR。

### 安装要求

- Android 8.0（API 26）或以上；
- arm64-v8a；
- 安装后授予麦克风权限；
- Android 13+ 建议授予通知权限。

### 下载

- `voice-input-demo-v0.1.0-arm64-v8a.apk`
- `SHA256SUMS.txt`

请在安装前核对 SHA-256。

### 已知限制

- 当前在线模型主要面向中文；
- 尚无独立 VAD、说话人分离和持久化；
- 英文缩写、数字、专有名词及噪声场景可能需要人工编辑；
- 这是技术验证版，不保证生产级准确率与稳定性。

### 隐私

当前 ASR 在设备本地运行，应用不上传麦克风音频。请以本 tag 对应的源码和 Manifest 为准。

### 测试记录

- 设备：`[填写设备，例如 Xiaomi 13]`
- Android 版本：`[填写]`
- 最长连续录音：`[填写]`
- 测试日期：`[填写]`

## English

The first public technical-validation release for long-running, on-device streaming transcription on Android arm64-v8a devices.

### Highlights

- streaming Zipformer Partial results;
- per-segment SenseVoice second pass;
- black second-pass Final and gray online text;
- foreground Service, screen-off capture, and PCM waveform;
- auto-scroll, copy, new document, and editing after stop;
- no cloud ASR dependency.

### Requirements

- Android 8.0 (API 26) or later;
- arm64-v8a;
- microphone permission;
- notification permission is recommended on Android 13+.

### Assets

- `voice-input-demo-v0.1.0-arm64-v8a.apk`
- `SHA256SUMS.txt`

Verify the SHA-256 value before installing.

### Known limitations

- The streaming model primarily targets Chinese.
- No independent VAD, speaker diarization, or persistent note storage yet.
- Abbreviations, numbers, proper nouns, and noisy audio may require manual editing.
- This is a technical demo, not a production accuracy or stability guarantee.

### Privacy

The current ASR pipeline runs on-device and does not upload microphone audio. The source and Manifest at this tag are authoritative.

### Test record

- Device: `[fill in, for example Xiaomi 13]`
- Android version: `[fill in]`
- Longest continuous session: `[fill in]`
- Test date: `[fill in]`

