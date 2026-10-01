# Chat Client

一款 Android 端 AI 聊天应用（Kotlin + Jetpack Compose，单 Activity + MVVM）。

- 支持多模型配置（OpenAI 兼容 API / OpenCode 免费车道）与实时流式回复
- **Super Chat（Agent 模式）**：模型自主决定"说话 / 调用工具 / 继续"，内置 busybox 沙箱工具（calculator / current_time / run_shell），危险级别分级授权
- 明亮 / 暗色 / 跟随系统主题 + 强调色自定义
- 会话管理、导出、崩溃日志页
- 兼容 Android 5.0+（minSdk 21），busybox 含 4 种 ABI

## 构建

```bash
ANDROID_HOME=<你的 SDK 路径> gradle :app:assembleDebug
```

## 许可

本项目使用 [Apache License 2.0](LICENSE)，详见 [NOTICE](NOTICE)。
内嵌 BusyBox（GPL-2.0-only）以独立进程方式调用，按原样分发，详见 NOTICE。
