## Serein Chat v2.00

### 新增：每轮费用估算
用量行最右侧新增**本轮花费**，按 DeepSeek 官方定价实时估算：

```
↑37 ↓36 · 38.1 tok/s · 缓存 0% · 首字 0.6s · 约 ¥0.00018
```

- 分模型计价：`flash`（0.02/1/4 元每百万）与 `v4-pro`（0.15/4.5/13.5 元每百万）
- 缓存命中/未命中分别计费，输出单列
- **区分高峰与空闲时段**：北京时间周一至周五 9:00–12:00、14:00–18:00 为高峰，其余（含周末）空闲价 = 高峰的一半
- 金额自适应精度：≥1 元两位小数，其余四位/五位，小额也看得清

> 说明：法定节假日未单独判断，节假日期间估算会偏高；结果仅供估算，实际以账单为准。

### 修复：GitHub Actions 连败 3 次
v1.70 / v1.80 / v1.90 打标签后工作流均失败，日志显示：

```
Error: The process 'C:\Android\android-sdk\cmdline-tools\16.0\bin\sdkmanager.bat'
       failed with exit code 1     ← 卡在 "Set up Android SDK" 的 --licenses 步骤
```

原因是 `android-actions/setup-android` 自动执行 `sdkmanager --licenses` 时没有传 `--sdk_root`。
已改为手工安装 cmdline-tools 并显式传 `--sdk_root`，随后安装 `platforms;android-35`、
`build-tools;36.1.0`、`platform-tools`，再执行 `build.ps1`。

### 校验
- 包名 `com.serein.chat`，versionCode 12，minSdk 26 / targetSdk 35
- SHA-256：`aa0533673a3297e156f188f07728e676029bbfa96f72dcd378f87b15a8647820`
