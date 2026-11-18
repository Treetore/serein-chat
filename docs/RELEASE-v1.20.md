## Serein Chat v1.20

### 新增
- **用户元信息标记（\latest_reminder\）**：每轮在用户消息前插入
  \{"role":"latest_reminder","content":"<日期>,<星期>,<地区>,App,<语言>"}\，并随历史回传。
  设置新增「地区」项，可留空。
- 深度思考搜索（\kind: deep_search\）使用独立文案，与普通联网搜索区分

### 校验
- 包名 \com.serein.chat\，versionCode 4，minSdk 26 / targetSdk 35
- SHA-256：\$sha