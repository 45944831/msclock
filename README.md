# 毫秒悬浮时钟 · msclock

Android 悬浮窗毫秒时钟。走时按网络校时后的偏移锚定，可挂在桌面/任意应用之上，
支持 14 个时间源校准、外观调节与拖动位置记忆。

- 包名：`com.wb.msclock`
- 最低系统：Android 8.0（API 26），目标 API 33
- 安装包：[dist/毫秒悬浮时钟.apk](dist/毫秒悬浮时钟.apk)

## 功能

- **毫秒显示**：`HH:MM:SS.mmm`，Choreographer 单循环驱动，默认 30fps（可切 60fps）。
- **多源校时**：淘宝 / 天猫 / 京东 / 美团 / 饿了么 / 唯品会 / 拼多多 / 抖音 / 快手 /
  小红书 / 支付宝 / 12306 / 云闪付 / 阿里云 NTP 共 14 源，按 RTT 加权合成；
  快校 3 源/60s，全量 14 源/10min，失败按 60s→10min 退避。
- **不受系统时间影响**：以 `elapsedRealtime` 锚定，手动改系统时间不会让悬浮钟跳变。
- **单源可选**：时间源页可指定某个平台，失败时回退该源上次成功偏移。
- **外观可调**：50%–200% 缩放（按屏幕宽高自动封顶）、30%–100% 透明度、30/60fps。
- **位置记忆**：拖动即保存，越界实时钳制；重启后位置/外观/时间源/手动微调均保留。
- **熄屏省电**：熄屏暂停渲染与周期校时，亮屏立即补一帧并补校（实测熄屏 0.0% CPU）。
- **权限自检**：悬浮窗权限被撤销后服务自动退出，不留假"运行中"状态。

## 使用

1. 安装后打开应用，点「开始悬浮时钟」，按提示授予「显示在其他应用上层」。
2. 悬浮窗可拖动；长按 1 秒关闭；单击打开应用。
3. 主界面可手动微调 ±ms；「时间源」切换平台；「悬浮窗设置」调大小/透明度/刷新率。
4. 通知栏常驻通知可一键关闭。

## 构建

手工构建链（aapt2 + javac + d8 + zipalign + apksigner），不依赖 Gradle：

```bash
# 需要 Android SDK build-tools 34.0.0 / platforms/android-34 与 JDK 8+
ANDROID_SDK=/d/SDK bash build_apk.sh
```

产物输出到仓库上一级目录 `毫秒悬浮时钟.apk`。首次构建会自动生成 `debug.keystore`；
**升级安装必须沿用同一签名**，请自行备份该密钥库。

## 真机测试

- 报告：[docs/真机深度测试报告_2026-10-28.md](docs/真机深度测试报告_2026-10-28.md)
- 脚本：[tests/](tests/)（OPPO PEYM00 / ColorOS 13 / Android 13 实测；截图断言 + dumpsys 校验）
- 结果：132 项检查，PASS 131，WARN 1（ColorOS 上划清任务强停属 OEM 策略），FAIL 0。

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

## 更新记录

见 [CHANGELOG.md](CHANGELOG.md)。
