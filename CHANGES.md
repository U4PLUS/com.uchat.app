# 最新变更（2026-10-22）

> 基线：v1.0.0 正式版（906bb7e）

## 多轮自主对话（JSON 回复协议）
- 系统提示词要求模型每条回复输出 JSON：`{"content": "...", "done": true|false}`，客户端自动过滤，界面只展示正文；
- `done=false` 表示 AI 还没说完：客户端自动追加「继续」指令再次请求，像真人一样连说多段，直到 `done=true` 结束回合；
- **续写指令不再写入对话历史**：改为请求级注入（`extraSystem` 参数），界面与备份中不再出现"你上一轮回复中 done 为 false…"这类内部提示；
- 提示词加固（面向大多数模型）：明确只输出单个 JSON、禁止代码块包裹、done 只接受布尔值、附三个示例；解析器兼容 ```json 围栏 / 前后夹带文字 / 字符串形式 done（"false"）等弱模型输出，解析失败自动降级为普通文本；
- 续写轮数上限可在「设置 → Agent 工具」调整（默认 10，0 = 不限）。

## 记忆系统（跨会话长期记忆）
- 新增记忆存储（DataStore）：AI 通过 `remember_memory` / `recall_memories` 工具自动保存、检索关于用户的重要信息（偏好/身份/习惯/事实）；
- 每轮请求自动注入记忆（最多 20 条），AI 写入的新记忆下一轮立即生效；
- 设置 → 记忆：查看 / 删除 / 清空记忆条目（重要度：高/普通/低 + 保存时间）。

## 联网搜索（参考 modsearch 实现）
- 新增 `web_search` 工具：Firecrawl keyless `/v2/search`（免费额度、无需 API Key），失败自动降级 Keenable keyless `/v1/search/public`；
- 新增 `fetch_page` 工具：Firecrawl keyless `/v2/scrape` 抓取公开网页正文（Markdown，截断 8000 字符）；
- 复用内置 ISRG Root X1 证书信任，兼容 Android 5+ 旧设备 HTTPS。

## Agent 模式默认常开
- 移除 Super Chat 开关（顶栏 ⚡ 按钮与设置页开关），Agent 模式改为默认：所有模型默认携带工具声明；
- 模型不支持 function calling（接口 4xx）时自动去掉工具降级重试一次；
- 原「Super Chat」设置子页改为「Agent 工具」：保留授权阈值与内置工具列表，新增「AI 自主续写轮数」设置。

## 移除 OpenCode 免费模型
- 删除全部免费车道代码与 UI：模型列表/编辑表单中的「获取 OpenCode 免费模型」「思考等级」「免费网关提示」、ViewModel/Repository 的拉取/测速/添加逻辑、`OpenCodeGateway` / `OpenCodeCatalog` / `OpenCodeEffort` 协议封装；
- 旧配置中已保存的免费模型保留为普通模型（不再强制流式、不再注入指纹头），可自行删除。

## API 地址改为 v1 根地址
- 添加/编辑模型时 API 地址只需填到 v1 根地址（如 `https://api.deepseek.com/v1`），发送时自动拼接 `/chat/completions`；
- 完整端点（`/v1/chat/completions`）旧格式仍兼容；`/v1/models` 端点推导同步兼容 v1 根地址。

---

# AI Chat v1.2.0 修改说明

> 基线：原始 LLM_Client 源码（v1.1.0，`clean_src` 目录重建后）
> 目标：修复 bug + 完善功能 + 兼容旧设备 HTTPS + 最低 Android 5 (API 21) + 编译出 APK

---

## 一、构建工程（原来无法编译，现已可编译出 APK）

| 项目 | 说明 |
|---|---|
| `app/build.gradle.kts` | **新增**：app 模块构建文件（原包缺失）。AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.10.01 / material3 / icons-extended / activity-compose / lifecycle / datastore-preferences / gson |
| 源码结构 | `src/main` 移入 `app/src/main`，删除根目录冗余 `AndroidManifest.xml` |
| min/target | `minSdk 21`（Android 5）、`targetSdk 34`、`compileSdk 34` |
| 版本 | versionCode 2 / versionName 1.2.0 |
| 镜像 | settings.gradle.kts 增加阿里云 Maven 镜像（google/central/gradle-plugin），加速国内依赖下载 |
| 产物 | `deliver/AIChat-v1.2.0-debug.apk`（Debug 签名，可直接安装） |

