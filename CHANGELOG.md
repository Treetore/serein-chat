# 更新日志

### 修复（v1.50 热更新 · 第二批）
- **设置项点击后浮层消失**：音色与地区改为原地刷新行内文字，不再触发整屏重建
  （此前调用 onProviderChanged → rebuildForTheme，会把设置浮层连同 modalHost 一起销毁）

### 修复（v1.50 热更新）
- **OpenAI 兼容协议下联网搜索无效**：`deepseek_web_search` / `deepseek_read_url` 只在 Anthropic 分支声明，
  Chat Completions 分支从未下发 `tools`，模型因此"无法联网"。已按官方 Chat Completions 形态补齐：
  `tools: [{"type":"deepseek_read_url"},{"type":"deepseek_web_search"}]`，并解析响应中的
  `server_tool_calls`（来源）与 `annotations`（引用）
- 同时补齐该协议下的 `safety_check` 与 `title`

## v1.60 — 高级参数可用化、归档防护、首字延迟、自动发版

### 新增
- **高级参数真正可用**：设置里可调「采样温度」（0–2.0，滑杆）与「单步最大输出」（默认/512/1024/2048/4096）。
  开启思考或联网时两者由服务端接管，面板显示锁定提示并置灰，符合官方"参数被忽略"的规则
- **首字延迟**：用量条新增 首字 0.8s，按轮记录在 Usage 中
- **GitHub Actions 自动发版**：推送 * 标签即在 windows-latest 上构建并附加到 Release
- **归档导入防护**：条目数 ≤ 20000、单条 ≤ 64MB、解压合计 ≤ 256MB，超限直接拒绝

### 其它
- 版本号 1.50 → 1.60（versionCode 8）

## v1.50 — 官方语音、缓存可视化、参数说明与项目入口

### 新增
- **朗读走官方 TTS**：`POST /v1/tools/tts`，音色可选 海星（stella，女声）/ 暗潮（tide，男声）；
  失败或三方服务商时自动回退系统 TTS
- **语音输入走官方 ASR**：本机录 16kHz/16bit 单声道 WAV → `POST /v1/tools/asr` → 识别文本回填输入框；
  三方服务商仍用系统识别
- **缓存可视化**：用量条在有缓存命中时显示 `缓存 48%（命中 4.0k）`；余额面板新增累计用量
  （请求数 / 累计命中 tokens / 输入输出总量）
- **设置 → GitHub 项目主页**（带图标），点击直达仓库
- 设置新增「朗读音色」，以及「高级参数」说明：思考/联网开启时 sampling 参数由服务端接管

### 修复
- **语音输入一直不可用**：`RECORD_AUDIO` 只在清单声明、从未运行时申请，导致 `AudioRecord`
  被系统拒绝（`permission denied: recording not allowed`）。已补运行时权限请求与拒绝提示

