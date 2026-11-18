## Serein Chat v1.80

### 新增：会话标题 AI 起名
每轮请求携带 	itle: {enabled: true}（仅官方服务商），服务端返回的标题直接写进会话名，
顶栏与侧边栏即时刷新。

- **Responses**：读 
esponse.title
- **OpenAI**：读最后一个 chunk 的 delta.title（兼容顶层 	itle）
- **Anthropic**：沿用既有 message_delta.title 解析
- **兜底**：服务端未返回标题时，仍用首条用户消息截断做会话名

### 调试增强
- 调试 intent 支持 -e newchat 1（新建会话），便于联调

发送「介绍一下量子纠缠的基本概念」→ 顶栏标题 **「量子纠缠基本概念」**
（省掉"介绍一下"和"的"，确认是模型生成而非字符串截断）

### 校验
- 包名 com.serein.chat，versionCode 10，minSdk 26 / targetSdk 35
- SHA-256：$sha