构建环境备注（仅本机编译需要，不影响源码）：ARM64 主机用 qemu 包装的 aapt2，通过 `gradle.properties` 中 `android.aapt2FromMavenOverride=/opt/aapt2/aapt2` 指定。

---

## 二、HTTPS 兼容（旧设备缺少 ISRG Root X1）

**问题**：Android 5/6 系统信任库没有 ISRG Root X1，连接 Let's Encrypt 签发的 API 会报证书校验失败；而 `network_security_config` 只在 Android 7.0+ 生效，无法覆盖旧系统。

**方案（双层）**：
1. **新增 `data/TlsConfig.kt`**：把内置 `assets/isrg_root_x1.pem` 解析为附加信任锚，与系统信任库组合成 `CombinedTrustManager`（先系统校验、失败后再用内置锚校验）；并包装 `Tls12SocketFactory` 确保启用 TLS 1.2。所有 HTTPS 连接（发消息/测试连接/拉模型列表）统一经 `AppRepository.openConnection()` 注入该 SocketFactory。
2. **`network_security_config.xml`**：在 `base-config` 的 trust-anchors 中追加 `<certificates src="@raw/isrg_root_x1" />`（对 API 24+ 双保险）。

证书文件：`app/src/main/assets/isrg_root_x1.pem` 与 `app/src/main/res/raw/isrg_root_x1.pem`（取自系统信任库，31 行 PEM）。

---

## 三、功能修复与完善

### 1. 真正的流式打字机输出（原实现名不副实）
- `sendMessage/regenerate/newChatAndSend` 的 `onChunk` 回调现实时更新 `ChatUiState.streamingContent`；
- UI 新增 `StreamingBubble`：流式片段用 `MarkdownContent` 实时渲染 + 光标闪标 + 「停止生成」按钮；
- 列表在流式增量时自动滚动到底部。

### 2. 停止生成按钮
- 新增 `UiIntent.StopGenerating` / `ChatViewModel.stopGenerating()`：置取消标志 + 取消协程；
- `AppRepository.sendMessage` 增加 `isCancelled` 回调，流式/非流式解析循环中检查并提前结束，`finally` 断开连接；被取消的请求不追加回复，已发送的用户消息保留。
- 切换会话/返回列表/清空对话时自动中断进行中的生成。

### 3. 「测试连接 / 获取模型列表」修复（原逻辑 bug）
- 原实现测试的是**已保存的激活模型**配置，编辑未保存的 URL/Key 时结果错误；
- 现改为 `repository.testApiConnection(editingModel)` / `fetchAvailableModels(editingModel)`，一律使用**正在编辑表单中的配置**。

### 4. 接线「获取模型列表」（原死代码）
- `ModelEditForm` 新增「获取模型列表」按钮（URL/Key 非空可用），拉取 `/v1/models` 后以可点击 FilterChip 展示，点击即把模型名填入输入框；
- 端点推导 `modelsEndpoint()` 兼容 `.../v1/chat/completions`、`.../chat/completions`、`.../v1/models` 三种地址形式。

### 5. 配置与聊天记录 JSON 备份 / 恢复
- 设置页新增「数据」页签：
  - **导出备份**：系统 SAF 「创建文件」对话框保存 `ai_chat_backup_<时间戳>.json`（含全部模型配置 + 聊天记录，schema v1）；
  - **导入恢复**：系统文件选择器读 JSON 并整体恢复；
  - **清空所有数据**（带确认弹窗）。
- `AppRepository` 新增 `exportAll / importAll / clearAll`。

### 6. 消息重试 / 编辑重发
- 长按任意消息弹出菜单：
  - 用户消息：**编辑并重发**（内容回填输入框 + 截断该消息及之后）、发送（复用原文）、复制、分享；
  - AI 消息：**重新生成**、复制、分享。

