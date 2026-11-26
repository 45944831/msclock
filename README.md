# 毫秒悬浮时钟 · msclock

Android 悬浮窗毫秒时钟。走时按网络校时后的偏移锚定，可挂在桌面/任意应用之上，
支持 16 个时间源校准（含 GNSS 与系统网络时间）、外观调节与拖动位置记忆。

- 包名：`com.wb.msclock`
- 最低系统：Android 8.0（API 26），目标 API 33
- 安装包：[dist/毫秒悬浮时钟.apk](dist/毫秒悬浮时钟.apk)

## 功能

- **毫秒显示**：`HH:MM:SS.mmm`，Choreographer 单循环驱动，默认 30fps（可切 60fps）。
  可在设置中关闭毫秒进入**秒级省电模式**（停 vsync，每秒对齐唤醒一次，空闲 CPU 接近 0）。
- **多源校时**：淘宝 / 天猫 / 京东 / 美团 / 饿了么 / 唯品会 / 拼多多 / 哔哩哔哩 / 快手 /
  小红书 / 支付宝 / 12306 / 云闪付 / 阿里云 NTP 共 14 个网络源，外加 **GNSS 授时**（API 29+，
  室外微秒级、无需网络）与**系统网络时间**（API 33+）两个可选系统源，按不确定度加权合成；
  NTP 并行请求阿里云/腾讯/cn.ntp 取中位 offset，校验 rootDelay/rootDispersion/serverResponse，
  测时与调度全走单调钟，系统跳钟不污染校时结果；
  快校 3 源约 0.3s，全量约 2.6–3.4s（启动后 6s 自动补一轮），失败按 60s→10min 退避。
- **无网/异常可诊断**：校时前用 `NET_CAPABILITY_VALIDATED` 预检，无网直接给结论不发请求；
  全失败时按占比区分「未连接网络 / 域名解析失败 / SSL 握手失败 / 全部源超时」。
- **抗抖动与守时**：校时结果按 1/unc² 置信度融合（超 3σ 判为真实跳变直接采纳），
  毫秒位不随测量噪声跳动；用最近 8 次结果拟合晶振频偏，两次校时之间按 ppm 补偿走时。
- **源健康度**：每源记录成功率与精度画像，快校动态选最优 3 源，连续失败的源指数退避。
- **不受系统时间影响**：以 `elapsedRealtime` 锚定，手动改系统时间不会让悬浮钟跳变；
  校时缓存同样以单调钟计时有效期，小步改钟不会让过期缓存生效。
- **单源可选**：时间源页可指定某个平台，失败时回退该源上次成功偏移。
- **外观可调**：50%–200% 缩放（滑杆或双指捏合，按屏幕宽高自动封顶）、30%–100% 透明度、30/60fps。
- **位置记忆**：拖动即保存，越界实时钳制，松手靠近左右边缘自动磁吸；
  重启后位置/外观/时间源/手动微调均保留。
- **熄屏省电**：熄屏暂停渲染与周期校时，亮屏立即补一帧并补校（实测熄屏 0.0% CPU）。
- **权限自检**：悬浮窗权限被撤销后服务自动退出，不留假"运行中"状态。

## 使用

1. 安装后打开应用，点「开始悬浮时钟」，按提示授予「显示在其他应用上层」。
2. 悬浮窗可拖动；长按 1 秒关闭；单击打开应用。
3. 主界面可手动微调 ±ms；「时间源」切换平台；「悬浮窗设置」调大小/透明度/刷新率/毫秒开关。
4. 悬浮窗双指捏合缩放；拖动松手吸附边缘；长按 1 秒关闭。
5. 通知栏常驻通知可一键关闭。

## 构建

手工构建链（aapt2 + javac + d8 + zipalign + apksigner），不依赖 Gradle：

```bash
# 需要 Android SDK build-tools 34.0.0 / platforms/android-34 与 JDK 8+
ANDROID_SDK=/d/SDK bash build_apk.sh
```

产物输出到仓库上一级目录 `毫秒悬浮时钟.apk`。首次构建会自动生成 `debug.keystore`；
**升级安装必须沿用同一签名**，请自行备份该密钥库。

校时算法的纯 JVM 单测（无需 Android SDK / JUnit）：

```bash
bash tests/jvm/run_tests.sh     # 33 项断言：区间交集/加权/中位数/NTP时间戳/时间格式化/结果融合/频偏拟合
```

GitHub Actions（`.github/workflows/ci.yml`）在 push/PR 时自动跑单测 + 手工链构建 APK。

## 真机测试

- 报告：[docs/真机深度测试报告_2026-10-28.md](docs/真机深度测试报告_2026-10-28.md) ·
  [docs/真机深度测试报告_2026-11-14.md](docs/真机深度测试报告_2026-11-14.md)（v1.2）
- 脚本：[tests/](tests/)（OPPO PEYM00 / ColorOS 13 / Android 13 实测；截图断言 + dumpsys 校验）
- 结果：132 项检查，PASS 130 / WARN 2 / FAIL 0（WARN：ColorOS 上划清任务即强停属 OEM 策略；
  启停循环后线程数瞬时波动，150s 后回落至基线）。v1.1 复测：深度 27/27、UI 32/32、校时网络 13/13。

自动化启停通道（需 shell/root，普通应用无权使用）：

```bash
adb shell am broadcast -a com.wb.msclock.action.CMD \
  -n com.wb.msclock/.CmdReceiver --receiver-include-background --es cmd start
adb shell am broadcast -a com.wb.msclock.action.CMD \
  -n com.wb.msclock/.CmdReceiver --receiver-include-background --es cmd stop
```

## 权限说明

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 显示悬浮窗 |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | 常驻通知，保证后台走时 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 校时（仅访问上述时间源） |

无账号、无统计、无广告，不上传任何本地数据。

## 安全与隐私说明

**校时数据的性质**：本应用通过 HTTPS `Date` 响应头 / 公共时间戳接口 / UDP NTP 读取时间，
这些请求不携带任何账号或本地数据，响应只用于计算时间偏移；偏移只留在本地，不上传。

**NTP 的信任边界**：UDP NTP（含阿里云/腾讯/cn.ntp 公共服务器）**不带身份认证**，
同一网络内的攻击者理论上可伪造应答影响显示时间（RFC 8915 NTS 才是认证方案，本项目未接入，
因 Android 标准库无 NTS 支持）。缓解手段：

- 多源按中位/加权合成，单一源被污染影响有限；
- 校验 `stratum`、`rootDelay`、`rootDispersion`、`t3−t2` 等质量门限，离群源降权或剔除；
- 结果按置信度融合并做 3σ 跳变检测。

因此本应用适合日常看时与相对校准，**不应作为安全敏感场景（如证书/交易时序证据）的唯一时间基准**。

**第三方接口依赖**：淘宝/天猫/京东等时间源来自其公开 Web 接口的 `Date` 响应头，属非契约化端点，
随时可能变更或限制。应用内置源健康度与指数退避，单点失效会自动切换，不影响整体可用性；
全部网络源不可用时仍可用 GNSS（室外）或设备本地时间。

**签名密钥**：构建使用仓库内的 `debug.keystore`（口令 `android`，仅用于自签名分发），
该密钥**不纳入 git**（见 `.gitignore`），请自行备份——升级安装必须沿用同一密钥，否则会提示签名冲突。
正式分发建议改用自有 release 密钥：将 `build_apk.sh` 中 `KS` 指向你的 keystore，
并通过环境变量注入口令，不要把正式口令写进脚本或提交到仓库。

## 更新记录

见 [CHANGELOG.md](CHANGELOG.md)。