### 验证（Android 15 模拟器 + 服务端探针）
\`\`\`
TTS text_len=1146 voice=stella        → 200，播放正常
ASR bytes=586221                      → 200，输入框回填「这是语音识别的示例文本」
授权弹窗 Allow → 录音 → 停止 → 自动上传
\`\`\`

### 其它
- README 增加社群入口（QQ 群）与顶部徽章
- 版本号 1.40 → 1.50（versionCode 7）

## v1.40 — 引用闭环

### 新增
- 「N 个引用」入口现在会聚合本轮**全部来源**：搜索结果的来源卡片 + 正文返回的 citations，
  按 URL 去重后统一列出
- 引用条目展示 站点名 · 标题 · 引用原文，点击直接跳转原文
- 仅含 citations（没有搜索块）的回复也能打开来源面板（此前为空）

### 验证（模拟器）
- 回复带 1 条 citation：面板列出 3 条来源，其中第 3 条为 citation（`api-docs.deepseek.com` + 引用原文）

### 其它
- 版本号 1.30 → 1.40（versionCode 6）

## v1.30 — 文档解析（Files API）

### 新增
- 附件支持 **PDF / Word / PowerPoint / 图片**，走 `POST /anthropic/v1/files` 先解析换取 `file_id`
- 按类型自动选择 `purpose`：文档 `text_recognition`、纯文本类（txt/md/csv/json/代码）`user_data`
- 用户在消息里以 `{"type":"document","source":{"type":"file","file_id":...}}` 引用，排在正文之前
- 对话请求补发 `anthropic-beta: files-api-2025-04-14`
- 附件不再被「模型无视觉能力」拦截：文档解析不依赖视觉，图片仍按能力校验

### 验证（模拟器 + 服务端探针）
\`\`\`
UPLOAD purpose=user_data bytes=398
DOC blocks=['document:file:file_mock_1', 'text']
ctx_roles=latest_reminder,user
\`\`\`

### 其它
- 版本号 1.20 → 1.30（versionCode 5）

## v1.20 — 用户元信息标记与深度搜索展示

### 新增
- **latest_reminder（用户元信息标记）**：每轮在用户消息前插入
  `{"role":"latest_reminder","content":"<日期>,<星期>,<地区>,App,<语言>"}`，随历史一并回传；
  设置新增「地区」项（可留空）
- 深度思考搜索（`kind: deep_search`）使用独立文案，与普通联网搜索区分

### 验证（模拟器 + 服务端探针）
- 首轮：`ctx_roles=latest_reminder,user`
- 次轮：`ctx_roles=latest_reminder,user,assistant,latest_reminder,user`
- 标记内容示例：`2026-11-18,Wednesday,,App,English`

### 其它
- 版本号 1.10 → 1.20（versionCode 4）

## v1.10 — 内容块原样回传

官方要求「客户端只需展示返回的内容块，并在下一轮把它们原样回传」。此前我们把 assistant 轮压缩成纯文本，
丢弃了思考签名、工具调用与引用，导致思考连续性断裂、缓存命中率下降。

### 新增
- 每个内容块保存服务端原始 JSON（`Block.raw`），增量文本/思考/签名/引用在流式过程中同步写回
- 消息级 `rawJson`：Anthropic 协议回传块数组，OpenAI 协议回传消息对象（含 `reasoning_content`）
- 请求序列化时存在原始内容则原样回传，否则回退到纯文本拼接（老会话平滑兼容）
- 缺少 `type` 的遗留块不参与回传，避免服务端 400

### 验证（Android 15 模拟器 + 服务端探针）
- 第二轮回传结构：`blocks=['thinking','server_tool_use','web_search_tool_result','text'] sig=True cites=1`
- 思考块携带 `signature`，正文块携带 `citations`

### 其它
- 版本号 1.01 → 1.10（versionCode 3）

## v1.01 — 对齐官方思考参数与拒答处理

### 修复
- **思考强度改为 `output_config.effort`（low / high / max）**：原实现的 `thinking.budget_tokens` 被服务端忽略，
  三档实际不生效；OpenAI 兼容协议改用 `reasoning_effort`
- **三档文案更正**：低 / 高 / 最高（官方映射为 medium→high，原来的「中」会误导）
- **拒答处理**：`stop_reason: refusal` / `finish_reason: content_filter` 时用统一文案替换已展示内容，
  该轮不再进入后续上下文（此前会残留半截答案并污染上下文）

### 其它
- 模型能力弹窗提示：开启思考或联网时，temperature / top_p 等采样参数由服务端接管
- 版本号 1.0.0 → 1.01（versionCode 2）

## v1.00 — 首个公开发行版

首个可发布版本，功能与安全基线完成。

### 接入
- 双协议服务商：OpenAI 兼容 / Anthropic 兼容，多服务商管理与一键切换
- 模型能力探测（视觉 / 工具 / 思考），支持手动覆盖
- 思考强度三档（低 / 中 / 高）仅对官方服务开放

### 对话
- SSE 流式渲染，逐字渐映（帧率自适应），Markdown 实时排版，代码高亮与复制
- 思考块折叠、搜索/读链来源卡片、引用角标
- 编辑重发、分支切换、重新生成、继续生成、朗读、分享
- 会话搜索、草稿保存、中英双语、深浅色与自定义强调色/背景图

### 数据
- 与服务商官方 `export_format_version=3` 归档双向导入导出
- 余额与近 7 天用量面板，余额 30s 自动刷新；单轮用量（tokens / 速度 / 缓存命中率）

### 安全
- API Key 使用 Android Keystore AES-256-GCM 加密存储
- 关闭系统备份；默认禁止明文 HTTP；剪贴板标记敏感
- 无日志输出、无第三方 SDK、无埋点

### 修复
- 流式正文中偶发 `null` 字符串（JSON null 被当作正文）
- 流式过程中界面停止跟随滚动；结束后动作栏与用量条不可见
- 窄屏下输入栏控件相互挤压
- 思考强度入口点击无响应