### 7. 其他 bug 修复
| 问题 | 修复 |
|---|---|
| 标题编辑弹窗时序 bug（editingTitle 异步加载时可能拿空标题） | 弹窗改为 `editingTitle != null` 状态驱动，`remember(editingTitle)` 同步初始化；新增 `CancelEditTitle`，删除 no-op 的 `HideEditTitle` |
| SSE `data:[DONE]`（无空格）漏判 | `isDone` 同时匹配 `data: [DONE]` 与 `data:[DONE]`，`data:` 前缀解析更健壮 |
| `readTimeout` 120s 过长（影响停止响应） | 降为 60s，配合取消标志 |

### 8. API 21（Android 5）兼容性检查
- VectorDrawable 图标、Compose/Material3 组件、DataStore、SAF（CreateDocument/OpenDocument，API 19+）、TLS 1.2 显式启用均兼容 API 21；
- 代码层证书信任专门覆盖 API 21–23（NSC 管不到的范围）。

---

## 四、构建验证结果

```
BUILD SUCCESSFUL in 2m 59s
package: name='com.aichat.app' versionName='1.2.0'
minSdkVersion:'21'  targetSdkVersion:'34'
uses-permission: INTERNET / ACCESS_NETWORK_STATE
application-label: AI Chat
认证：Signer #1 certificate DN: C=US, O=Android, CN=Android Debug（Debug 签名，可安装）
内置：assets/isrg_root_x1.pem、res/raw/isrg_root_x1.pem ✓
```

---

## 五、遗留说明（未改动，保持原样）
- API Key 仍明文存于 DataStore（未加密）；`allowBackup=true`。如需加固可后续引入 EncryptedSharedPreferences / Android Keystore 并关闭备份。
- `network_security_config` 的 `cleartextTrafficPermitted="true"` 予以保留（原项目定位支持 http 内网/自建 API），如需收紧可在安全审查时改为 false 或加开关。
- 请求体中的 `"thinking": true` 自定义字段原样保留（部分兼容服务端支持）；o1 系列如报错可自行在模型配置中关闭深度思考。

---

# AI Chat v1.3.0 追加：内置 Our Free Model 免费模型

> 需求：把 DSH 插件 `dsh-our-free-model`（opencode.ai 免密免费车道）的能力接入安卓 app。
> 插件本体是跑在 DSH 里的 Node.js 服务，无法直接打包进安卓；经实测逆向其网关协议后，在 app 内原生实现了同一通道。

## 网关协议（2026-09-25 实测验证）
- Base：`https://opencode.ai/zen/v1/chat/completions`（OpenAI 兼容 chat wire）
- 凭据：`Authorization: Bearer public`（无真实密钥）
- 指纹头：`user-agent: deepseek-harness/0.1.7 (+...) opencode/1.18.31`、`x-opencode-client: desktop`、`x-opencode-session: ses_...`、`x-opencode-request: msg_...`、`x-opencode-project: global`
- **免费档只允许流式**（stream=false 直接 403 FreeTierError）
- 请求体必须声明 `bash/glob/grep/read` 四个占位工具 + `tool_choice: none`，否则 403
- 按 session 计配额：同一对话固定 session id（429 规避），地区门 403 RegionError
- 模型清单：`GET /zen/v1/models`（需指纹头，否则 Cloudflare 1010）

## 代码改动
| 文件 | 改动 |
|---|---|
| `AppRepository.kt` | `ModelConfig` 新增 `customHeaders` / `freeGatewayFingerprint` 字段（Gson 向后兼容，旧配置默认关闭）；新增 `OpenCodeGateway`（session/request id 铸造、指纹头、工具声明，Kotlin 版 base62+sha256）；`sendMessage` 对免费网关强制流式 + 注入 `max_tokens=8192`、tools 指纹、指纹头、稳定 session；`fetchAvailableModels`/`testApiConnection` 同样附加指纹头与自定义头，403 提示地区受限可能 |
| `ChatViewModel.kt` | 新增 `addFreeModels()`：一键添加 MiMo V2.6 Flash / Nemotron 3 Ultra / Ling 3.0 Flash 三个免费模型预设；三处发送调用传入 `sessionSeed=chatId` 保证会话配额稳定 |
| `AIChatApp.kt` | 设置→模型页顶部新增「添加 Our Free Model 免费模型」按钮 + 说明文案；模型列表免费项带 ⚡ 标记；编辑表单显示免费网关提示；UiIntent 新增 `AddFreeModels` |
| `MainActivity.kt` | `AddFreeModels` 意图映射 |
| 版本 | 1.2.0 → 1.3.0（versionCode 3） |

