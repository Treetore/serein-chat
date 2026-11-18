## Serein Chat v1.70

### 新增：Responses API（第三种协议）
服务商协议三选一：OpenAI / Anthropic / **Responses**。

- `POST {base}/responses`，解析语义化 SSE 事件：
  `response.reasoning_text.delta`（思考）、`response.output_text.delta`（正文）、
  `response.output_item.done`（`web_search_call` → 搜索来源）、`completed / incomplete / failed`（用量与结束态）
- 参数映射：`reasoning.effort`、`max_output_tokens`、`temperature`、`safety_check`、`title`、联网工具
- 用量映射：`input_tokens_details.cached_tokens` → 缓存命中

### 修复
- 流式请求改用 `Accept-Encoding: identity`，避免端点不带 `Content-Encoding` 时读到压缩字节（曾把 gzip 当正文渲染成乱码）
- Responses 输入映射：`latest_reminder` 不再映射成 assistant（此前服务端返回 400 `an assistant message must follow a user message`），改为并入用户消息前缀
- `temperature` 取一位小数下发，避免 `0.699999988079071`

```
event=response.output_text.delta → .done → content_part.done → output_item.done → completed
界面回复：「我是DeepSeek，由深度求索公司创造的AI助手…」，用量条正常
```

### 校验
- 包名 `com.serein.chat`，versionCode 9，minSdk 26 / targetSdk 35
- SHA-256：`8d2f65f2d9767d4d52265ebbcf26a3a06a8c0b9e72d4fa798b87ab1a5fa353a2`
