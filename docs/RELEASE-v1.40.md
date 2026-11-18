## Serein Chat v1.40

### 新增
- 「N 个引用」入口现在聚合本轮**全部来源**：搜索结果的来源卡片 + 正文返回的 `citations`，
  按 URL 去重后统一列出
- 引用条目展示 站点名 · 标题 · 引用原文，点击直接跳转原文
- 只有 `citations`、没有搜索块的回复也能打开来源面板（此前是空面板）

### 验证（Android 15 模拟器）
回复带 1 条 citation 时，来源面板列出 3 条：2 条来自搜索卡片，第 3 条为 citation
（`api-docs.deepseek.com` + 引用原文），点击可跳转。

### 校验
- 包名 `com.serein.chat`，versionCode 6，minSdk 26 / targetSdk 35
- SHA-256：`e6479095b74cbc8a8ed8c1317e8759498bf7c9695556e44d057979a971da46f2`