## 使用方式
1. 设置 → 模型 → 「添加 Our Free Model 免费模型」→ 自动添加 3 个免费模型；
2. 顶部模型条切换任一免费模型即可对话（流式打字机输出）；
3. 免费模型配置支持「测试连接」「获取模型列表」（自动带指纹头）。

## 已知边界
- 免费车道按会话计配额（429 时换新对话即可）、部分地区模型受限（403）；
- 仅支持 chat wire 模型（muse-spark 等走 /responses 的模型未接入）；
- 免费通道可用性与上游网关政策相关，可能随时变化。

# AI Chat v1.4.0 追加：获取 OpenCode 免费模型（实测速度 + 思考等级 + 思考过程展示）

> 需求：把「添加 Our Free Model 免费模型」替换为「获取 OpenCode 免费模型」——从网关 API 拉取模型列表，筛选带 free 后缀的模型，逐个实测返回速度并直观展示，由用户自己决定是否添加（不自动添加）；支持特殊模型（如 MiMo 可选思考等级）；支持思考模式；处理会返回 thinking（推理过程）的模型。

## 代码改动
| 文件 | 改动 |
|---|---|
| `AppRepository.kt` | `ModelConfig` 新增 `thinkingLevel`（off/light/balanced/deep，Gson 向后兼容，旧配置解码为 null 时归一化：免费模型默认 balanced）；`ChatMessage` 新增 `reasoning` 字段；新增 `OpenCodeCatalog`（本地能力表：reasoning/视觉/上下文/思考可否关闭/走不走 chat wire，移植自插件 catalog.js）+ `OpenCodeEffort`（思考等级→输出预算：精简 2K / 均衡 8K / 深思=模型上限，思考不可关闭模型如 MiMo V2.5/2.6 各档翻倍）；新增 `fetchOpenCodeFreeModels()`（带指纹头 GET `/zen/v1/models`，按免费车道规则过滤，附能力信息）与 `testOpenCodeModelSpeed()`（最小流式请求实测首字延迟/可用性，403 地区受限、429 额度、超时分别标注）；`sendMessage` 免费车道按 `thinkingLevel` 下发预算（替代固定 8192），流式解析同时收集 `delta.content` 与 `delta.reasoning`/`reasoning_details`，并从正文剥离 `<\|thinking\|>` 等思考标签进入思考通道 |
| `ChatViewModel.kt` | `ChatUiState` 新增 `streamingReasoning`/`freeModels`/`freeModelsLoaded`/`fetchingFreeModels`/`freeSpeedResults`/`speedTestProgress`/`freeModelError`；`addFreeModels()` 重写为 `fetchOpenCodeFreeModels()`（拉清单→逐个测速）与 `addOpenCodeModel(id)`（仅用户点击「添加」才保存配置）；三处发送调用适配双通道回调并保存 reasoning |
| `AIChatApp.kt` | 模型页顶部按钮改为「获取 OpenCode 免费模型」→ 拉取后展示共 N 个免费模型 + 测速进度，按速度排序（可用且快的在前），每行显示能力标签（思考/视觉/上下文/思考不可关）、速度徽章（快<2s 绿 / 中<6s 橙 / 慢红 / 地区受限/额度/超时/不支持）、「添加/已添加」按钮；编辑表单对免费+思考模型显示思考等级 chips（关闭/精简/均衡/深思 + 预算标注，思考不可关模型不显示“关闭”且提示翻倍）；`MessageBubble`/`StreamingBubble` 新增思考过程折叠块（`ThinkingBlock`，流式中思考默认展开）；旧备份缺 `reasoning` 字段的 null 防御 |
| `MainActivity.kt` | `FetchOpenCodeModels` / `AddOpenCodeModel` 意图映射 |
| 版本 | 1.3.0 → 1.4.0（versionCode 4） |

