## Serein Chat v1.50

### 新增
- **朗读走官方 TTS**：`POST /v1/tools/tts`，音色可选 海星（`stella`，女声）/ 暗潮（`tide`，男声）；
  失败或三方服务商时自动回退系统 TTS
- **语音输入走官方 ASR**：本机录 16kHz / 16bit / 单声道 WAV → `POST /v1/tools/asr` → 文本回填输入框；
  三方服务商仍走系统识别
- **缓存可视化**：用量条在有命中时显示 `缓存 48%（命中 4.0k）`；余额面板新增累计用量
  （请求数 / 累计命中 tokens / 输入输出总量）
- **设置 → GitHub 项目主页**（带图标），点击直达仓库
- 设置新增「朗读音色」与「高级参数」说明（思考/联网开启时 sampling 参数由服务端接管）

### 修复
- **语音输入此前一直不可用**：`RECORD_AUDIO` 只在清单声明、从未运行时申请，`AudioRecord` 被系统拒绝。
  已补运行时权限请求，并在拒绝时给出提示。

### 验证（Android 15 模拟器 + 服务端探针）
```
TTS text_len=1146 voice=stella   → 200，播放正常
ASR bytes=586221                 → 200，输入框回填「这是语音识别的示例文本」
授权弹窗 Allow → 录音 → 再次点击停止 → 自动上传
```

### 校验
- 包名 `com.serein.chat`，versionCode 7，minSdk 26 / targetSdk 35
- SHA-256：`e021887ff41c8e4914f73f86ef50ee0216d43f61e68d0c37b523c7a35b57cc42`

### 热更新（同版本）
- **修复：OpenAI 兼容协议下无法联网搜索** —— 该分支此前从未下发 `tools`。现已按官方 Chat Completions 形态声明
  `{"type":"deepseek_read_url"}` 与 `{"type":"deepseek_web_search"}`，并解析响应中的 `server_tool_calls`（来源）
  与 `annotations`（引用）；同时补齐 `safety_check` 与 `title`
