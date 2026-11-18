## Serein Chat v1.30

### 新增
- 附件支持 **PDF / Word / PowerPoint / 图片**：先经 `POST /anthropic/v1/files` 解析换取 `file_id`
- 按类型自动选择 `purpose`：文档用 `text_recognition`，纯文本 / 代码 / Markdown 用 `user_data`
- 消息中以 `{"type":"document","source":{"type":"file","file_id":...}}` 引用，排在正文之前
- 对话请求补发 `anthropic-beta: files-api-2025-04-14`
- 文档解析不再受「模型无视觉能力」限制；图片仍按模型能力校验

### 验证（Android 15 模拟器 + 服务端探针）
```
UPLOAD purpose=user_data bytes=398
DOC blocks=['document:file:file_mock_1', 'text']
ctx_roles=latest_reminder,user
```

### 限制
- 服务端解析最长 120 秒，超大文档需要等待；客户端读超时放宽到 300 秒
- 纯文本类走 `user_data`，不计费；PDF/Word/PPT 解析按页计费
- `rich_layout_recognition`（把文档里的图一起交给模型）暂未在界面开放，默认用 `text_recognition`

### 校验
- 包名 `com.serein.chat`，versionCode 5，minSdk 26 / targetSdk 35
- SHA-256：`9c0bf8fd7d2c7a4751798d153dbb04f78b372545d39899f2b990bdbe5c03c209`
