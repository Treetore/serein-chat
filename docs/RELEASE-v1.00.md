## Serein Chat v1.00

首个公开发行版：双协议接入、流式渲染、思考强度、余额与用量、官方归档互通，并完成一次安全基线加固。

### 安装
1. 下载 `SereinChat-1.00.apk`
2. 允许「安装未知来源应用」
3. 首次启动进入「设置 → 服务商」，填入你自己的 API 地址与 Key（本包不包含任何密钥）

### 本次内容
- 双协议服务商（OpenAI 兼容 / Anthropic 兼容），多服务商切换、模型能力探测
- SSE 流式渲染：逐字渐映、Markdown 排版、代码高亮、思考块折叠、来源卡片
- 思考强度三档（仅官方服务）；余额面板 + 近 7 天用量；单轮 tokens / 速度 / 缓存命中率
- 官方 `export_format_version=3` 归档双向导入导出
- 安全：API Key 走 Android Keystore AES-256-GCM；关闭系统备份；默认禁止明文 HTTP；剪贴板标记敏感；无日志、无 SDK、无埋点

### 校验
- 包名 `com.serein.chat`，versionCode 1，minSdk 26 / targetSdk 35
- 签名：自签名调试证书，仅用于测试安装
- SHA-256：`b2232a727352dc7c6a88be169e4a6b600b13c4e8cb7eb0f3d1b6674fdf06898a`

### 已知限制
- 第三方协议仅覆盖 OpenAI / Anthropic 两族；Gemini 原生协议未实现（可用其 OpenAI 兼容端点）
- 数据库内会话正文未加密；归档导入暂无体积上限
