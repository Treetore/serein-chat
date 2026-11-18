## Serein Chat v1.10

按官方《构建 Chatbot》要求，实现 **assistant 内容块原样回传**。

### 变更
- 每个内容块保存服务端原始 JSON，流式过程中把增量文本 / 思考 / 签名 / 引用同步写回
- 消息级 
awJson：Anthropic 回传块数组，OpenAI 回传消息对象（含 
easoning_content）
- 请求时存在原始内容则原样回传，否则回退纯文本（旧会话兼容）
- 缺 	ype 的遗留块不参与回传，避免服务端 400

### 为什么重要
此前 assistant 轮只回传正文，思考签名、工具结果与引用全部丢失：思考连续性断裂、
工具模式下可能触发 400、前缀缓存命中率被拉低。

### 校验
- 包名 \com.serein.chat\，versionCode 3，minSdk 26 / targetSdk 35
- SHA-256：\$sha