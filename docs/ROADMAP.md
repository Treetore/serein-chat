# 升级路线图（对照官方《构建 Chatbot》）

> 依据：DeepSeek API 文档 `guides/build_a_chatbot`、`guides/thinking_mode`（2026-11 抓取）。
> 目标：把「看起来有」的能力换成「按文档正确实现」的能力，再补官方已有的功能。

## 现状盘点

| 官方能力 | 本应用 | 状态 |
| --- | --- | --- |
| 联网搜索 `deepseek_web_search` | 已实现 | ✅ 但结果块未回传 |
| 深度思考搜索 | 工具已开 | ⚠️ 未区分 `kind: deep_search` 展示 |
| 阅读链接 `deepseek_read_url` | 已实现 | ✅ 同上 |
| 文件输入（PDF/Word/PPT/图片） | 仅图片 | ⚠️ 缺文档解析 |
| 模型内容安全 `safety_check` | 已发送 | ⚠️ 未处理 `stop_reason: refusal` |
| 用户元信息标记 `latest_reminder` | 无 | ❌ |
| 会话标题 `title` | 已实现 | ✅ |
| 朗读 / 语音输入 | 系统 TTS / RecognizerIntent | ⚠️ 非官方端点 |
| 思考模式开关 | 已实现 | ✅ |
| **思考强度** | `thinking.budget_tokens` | ❌ **参数错误，服务端忽略** |
| 上下文多轮拼接 | 只回传纯文本 | ❌ **丢 thinking / 工具块 / 引用** |
| 协议三选一 | 仅 Messages + Chat Completions | ⚠️ 缺 Responses API |

---

## P0 · 正确性缺陷（影响答案质量与稳定性，优先做）

### P0-1 思考强度改用 `output_config.effort` ✅ 已完成（v1.01）

文档明确：Anthropic 格式的强度控制是 `output_config.effort`（`low/high/max`），
`thinking.budget_tokens` 在开启工具 / 安全校验 / 标题时**一律被忽略**，本来也不是强度开关。

- 请求改为：`{"thinking":{"type":"enabled"},"output_config":{"effort":"low|high|max"}}`
- OpenAI 兼容协议改用：`reasoning_effort`（`low/high/max`）+ `thinking` 放 `extra_body`
- UI 三档改名：**低 / 高 / 最高**（文档映射表：medium→high，保留「中」会误导用户）
- 删除 `thinkingBudget` 字段与 1024/4096/16384 映射

**验收**：抓包确认请求体含 `output_config.effort` 且不含 `budget_tokens`；三档切换能在 `usage.output_tokens` 与思考耗时上观察到差异。

### P0-2 assistant 轮按原样回传内容块 ✅ 已完成（v1.10）

文档要求「客户端只需展示返回的内容块，并在下一轮把它们原样回传」。当前实现把 assistant 轮压成一个 `text` 块，
丢弃了 `thinking`（含 `signature`）、`server_tool_use`、`web_search_tool_result`、`web_fetch_tool_result` 与带
`citations` 的文本。

后果：思考连续性断裂；带工具时可能触发 400（OpenAI 格式下 `reasoning_content` 未回传会直接报错）；前缀缓存命中率下降。

- `Models.Message` 增加 `List<RawBlock> rawBlocks`（协议原始 JSON，落库）
- 序列化 assistant 轮时若存在 `rawBlocks`，直接原样输出；否则回退到当前文本拼接
- 迁移：历史消息无 rawBlocks 时保持现状（不破坏老会话）

**验收**：第二轮请求体的 `messages[1].content` 与上一轮响应块逐字段一致；带工具的思考对话不再报错；`cache_read_input_tokens` 明显上升。

### P0-3 拒答（refusal）处理 ✅ 已完成（v1.01）

`stop_reason: "refusal"` 时用本地拒答文案替换已展示的回答，并标记该轮不进入后续上下文。

**验收**：命中风险时界面显示统一文案而非半截答案；下一轮请求中不含该轮。

### P0-4 无效参数不再静默 ✅ 已完成（v1.50 说明 / v1.60 真参数）

开启工具 / 安全校验 / 标题时，`temperature`、`top_p`、`stop_sequences`、`thinking.budget_tokens` 等由服务端接管。
主题设置里不应展示会失效的采样参数，或在界面标注「当前模式下不生效」。

---

## P1 · 官方能力补齐

### P1-1 用户元信息标记 `latest_reminder` ✅ 已完成（v1.20）
把当前日期、地区、语言告诉模型，提升时效类回答质量。设置新增「地区」项（默认跟随系统语言时区）。

### P1-2 文件输入：文档解析 ✅ 已完成（v1.30）
除图片外，支持 PDF / Word / PowerPoint 走 Files API 上传并解析，解析文本拼进上下文；
附件卡片显示文件名、页数、解析状态。

### P1-3 深度思考搜索的可视化 ✅ 已完成（v1.20，卡片文案区分）
区分 `server_tool_use.kind`：普通搜索与 `deep_search` 用不同卡片样式；思考块内联展示搜索步骤。

### P1-4 引用闭环 ✅ 已完成（v1.40）
`citations_delta` 的角标点击跳转来源；一轮内聚合「引用来源」列表，并随 P0-2 一起回传，保证下一轮可复用。

---

## P2 · 协议与体验

- **Responses API**（`POST /responses`）作为第三种协议接入，复用现有 UI ✅ 已完成（v1.70）
- **官方 TTS / ASR** ✅ 已完成（v1.50）：用 `/tts`、`/asr` 替换系统能力，解决系统 TTS 音色呆板、识别中文标点差的问题
- **缓存可视化** ✅ 已完成（v1.50）：`cache_read_input_tokens` / `cache_creation_input_tokens` 已进用量条，补「本轮节省 ≈ 命中 tokens ÷ 输入 tokens」文案
- **JSON Output / Tool Calls**：给高级用户开放响应格式与自定义工具注册
- **对话前缀续写（Beta）/ FIM（Beta）**：面向开发者的实验面板

## P3 · 工程与发布

- GitHub Actions ✅ 已完成（v1.60）：打 tag 自动构建 APK 并上传 Release（本机 SDK 路径已支持环境变量覆盖）
- 归档导入体积上限 ✅ 已完成（v1.60）（当前无限制，公开仓库需防恶意 ZIP）
- 数据库会话正文加密（当前仅 API Key 加密）
- 本地性能与崩溃统计（不外传）→ 首字延迟已接入（v1.60）；**会话标题 AI 起名** ✅ 已完成（v1.80）

---

## 建议执行顺序

```
第 1 批（1–2 天）  P0-1 思考强度、P0-3 拒答、P0-4 无效参数
第 2 批（2–3 天）  P0-2 内容块回传（涉及数据模型与迁移，单独发版）
第 3 批（2–3 天）  P1-1 元信息、P1-2 文档解析、P1-4 引用闭环
第 4 批（按需）    P2 / P3
```
