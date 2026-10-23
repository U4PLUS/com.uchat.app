# Chat Client

一款 Android 端 AI 聊天应用（Kotlin + Jetpack Compose，单 Activity + MVVM）。

- 支持多模型配置（OpenAI 兼容 API）与实时流式回复；API 地址只需填到 v1 根地址（如 `https://api.deepseek.com/v1`），发送时自动拼接 `/chat/completions`
- **Agent 模式（默认常开）**：模型自主决定"说话 / 调用工具 / 继续"，内置 busybox 沙箱工具（calculator / current_time / run_shell / web_search / fetch_page），危险级别分级授权
- **多轮自主对话**：模型回复走 JSON 协议（`content` + `done`），客户端自动过滤；AI 说"还没说完"时客户端自动请求其继续输出，像真人一样连说多段直到说完
- **记忆系统**：AI 自动保存关于你的重要信息（偏好/身份/习惯），跨会话有效并随每轮注入，可在设置 → 记忆查看管理
- **联网搜索**：AI 可自主联网搜索（Firecrawl 免费额度，无需 API Key）并抓取网页阅读
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
