# 生产环境安全审查（v1.00）

审查对象：`com.serein.chat` v1.0.0（versionCode 1），审查日期 2026-11-18。
方法：源码逐文件检索 + `aapt2 dump badging` 校验 + 模拟器实机行为验证。

## 一、数据流与信任边界

```
用户输入 ──► 本机 SQLite（明文，仅本机）
     │
     └──► HTTPS（TLS，禁止明文）──► 用户填写的 API 服务商
API Key ──► Android Keystore(AES-256-GCM) ──► SharedPreferences 密文
```

不存在自有服务器、账号体系、云同步或统计上报，因此**没有"服务端泄露"面**；风险集中在设备侧与被调用的第三方接口。

## 二、逐项结论

| 编号 | 检查项 | 结论 | 处置 |
| --- | --- | --- | --- |
| A1 | API Key 明文落盘 | **发现**：原实现直接把含 key 的 JSON 存入 SharedPreferences | 已修：`SecureStore` 用 Keystore AES-256-GCM 加密，密文前缀 `enc1:`；旧明文数据读取时自动迁移 |
| A2 | 密钥随系统备份外流 | **发现**：`allowBackup="true"`，adb backup / 云备份可取出 prefs | 已修：`allowBackup="false"` |
| A3 | 明文 HTTP 可用 | **发现**：`usesCleartextTraffic="true"` 全局放开 | 已修：改为 `networkSecurityConfig`，默认 `cleartextTrafficPermitted="false"`，仅放行 `localhost/127.0.0.1/10.0.2.2` |
| A4 | 日志泄露内容 | 通过 | 全仓检索 `android.util.Log` / `System.out` 无命中；调试日志在发布前已移除 |
| A5 | 剪贴板预览泄露 | **发现**：普通 `newPlainText`，系统剪贴板浮层会显示正文 | 已修：统一 `Ui.copy()` 并置 `ClipDescription.EXTRA_IS_SENSITIVE` |
| A6 | 导出包含密钥 | 通过 | 归档只写 `manifest.json` / `chat_session_index.json` / `chat_sessions/*.json`（会话与消息），不含 provider 配置 |
| A7 | 权限最小化 | 通过 | 仅 `INTERNET`、`ACCESS_NETWORK_STATE`、`RECORD_AUDIO`；无存储、位置、通讯录、通话状态权限 |
| A8 | 第三方 SDK / 埋点 | 通过 | 无任何第三方依赖，纯 framework |
| A9 | 组件暴露面 | 通过 | 仅 `MainActivity` 导出且仅接收 `ACTION_SEND(text/plain)`；无 Service/Receiver/Provider 导出 |
| A10 | 备份文件可读性 | 通过 | SQLite 位于应用私有目录，非 root 不可读 |
| A11 | WebView / JS 桥 | 通过 | 未使用 WebView，无 JS 注入面 |
| A12 | 导入归档路径穿越 | 通过 | 只按固定前缀读取条目，不写任意路径；图片目录被跳过 |
| A13 | 归档炸弹 / 超大条目 | 部分 | 逐条目读入内存，无单条上限；建议后续加 64MB 总量限制（未阻塞发布） |
| A14 | 截图 / 最近任务快照 | 未处理 | 设置页含 Key 输入；如需更强保护可对设置浮层加 `FLAG_SECURE`（默认不加，避免影响正常截图） |

## 三、已验证的运行时行为（Android 15 模拟器）

- 保存服务商后重进设置，Key 仍可正常解密使用（加密往返成功）
- 首次启动（升级自明文存储）时，旧数据被读取并重新以密文写入
- 断网/错误 endpoint 时仅提示本地化错误文案，不打印请求体
- 导出 ZIP 解包检查：无 `key` 字段

## 四、遗留与建议

1. 数据库（会话正文）未加密。若要覆盖"设备被物理取证"场景，可接 SQLCipher 或对消息体做字段级 AES 加密；当前取舍是性能与体积。
2. 归档导入未设大小上限，公开仓库若收到恶意 ZIP，建议下一版加上条目数与字节数阈值。
3. 未做证书固定（pinning）：用户可自填 endpoint，固定证书不适用。
4. `RECORD_AUDIO` 属敏感权限，首次使用语音输入时系统会弹窗；拒绝后其它功能不受影响。
