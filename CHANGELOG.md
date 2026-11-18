# 更新日志

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