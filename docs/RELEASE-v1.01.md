## Serein Chat v1.01

对齐官方《构建 Chatbot》《思考模式》两页文档，修掉两处**参数与行为错误**。

### 修复
- **思考强度改用 output_config.effort（low / high / max）**
  旧实现下发 	hinking.budget_tokens，该参数在开启工具/安全校验/标题时会被服务端忽略，三档实际不生效。
  OpenAI 兼容协议改用 
easoning_effort。
- **三档文案更正为 低 / 高 / 最高**（官方映射 medium→high，原来的「中」会误导用户）
- **拒答处理**：stop_reason: refusal / inish_reason: content_filter 时用统一文案替换已展示内容，
  并且该轮不再进入后续上下文（此前会残留半截答案并污染对话）

### 其它
- 模型能力弹窗新增提示：开启思考或联网时，	emperature、	op_p 等采样参数由服务端接管
- 版本号 1.0.0 → 1.01（versionCode 2）

### 校验
- 包名 \com.serein.chat\，versionCode 2，minSdk 26 / targetSdk 35
- 大小 659.9 KB
- SHA-256：\$sha