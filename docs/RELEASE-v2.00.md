## Serein Chat v2.00

### 新增：每轮费用估算
用量行最右侧新增**本轮花费**，按 DeepSeek 官方定价实时估算：

```
↑37 ↓36 · 38.1 tok/s · 缓存 0% · 首字 0.6s · 约 ¥0.00018
```

- 分模型计价：`flash`（0.02/1/4 元每百万）与 `v4-pro`（0.15/4.5/13.5 元每百万）
- 缓存命中/未命中分别计费，输出单列
- **区分高峰与空闲时段**：北京时间周一至周五 9:00–12:00、14:00–18:00 为高峰，其余（含周末）空闲价 = 高峰的一半
- 金额自适应精度：≥1 元两位小数，其余四位/五位，小额也看得清

> 说明：法定节假日未单独判断，节假日期间估算会偏高；结果仅供估算，实际以账单为准。

### 修复：GitHub Actions 连败 3 次
v1.70 / v1.80 / v1.90 打标签后工作流均失败，日志显示：

```
Error: The process 'C:\Android\android-sdk\cmdline-tools\16.0\bin\sdkmanager.bat'
       failed with exit code 1     ← 卡在 "Set up Android SDK" 的 --licenses 步骤
```

原因是 `android-actions/setup-android` 自动执行 `sdkmanager --licenses` 时没有传 `--sdk_root`。
已改为手工安装 cmdline-tools 并显式传 `--sdk_root`，随后安装 `platforms;android-35`、

### 校验
- 包名 `com.serein.chat`，versionCode 12，minSdk 26 / targetSdk 35
- SHA-256：`aa0533673a3297e156f188f07728e676029bbfa96f72dcd378f87b15a8647820`

### 热修复（同版本 2.00）
- **上传的文档没有进入上下文**：此前只有 Anthropic 协议会走 Files API，
  OpenAI / Responses 协议下附件从不上传，模型只看到一段文字。
  现在官方服务商在任何协议下都会先上传换取 ile_id（Anthropic 用 /anthropic/v1/files，
  其余用 /files），并补齐 Responses 的 input_file / input_image 内容块。

### 热修复校验
- 包名 com.serein.chat，versionCode 12 / versionName 2.00（未升版本）
- SHA-256：\$sha

### 热修复（同版本 2.00 · 第二批）
- **图片上传失败**：图片分支此前跳过 purpose 字段，Files API 要求必填 →
  现在统一发送（图片默认 user_data）。图片上传失败时自动退化为 base64 内联，不再中断附件流程。
- **协议差异提示**：OpenAI（Chat Completions）协议没有文档块形态，附件文档无法进入上下文，
  现在附加文档时会明确提示切换到 Anthropic / Responses。

### 第二批校验
- SHA-256：\$sha

### 热修复（同版本 2.00 · 第三批）
- **附件体验**：输入区图片显示缩略图，所有附件（图片/文档）右侧带 × 可单独删除
- **对话内图片预览**：消息气泡里的图片可直接点击全屏查看，点击任意处关闭
- **协议差异提示**：OpenAI（Chat Completions）协议附加文档时提示切换到 Anthropic / Responses

### 第三批校验
- SHA-256：\$sha

### 热修复（同版本 2.00 · 第四批）
- **语音识别 403**：ASR/TTS 的端点改为统一归一化构造（base 带 /anthropic 或 /v1 时自动纠正），
  请求体改用 setFixedLengthStreamingMode 声明长度（避免 chunked 被网关拒绝），并同时携带
  Authorization: Bearer 与 x-api-key
- 失败提示现在包含 HTTP 状态、响应体与请求 URL，便于定位

### 第四批校验
- SHA-256：\$sha