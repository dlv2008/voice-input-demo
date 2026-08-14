# Third-party dependency and model record

> This is a release-compliance template, not a completed legal notice. Fill it from the exact files shipped in the APK before public binary distribution.

## 1. sherpa-onnx runtime

| Field                                   | Value                                                      |
| --------------------------------------- | ---------------------------------------------------------- |
| Project                                 | k2-fsa/sherpa-onnx                                         |
| Version                                 | 1.13.4                                                     |
| Source                                  | https://github.com/k2-fsa/sherpa-onnx                      |
| Release                                 | https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4 |
| Binary                                  | sherpa-onnx-static-link-onnxruntime-1.13.4.aar             |
| License source                          | https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE  |


Action:

- [ ] Save a copy of the upstream LICENSE at the version used.
- [ ] Check whether the AAR contains additional ONNX Runtime notices.
- [ ] Preserve all required copyright and NOTICE text.

## 2. Streaming Zipformer model

| Field                    | Value                                                                                                                        |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| Model                    | sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23                                                                            |
| Documentation            | https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html                    |
| Archive                  | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2 |
| Files shipped            | encoder int8, decoder, joiner int8, tokens.txt                                                                               |


Action:

- [ ] Inspect and retain LICENSE/README from the exact archive.
- [ ] Record model author, training-data notes, and attribution.
- [ ] Confirm that embedding these files in a public APK is permitted.

## 3. SenseVoice model

| Field            | Value                                                                                                                              |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Model            | sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17                                                                            |
| Documentation    | https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html                                                                   |
| Archive          | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2 |
| Upstream project | https://github.com/FunAudioLLM/SenseVoice                                                                                          |
| Files shipped    | model.int8.onnx, tokens.txt                                                                                                        |


Action:

- [ ] Inspect and retain LICENSE/README from the exact archive.
- [ ] Distinguish code license, model-weight license, and dataset terms.
- [ ] Confirm that embedding these files in a public APK is permitted.

## 4. Android dependencies

Generate the final list from the release dependency graph:

~~~powershell
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
~~~

