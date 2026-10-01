# AI Chat (Android)

一个可离线自用的 AI 聊天客户端（Kotlin + Jetpack Compose，Material 3，单 Activity + MVVM）。

- 包名：`com.aichat.app`，应用名：AI Chat
- 最低支持 Android 5（minSdk 21），targetSdk 34
- 原生 HttpURLConnection，不依赖 OkHttp
- 旧设备 HTTPS 兼容：内置 ISRG Root X1 信任锚 + TLS 1.2（`data/TlsConfig.kt`）
- 真流式打字机输出、停止生成、消息重试/编辑重发、JSON 备份/恢复
- 内置 OpenCode 免费车道：一键「获取 OpenCode 免费模型」（从网关拉清单 + 实测首字速度 + 用户自选添加）、思考等级（精简/均衡/深思，MiMo 等模型）、思考过程（reasoning）折叠展示、thinking 标签自动剥离

## 文档
- [CHANGES.md](./CHANGES.md) — v1.2.0 / v1.3.0 / v1.4.0 变更说明
- [ANALYSIS.md](./ANALYSIS.md) — 原始源码分析

## 构建
```bash
ANDROID_HOME=/opt/android-sdk gradle :app:assembleDebug --no-daemon
```
产物：`app/build/outputs/apk/debug/app-debug.apk`（Debug 签名）。
APK 发布版通过本网站的 Releases 提供，不入 git。

## 本地 Git 托管
本仓库托管在自建 Gitea 站点（见站点首页说明）。后续规划：
- GitHub 集成：从 GitHub 克隆仓库、推送到你的 GitHub 账户仓库。