## 使用方式
1. 设置 → 模型 → 「获取 OpenCode 免费模型」：先从网关拉取免费模型清单，再逐个实测首字速度（约十几秒）；
2. 列表按速度排序，快的前面：绿色=快、橙色=中、红色=慢/不可用（标注地区受限/额度/超时）；muse-spark（走 /responses）标注「不支持」；
3. 点某模型右侧「添加」才会加入你的模型列表（可重复获取，已添加显示「已添加」）；
4. 添加免费模型后进编辑表单：支持思考的模型（MiMo/Nemotron/Ling 等）可选思考等级：关闭(仅可关模型)·精简·均衡·深思；MiMo V2.5/2.6 思考不可关闭，档位预算自动翻倍；
5. 对话中模型先输出思考过程（可折叠查看），正文实时打字机输出；会把回复里的 thinking 标签自动剥离进思考过程。

## 已知边界
- 测速会为每个模型消耗一次极小配额（独立 session 一次性请求），额度紧张时可能显示「免费额度限制」——换新对话后再测或稍后再试；
- 免费车道按会话计配额（429 时换新对话）、部分地区模型受限（403）；
- muse-spark（/responses 接口）暂不支持；免费通道可用性随上游网关政策变化。

## v1.0.0（2026-10-01）

### 重大调整：移除联机（代理中继）功能
- 移除服务器/客户端中继模式、连接主机、远程会话/远程模型管理、实时同步等全部联机代码与 UI；
- 移除前台服务 CoreService 及相应权限声明；
- 设置页不再显示「代理中继」项。

### 设置页优化
- **删除分组标题**：设置大选项直接连排（模型 / Super Chat / 外观 / 数据 / 崩溃日志 / 关于），更简洁；
- 修复 Super Chat 设置页「已内置工具」排版（工具名与说明分两行，不再挤一行溢出）。

### 会话列表（抽屉）
- 宽度改为占屏幕左侧 50%（原 40%）；
- 整体布局兼容多种屏幕比例。

### 版本
- 版本号重置为 **1.0.0**（移除 GitPark 上全部历史 release）；
- App 内「关于」版本号改为读取实际版本（不再硬编码）。

### v1.0.0 补充（正式 Release）
- 主题默认改为**跟随系统**深色模式（新装用户随系统；旧暗色用户保留暗色）
- Release 构建启用：R8 混淆 + 资源压缩 + debug 签名（个人分发可安装）；proguard 保留 Gson 数据模型与工具类
- 发布 GitHub Release v1.0.0
## 上下文压缩与自动重试（2026-10-23）
- **上下文压缩**：历史消息估算 token 超过阈值时，最旧部分自动用当前模型压缩为中文摘要（≤300 字要点），摘要以「早期对话摘要」system 消息保留在历史中（UI 显示为可展开的摘要块）；摘要失败自动回退为窗口截断；
- 阈值可在「设置 → Agent 工具 → 上下文压缩阈值」调整：关闭 / 32k / 64k / 128k / 256k，默认 128k；
- **自动重试**：网络中断 / 超时 / 服务端 5xx 自动重试最多 10 次，每次间隔 5 秒，重试期间消息区显示错误详情与进度；4xx 不重试（工具降级逻辑保留）；可随时点「停止生成」中断。

## Markdown 图片 / 链接 / 文件渲染（2026-10-23）
- **图片**：AI 回复中的 `![描述](url)` 自动加载显示（支持 http/https 网络图、`data:image` base64 内嵌图、`file://` 本地图），加载中/失败占位提示，失败时网络图可「打开原图」；点击图片查看大图，再点关闭；
- **链接**：`[文字](网址)` 与裸 `https://…` 渲染为蓝色下划线链接，点击用系统浏览器打开（复用内置证书信任加载预览）；
- **文件**：`[文件名](https://…/xxx.pdf|zip|apk|…)` 识别为文件行（图标 + 文件名 + 打开），点击调用系统打开/下载。
## Markdown 图片按原位渲染 + send_file 文件卡片（2026-10-23）
- **图片原位展示**：`![描述](url)` 不再统一堆到消息末尾，改为在 markdown 语法所在位置原地渲染（文本流中图片前后内容与图片交错显示）；markdown 文件链接同样原位渲染；
- **send_file 工具**：AI 可调用 `send_file(name, url)` 发送文件，聊天中展示为**横 4:1 文件卡片**——左侧 1:1 文件类型图标（按扩展名配色：PDF/ZIP/APK/DOC/XLS/PPT/IMG/MED/TXT/APP 等）+ 名称；点击卡片提示「开始下载」并用系统下载器下载到「下载」目录（通知栏显示进度，无需存储权限）。