``` text
+--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24
|    +--- org.jetbrains:annotations:13.0 -> 23.0.0
|    +--- org.jetbrains.kotlin:kotlin-stdlib-common:1.9.24 (c)
|    +--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0 -> 1.8.22 (c)
|    \--- org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0 -> 1.8.22 (c)
+--- androidx.core:core-ktx:1.10.1 -> 1.13.0
|    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1
|    |    \--- androidx.annotation:annotation-jvm:1.8.1
|    |         \--- org.jetbrains.kotlin:kotlin-stdlib:1.7.10 -> 1.9.24 (*)
|    +--- androidx.core:core:1.13.0
|    |    +--- androidx.annotation:annotation:1.6.0 -> 1.8.1 (*)
|    |    +--- androidx.annotation:annotation-experimental:1.4.0
|    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.7.10 -> 1.9.24 (*)
|    |    +--- androidx.collection:collection:1.0.0 -> 1.1.0
|    |    |    \--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    +--- androidx.concurrent:concurrent-futures:1.0.0 -> 1.1.0
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    \--- com.google.guava:listenablefuture:1.0
|    |    +--- androidx.interpolator:interpolator:1.0.0
|    |    |    \--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    +--- androidx.arch.core:core-common:2.2.0
|    |    |    |    \--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    +--- androidx.arch.core:core-runtime:2.2.0
|    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    |    \--- androidx.arch.core:core-common:2.2.0 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2
|    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4 -> 1.7.3
|    |    |    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3
|    |    |    |    |    |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3
|    |    |    |    |    |         +--- org.jetbrains:annotations:23.0.0
|    |    |    |    |    |         +--- org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3
|    |    |    |    |    |         |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3 (c)
|    |    |    |    |    |         |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3 (c)
|    |    |    |    |    |         |    \--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 (c)
|    |    |    |    |    |         +--- org.jetbrains.kotlin:kotlin-stdlib-common:1.8.20 -> 1.9.24
|    |    |    |    |    |         |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.24 (*)
|    |    |    |    |    |         \--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.20 -> 1.8.22
|    |    |    |    |    |              +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (*)
|    |    |    |    |    |              \--- org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22
|    |    |    |    |    |                   \--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (*)
|    |    |    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3 (*)
|    |    |    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.20 -> 1.8.22 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (c)
|    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2 (c)
|    |    |    +--- androidx.profileinstaller:profileinstaller:1.3.0 -> 1.4.0
|    |    |    |    +--- androidx.annotation:annotation:1.8.1 (*)
|    |    |    |    +--- androidx.concurrent:concurrent-futures:1.1.0 (*)
|    |    |    |    +--- androidx.startup:startup-runtime:1.1.1
|    |    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    |    |    \--- androidx.tracing:tracing:1.0.0
|    |    |    |    |         \--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    |    \--- com.google.guava:listenablefuture:1.0
|    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-process:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-livedata:2.6.2 (c)
|    |    +--- androidx.versionedparcelable:versionedparcelable:1.1.1
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    \--- androidx.collection:collection:1.0.0 -> 1.1.0 (*)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (*)
|    |    \--- androidx.core:core-ktx:1.13.0 (c)
|    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (*)
|    \--- androidx.core:core:1.13.0 (c)
+--- androidx.appcompat:appcompat:1.7.0
|    +--- androidx.activity:activity:1.7.0 -> 1.10.0
|    |    +--- androidx.annotation:annotation:1.8.1 (*)
|    |    +--- androidx.core:core-ktx:1.13.0 (*)
|    |    +--- androidx.lifecycle:lifecycle-common:2.6.1 -> 2.6.2 (*)
|    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.1 -> 2.6.2 (*)
|    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.1 -> 2.6.2
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-process:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2 (c)
|    |    +--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1 -> 2.6.2
|    |    |    +--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |    |    +--- androidx.core:core-ktx:1.2.0 -> 1.13.0 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2
|    |    |    |    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|    |    |    |    +--- androidx.arch.core:core-runtime:2.1.0 -> 2.2.0 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (*)
|    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (c)
|    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (*)
|    |    |    +--- androidx.savedstate:savedstate:1.2.1
|    |    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    |    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.1 -> 2.6.2 (*)
|    |    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4 -> 1.7.3 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-process:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (c)
|    |    +--- androidx.profileinstaller:profileinstaller:1.4.0 (*)
|    |    +--- androidx.savedstate:savedstate:1.2.1 (*)
|    |    +--- androidx.tracing:tracing:1.0.0 (*)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib -> 1.9.24 (*)
|    |    +--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3 (*)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (c)
|    +--- androidx.annotation:annotation:1.3.0 -> 1.8.1 (*)
|    +--- androidx.appcompat:appcompat-resources:1.7.0
|    |    +--- androidx.annotation:annotation:1.2.0 -> 1.8.1 (*)
|    |    +--- androidx.collection:collection:1.0.0 -> 1.1.0 (*)
|    |    +--- androidx.core:core:1.6.0 -> 1.13.0 (*)
|    |    +--- androidx.vectordrawable:vectordrawable:1.1.0
|    |    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    |    +--- androidx.core:core:1.1.0 -> 1.13.0 (*)
|    |    |    \--- androidx.collection:collection:1.1.0 (*)
|    |    +--- androidx.vectordrawable:vectordrawable-animated:1.1.0
|    |    |    +--- androidx.vectordrawable:vectordrawable:1.1.0 (*)
|    |    |    +--- androidx.interpolator:interpolator:1.0.0 (*)
|    |    |    \--- androidx.collection:collection:1.1.0 (*)
|    |    \--- androidx.appcompat:appcompat:1.7.0 (c)
|    +--- androidx.collection:collection:1.0.0 -> 1.1.0 (*)
|    +--- androidx.core:core:1.13.0 (*)
|    +--- androidx.core:core-ktx:1.13.0 (*)
|    +--- androidx.cursoradapter:cursoradapter:1.0.0
|    |    \--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    +--- androidx.drawerlayout:drawerlayout:1.0.0 -> 1.1.1
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    +--- androidx.core:core:1.2.0 -> 1.13.0 (*)
|    |    \--- androidx.customview:customview:1.1.0
|    |         +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |         +--- androidx.core:core:1.3.0 -> 1.13.0 (*)
|    |         \--- androidx.collection:collection:1.1.0 (*)
|    +--- androidx.emoji2:emoji2:1.3.0
|    |    +--- androidx.annotation:annotation:1.2.0 -> 1.8.1 (*)
|    |    +--- androidx.collection:collection:1.1.0 (*)
|    |    +--- androidx.core:core:1.3.0 -> 1.13.0 (*)
|    |    +--- androidx.lifecycle:lifecycle-process:2.4.1 -> 2.6.2
|    |    |    +--- androidx.annotation:annotation:1.2.0 -> 1.8.1 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (*)
|    |    |    +--- androidx.startup:startup-runtime:1.1.1 (*)
|    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (c)
|    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2 (c)
|    |    +--- androidx.startup:startup-runtime:1.0.0 -> 1.1.1 (*)
|    |    \--- androidx.emoji2:emoji2-views-helper:1.3.0 (c)
|    +--- androidx.emoji2:emoji2-views-helper:1.2.0 -> 1.3.0
|    |    +--- androidx.collection:collection:1.1.0 (*)
|    |    +--- androidx.core:core:1.3.0 -> 1.13.0 (*)
|    |    +--- androidx.emoji2:emoji2:1.3.0 (*)
|    |    \--- androidx.emoji2:emoji2:1.3.0 (c)
|    +--- androidx.fragment:fragment:1.5.4
|    |    +--- androidx.activity:activity:1.5.1 -> 1.10.0 (*)
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    +--- androidx.annotation:annotation-experimental:1.0.0 -> 1.4.0 (*)
|    |    +--- androidx.collection:collection:1.1.0 (*)
|    |    +--- androidx.core:core-ktx:1.2.0 -> 1.13.0 (*)
|    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.5.1 -> 2.6.2 (*)
|    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.5.1 -> 2.6.2 (*)
|    |    +--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.5.1 -> 2.6.2 (*)
|    |    +--- androidx.loader:loader:1.0.0
|    |    |    +--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |    |    +--- androidx.core:core:1.0.0 -> 1.13.0 (*)
|    |    |    +--- androidx.lifecycle:lifecycle-livedata:2.0.0 -> 2.6.2
|    |    |    |    +--- androidx.arch.core:core-common:2.1.0 -> 2.2.0 (*)
|    |    |    |    +--- androidx.arch.core:core-runtime:2.1.0 -> 2.2.0 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (*)
|    |    |    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.10 -> 1.9.24 (*)
|    |    |    |    +--- androidx.lifecycle:lifecycle-common:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-livedata-core:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-process:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-runtime:2.6.2 (c)
|    |    |    |    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.2 (c)
|    |    |    |    \--- androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2 (c)
|    |    |    \--- androidx.lifecycle:lifecycle-viewmodel:2.0.0 -> 2.6.2 (*)
|    |    +--- androidx.savedstate:savedstate:1.2.0 -> 1.2.1 (*)
|    |    +--- androidx.viewpager:viewpager:1.0.0
|    |    |    +--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |    |    +--- androidx.core:core:1.0.0 -> 1.13.0 (*)
|    |    |    \--- androidx.customview:customview:1.0.0 -> 1.1.0 (*)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.6.21 -> 1.9.24 (*)
|    +--- androidx.lifecycle:lifecycle-runtime:2.6.1 -> 2.6.2 (*)
|    +--- androidx.lifecycle:lifecycle-viewmodel:2.6.1 -> 2.6.2 (*)
|    +--- androidx.profileinstaller:profileinstaller:1.3.1 -> 1.4.0 (*)
|    +--- androidx.resourceinspection:resourceinspection-annotation:1.0.1
|    |    \--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    +--- androidx.savedstate:savedstate:1.2.1 (*)
|    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (*)
|    \--- androidx.appcompat:appcompat-resources:1.7.0 (c)
+--- com.google.android.material:material:1.12.0
|    +--- org.jetbrains.kotlin:kotlin-bom:1.8.22
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib:1.8.22 -> 1.9.24 (c)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib-common:1.8.22 -> 1.9.24 (c)
|    |    +--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22 (c)
|    |    \--- org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22 (c)
|    +--- com.google.errorprone:error_prone_annotations:2.15.0
|    +--- androidx.activity:activity:1.8.0 -> 1.10.0 (*)
|    +--- androidx.annotation:annotation:1.2.0 -> 1.8.1 (*)
|    +--- androidx.appcompat:appcompat:1.6.1 -> 1.7.0 (*)
|    +--- androidx.cardview:cardview:1.0.0
|    |    \--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    +--- androidx.coordinatorlayout:coordinatorlayout:1.1.0
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    +--- androidx.core:core:1.1.0 -> 1.13.0 (*)
|    |    +--- androidx.customview:customview:1.0.0 -> 1.1.0 (*)
|    |    \--- androidx.collection:collection:1.0.0 -> 1.1.0 (*)
|    +--- androidx.constraintlayout:constraintlayout:2.0.1 -> 2.2.0
|    |    +--- androidx.appcompat:appcompat:1.2.0 -> 1.7.0 (*)
|    |    +--- androidx.constraintlayout:constraintlayout-core:1.1.0
|    |    |    \--- androidx.annotation:annotation:1.8.1 (*)
|    |    +--- androidx.core:core:1.3.2 -> 1.13.0 (*)
|    |    \--- androidx.profileinstaller:profileinstaller:1.4.0 (*)
|    +--- androidx.core:core:1.6.0 -> 1.13.0 (*)
|    +--- androidx.drawerlayout:drawerlayout:1.1.1 (*)
|    +--- androidx.dynamicanimation:dynamicanimation:1.0.0
|    |    +--- androidx.core:core:1.0.0 -> 1.13.0 (*)
|    |    +--- androidx.collection:collection:1.0.0 -> 1.1.0 (*)
|    |    \--- androidx.legacy:legacy-support-core-utils:1.0.0
|    |         +--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |         +--- androidx.core:core:1.0.0 -> 1.13.0 (*)
|    |         +--- androidx.documentfile:documentfile:1.0.0
|    |         |    \--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |         +--- androidx.loader:loader:1.0.0 (*)
|    |         +--- androidx.localbroadcastmanager:localbroadcastmanager:1.0.0
|    |         |    \--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    |         \--- androidx.print:print:1.0.0
|    |              \--- androidx.annotation:annotation:1.0.0 -> 1.8.1 (*)
|    +--- androidx.annotation:annotation-experimental:1.0.0 -> 1.4.0 (*)
|    +--- androidx.fragment:fragment:1.2.5 -> 1.5.4 (*)
|    +--- androidx.lifecycle:lifecycle-runtime:2.0.0 -> 2.6.2 (*)
|    +--- androidx.recyclerview:recyclerview:1.0.0 -> 1.1.0
|    |    +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|    |    +--- androidx.core:core:1.1.0 -> 1.13.0 (*)
|    |    +--- androidx.customview:customview:1.0.0 -> 1.1.0 (*)
|    |    \--- androidx.collection:collection:1.0.0 -> 1.1.0 (*)
|    +--- androidx.resourceinspection:resourceinspection-annotation:1.0.1 (*)
|    +--- androidx.transition:transition:1.5.0
|    |    +--- androidx.annotation:annotation:1.2.0 -> 1.8.1 (*)
|    |    +--- androidx.collection:collection:1.1.0 (*)
|    |    +--- androidx.core:core:1.13.0 (*)
|    |    \--- androidx.dynamicanimation:dynamicanimation:1.0.0 (*)
|    +--- androidx.vectordrawable:vectordrawable:1.1.0 (*)
|    \--- androidx.viewpager2:viewpager2:1.0.0
|         +--- androidx.annotation:annotation:1.1.0 -> 1.8.1 (*)
|         +--- androidx.fragment:fragment:1.1.0 -> 1.5.4 (*)
|         +--- androidx.recyclerview:recyclerview:1.1.0 (*)
|         +--- androidx.core:core:1.1.0 -> 1.13.0 (*)
|         \--- androidx.collection:collection:1.1.0 (*)
+--- androidx.activity:activity:1.10.0 (*)
\--- androidx.constraintlayout:constraintlayout:2.2.0 (*)
```
## 5. Release approval

- [ ] Every shipped third-party component has a traceable source and version.
- [ ] Required license texts and notices are present.
- [ ] Model redistribution terms are explicit enough for the intended public APK.
- [ ] No project-license statement incorrectly claims ownership of third-party files.
- [ ] Unclear items have been resolved before publishing the APK.

