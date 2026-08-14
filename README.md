# 端侧语音转录助手

[简体中文](README.md) | [English](README_EN.md)

一款面向 Android 的端侧流式语音转录 Demo：录音时持续显示在线识别(Zipformer)草稿，并在每个语音段结束后使用 SenseVoice 进行第二遍确认。音频和识别默认均在设备本地完成。

> 当前阶段：v0.1.0 发布候选版  
> 适用设备：Android 8.0（API 26）及以上、arm64-v8a  
> 项目性质：技术验证 / 开源演示，不应直接视为生产级转录产品  
> 项目地址：https://github.com/dlv2008/voice-input-demo
> LICENSE：Apache License 2.0


## 下载 APK

- [下载最新版本](https://github.com/dlv2008/voice-input-demo/releases/latest)
- 当前建议资产名：`voice-input-demo-v0.1.0-arm64-v8a.apk`
- 安装前请核对 Release 页面提供的 SHA-256

APK 使用项目维护者自己的证书签名。Android 可能提示“未知来源应用”，请只从本项目的 GitHub Releases 页面下载。

## 界面预览
![端侧转录助手界面](./docs/images/snapshot.png)

## 功能

- 16 kHz、单声道 PCM 麦克风采集；
- 录音过程中流式输出 Online Partial；
- 语音端点或安全分段后，使用 SenseVoice 做本地第二遍识别；
- 灰色显示在线草稿或在线兜底文本，黑色显示第二遍确认文本；
- Partial 替换、Final 追加，多个内部 segment 显示成连续正文；
- 清理空白、纯标点、模型标记和极短语气词，同时保留可能有意义的单字；
- PCM 峰值与 RMS 波形显示；
- Started + Bound 前台麦克风 Service；
- Partial WakeLock 支持息屏期间继续处理；
- 自动滚动开关、复制、新建，以及停止后的文本编辑；
- 无强制会话总时长限制；20 秒是单段语音安全边界；
- 不依赖云端识别服务，可以在飞行模式下运行。当前 Manifest 不申请网络权限。

## 它是怎样工作的

~~~mermaid
flowchart TD
    MIC["麦克风 PCM"] --> REC["StreamPcmRecorder"]
    REC --> ONLINE["在线 Zipformer"]
    ONLINE -->|"Partial"| UI["灰色实时文字"]
    ONLINE -->|"端点 / 20 秒边界"| SECOND["SenseVoice 二遍"]
    SECOND -->|"Final"| DOC["连续转录文档"]
    DOC --> UI2["黑色确认文字"]
~~~

在线模型负责低延迟反馈，SenseVoice 负责语音段结束后的第二遍确认。两种结果通过相同的 `segmentId` 对齐。详细设计见：

- [架构与技术说明（中文）](docs/ARCHITECTURE.zh-CN.md)
- [Architecture and Technical Notes (English)](docs/ARCHITECTURE.en.md)

## 技术栈

| 项目          | 当前选择                                                                                                                                                                                                           |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| UI          | Kotlin + Android XML Views + Material Components                                                                                                                                                               |
| 音频          | AudioRecord，16 kHz，mono，PCM                                                                                                                                                                                    |
| ASR Runtime | [sherpa-onnx 1.13.4](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4)                                                                                                                               |
| 在线模型        | [Streaming Zipformer 14M 中文模型](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#csukuangfj-sherpa-onnx-streaming-zipformer-zh-14m-2023-02-23-chinese) |
| 二遍模型        | [SenseVoice int8（中、英、日、韩、粤）](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)                                                                                                                 |
| Android     | compileSdk / targetSdk 35，minSdk 26                                                                                                                                                                            |
| ABI         | arm64-v8a                                                                                                                                                                                                      |
| 并发          | 单线程 ASR Worker + 有界 Semaphore 队列                                                                                                                                                                               |
| 后台录音        | 前台麦克风 Service + Partial WakeLock                                                                                                                                                                               |

## 从源码构建

### 1. 环境

- [Android Studio](https://developer.android.com/studio)
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0 或与项目兼容的稳定版本
- Android SDK Platform-Tools
- JDK：使用 Android Studio 内置 JBR 即可
- Git

### 2. 克隆

~~~powershell
git clone https://github.com/dlv2008/voice-input-demo.git
Set-Location voice-input-demo
~~~

### 3. 准备 sherpa-onnx AAR

将以下文件放入 `app/libs/`：

- [sherpa-onnx-static-link-onnxruntime-1.13.4.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-static-link-onnxruntime-1.13.4.aar)

最终路径：

~~~text
app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar
~~~

### 3. 建立本地依赖目录

Git 不保存空目录，而且 AAR 和模型文件被 `.gitignore` 排除，因此首次克隆后需要手动建立目录。

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force app\libs | Out-Null
New-Item -ItemType Directory -Force app\src\main\assets | Out-Null
```

Linux、macOS 或 WSL：

```bash
mkdir -p app/libs
mkdir -p app/src/main/assets
```

建立后的基本结构：

```text
voice-input-demo/
└── app/
    ├── libs/
    └── src/
        └── main/
            └── assets/
```

### 4. 下载 sherpa-onnx AAR

下载：

- [sherpa-onnx-static-link-onnxruntime-1.13.4.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-static-link-onnxruntime-1.13.4.aar)

将文件复制到：

```text
app/libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar
```

文件名不要修改，因为 `app/build.gradle.kts` 按照这个名称加载 AAR：

```kotlin
implementation(
    files("libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar")
)
```

AAR 提供 sherpa-onnx 的 Kotlin/Java 接口、JNI 库以及 ONNX Runtime 运行支持。

### 5. 下载语音模型

项目使用两套模型。

#### 5.1 在线流式识别模型

用途：录音过程中持续产生灰色的实时转录结果。

下载：

- [Streaming Zipformer 14M 中文模型](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2)

#### 5.2 SenseVoice 二次识别模型

用途：语音段结束后进行第二遍识别，产生黑色确认文本。

下载：

- [SenseVoice int8 模型](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2)

使用 7-Zip、tar 或其他支持 `.tar.bz2` 的工具解压，将解压得到的两个完整目录放入：

```text
app/src/main/assets/
```

在 WSL、Linux 或 macOS 中，也可以在仓库根目录执行：

```bash
mkdir -p app/src/main/assets
cd app/src/main/assets

wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2
tar -xjf sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2
rm sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2

wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
tar -xjf sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
rm sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
```

### 6. 检查最终目录

最终应至少存在以下文件：

```text
app/
├── libs/
│   └── sherpa-onnx-static-link-onnxruntime-1.13.4.aar
└── src/
    └── main/
        └── assets/
            ├── sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/
            │   ├── encoder-epoch-99-avg-1.int8.onnx
            │   ├── decoder-epoch-99-avg-1.onnx
            │   ├── joiner-epoch-99-avg-1.int8.onnx
            │   └── tokens.txt
            └── sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/
                ├── model.int8.onnx
                └── tokens.txt
```

不要形成多余的嵌套目录，例如：

```text
assets/模型目录/模型目录/model.int8.onnx
```

应用代码按照上面给出的固定目录名称查找模型。

### 7. `tokens.txt` 是什么

`tokens.txt` 是模型的词表文件，用于把模型输出的 token 编号转换成文字、字符或子词。

两套模型各自拥有一份 `tokens.txt`：

```text
在线 Zipformer 模型
└── tokens.txt

SenseVoice 模型
└── tokens.txt
```

注意：

- `tokens.txt` 必须与相应的 ONNX 模型来自同一个模型压缩包；
- 不要让两套模型共用一份 `tokens.txt`；
- 不要单独下载其他版本的 `tokens.txt` 进行替换；
- 文件缺失或版本不匹配时，可能导致模型加载失败或输出文字错误。

代码中的对应关系为：

```text
AndroidOnlineAsrEngine
→ 在线 Zipformer 目录中的 tokens.txt

AndroidVoiceCore
→ SenseVoice 目录中的 tokens.txt
```

### 8. 为什么这些文件没有提交到 GitHub

AAR 和模型文件没有进入普通 Git 历史，主要原因是：

1. 模型文件体积较大，会明显增加仓库克隆和更新成本；
2. GitHub 普通 Git 文件存在 100 MiB 的单文件限制；
3. AAR 和模型属于第三方构建产物，需要分别遵守上游许可证；
4. 从官方地址下载，可以明确文件来源和模型版本；
5. 后续更新模型时，不需要把大型二进制文件写入整个 Git 历史。

请不要使用 `git add -f` 强制提交这些文件。

GitHub Release 中提供的 APK 已经包含运行所需的本地库和模型。普通用户只需安装 APK，不需要单独下载模型；只有从源码构建的开发者需要执行上述步骤。

### 9. 构建和运行

1. 使用 Android Studio 打开仓库根目录；
2. 等待 Gradle Sync 完成；
3. 使用 USB 连接 arm64-v8a Android 手机；
4. 在手机上授权 USB 调试；
5. 在 Android Studio 中选择 `app`；
6. 点击 Run；
7. 首次启动时授予麦克风和通知权限。

命令行验证：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK 默认位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前项目只构建：

```text
arm64-v8a
```

因此不能安装到只支持 x86、x86_64 或 32 位 ARM 的设备和模拟器。

## 交互语义

| 界面文字     | 含义                             |
| -------- | ------------------------------ |
| 灰色斜体     | 当前 Online Partial，会被同一段的后续结果替换 |
| 黑色       | SenseVoice 第二遍成功后的稳定 Final     |
| 灰色稳定文本   | 二遍失败或为空时保留的 Online fallback    |
| STOPPING | 正在停止录音、排空 PCM、完成尾段识别和释放 Stream |

黑色代表“经过第二遍确认”，不代表绝对正确。专有名词、数字、英文缩写与噪声场景仍可能需要人工编辑。

## 权限与隐私

| 权限                            | 用途                   |
| ----------------------------- | -------------------- |
| RECORD_AUDIO                  | 采集麦克风 PCM            |
| POST_NOTIFICATIONS            | Android 13+ 显示前台服务通知 |
| FOREGROUND_SERVICE            | 持续录音服务               |
| FOREGROUND_SERVICE_MICROPHONE | 声明麦克风前台服务类型          |
| WAKE_LOCK                     | 息屏时维持必要的音频处理         |

当前识别链路在设备端运行，项目不需要把录音上传到服务器。请在新增 LLM、同步或分析功能时重新更新隐私说明。

## 已知限制

- 当前在线 Zipformer 模型主要面向中文；混合英文和专有名词识别有限；
- 尚未接入独立 VAD，主要依赖在线识别器 Endpoint 和 20 秒安全分段；
- 暂无音频/笔记持久化、数据库、标签和全文检索；
- 暂无说话人分离与多人标注；
- 暂无同音字词典、领域热词或 LLM 上下文纠错；
- 当前仅打包 arm64-v8a；
- UI 编辑内容的持久化范围取决于当前实现，关闭应用前请先复制重要文本。

## 路线图

- [ ] VAD 与更自然的段落边界；
- [ ] 会话和音频持久化；
- [ ] 语音笔记列表、标签、编辑、分享、合并；
- [ ] 可管理的会议/日记/技术文档模板；
- [ ] 可选 LLM 后处理：纠错、标点、摘要与结构化；
- [ ] 说话人分离与标注；
- [ ] 性能基准、回归测试和更多 Android 机型；
- [ ] 抽象跨平台核心接口，为未来 iOS 适配保留边界。

## 参与开发

1. Fork 本仓库并从 `main` 创建分支；
2. 保持 Recognizer 长期复用、Stream 按会话管理的资源语义；
3. 不在 UI 线程加载模型或解码；
4. 不静默丢弃 PCM；背压必须可观察并明确失败；
5. 提交前运行单元测试和真机回归；
6. 提交 Pull Request，并说明机型、Android 版本、测试语句和结果。

## 开源许可

本项目源码采用 [Apache License 2.0](LICENSE)。
第三方运行库和模型权重适用各自独立的许可证。

## 致谢

- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice)
- [Android Developers](https://developer.android.com/)

## 发布者文档

- [发布前检查表](docs/RELEASE_CHECKLIST.zh-CN.md)
- [Release Notes 模板](docs/RELEASE_NOTES_TEMPLATE.md)
