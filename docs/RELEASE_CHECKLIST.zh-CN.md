# v0.1.0 发布检查表

> 原则：全部勾选后，才把 GitHub Release 从 Pre-release 改为正式 Latest。

## A. 仓库与文档

- [ ] 已确认 Git 根目录，没有误建嵌套仓库；
- [ ] GitHub owner、仓库名和可见性正确；
- [ ] 默认分支是 `main`；
- [ ] `README.md` 默认显示中文；
- [ ] `README_EN.md` 可与中文互相切换；
- [ ] 中英文架构文档均可打开；
- [ ] Mermaid 图在 GitHub 正常渲染；
- [ ] 所有截图文件已添加，文件名和大小写正确；
- [ ] `YOUR_GITHUB_USERNAME` 占位符为 0；
- [ ] `PROJECT_LICENSE` 占位符为 0；
- [ ] 项目根目录已有正式 `LICENSE`；
- [ ] README 中的功能、局限和隐私描述与 APK 一致。

## B. 安全与供应链

- [ ] `local.properties` 未跟踪；
- [ ] `build/`、`.gradle/`、`.cxx/` 未跟踪；
- [ ] keystore、`keystore.properties` 和密码未跟踪；
- [ ] APK、AAB、AAR 和 ONNX 模型未进入普通 Git；
- [ ] 无 token、API key、私钥、内网地址和私人证书；
- [ ] 无真实会议日志、电话、姓名和不应公开的截图；
- [ ] 已保存 sherpa-onnx 的 LICENSE/NOTICE；
- [ ] 已核对 Online 模型包的 LICENSE/README；
- [ ] 已核对 SenseVoice 模型包的 LICENSE/README；
- [ ] 已确认 APK 中第三方二进制和模型权重可以再分发；
- [ ] `THIRD_PARTY_NOTICES` 已从模板完成并随仓库或 Release 提供。

## C. 构建

- [ ] `versionName = "0.1.0"`；
- [ ] `versionCode = 1` 或本次应有的更高值；
- [ ] `applicationId` 已最终确认；
- [ ] `testDebugUnitTest` 通过；
- [ ] Debug APK 构建成功；
- [ ] Release APK 使用正式 keystore 签名；
- [ ] keystore 已有加密备份；
- [ ] `apksigner verify --verbose --print-certs` 通过；
- [ ] APK 名为 `voice-input-demo-v0.1.0-arm64-v8a.apk`；
- [ ] 已生成 `SHA256SUMS.txt`；
- [ ] Release APK 未被错误加入 Git。

## D. 真机回归

- [ ] 小米 13 或目标 arm64-v8a 设备全新安装成功；
- [ ] 麦克风权限首次授权正常；
- [ ] 通知权限允许和拒绝路径均检查；
- [ ] 两个模型加载成功；
- [ ] 在线 Partial 灰色、持续替换；
- [ ] SenseVoice Final 黑色并追加；
- [ ] Online fallback 颜色和语义正确；
- [ ] 空白、纯标点和短语气词过滤符合预期；
- [ ] 波形随 peak/RMS 变化；
- [ ] 自动滚动开启时跟随新文本；
- [ ] 自动滚动关闭时不抢位置；
- [ ] Stop 完成后可编辑；
- [ ] 录音期间不可编辑；
- [ ] 复制与新建可用；
- [ ] Home 后录音继续；
- [ ] 息屏后录音和识别继续；
- [ ] 通知停止可结束会话；
- [ ] 15–30 分钟连续会话通过；
- [ ] 连续开始/停止 10 次无资源泄漏；
- [ ] Stop 后 pending=0；
- [ ] Stop 后 permits=20/20；
- [ ] 无 ANR、native crash 和明显不可接受的延迟。

## E. Git 与 Release

- [ ] 发布提交已 Push；
- [ ] 工作区干净；
- [ ] tag `v0.1.0` 指向正确提交；
- [ ] tag 已 Push；
- [ ] Release title、说明和已知限制完整；
- [ ] APK 与 SHA256SUMS 已上传；
- [ ] 先以 Pre-release 下载并复验；
- [ ] GitHub 下载的 APK SHA-256 与本地一致；
- [ ] 无登录窗口可访问公开仓库和下载页；
- [ ] Pre-release 已在最终确认后改为 Latest；
- [ ] README 的 Latest Release 链接有效。

## F. 发布后

- [ ] 在全新目录 clone；
- [ ] 严格按 README 下载依赖；
- [ ] 全新 clone 可完成测试和 Debug 构建；
- [ ] Issue 模板或反馈入口已说明；
- [ ] 已保存本次 APK、SHA、tag、证书指纹和测试记录；
- [ ] 下一版本计划使用更高 versionCode 并复用同一签名密钥。

