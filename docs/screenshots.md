# 真实截图采集说明

README 和 GitHub Pages 已使用真实手机截图。后续如果界面变化，需要重新采集并替换这些图片。

## 当前截图

- `today-signals.png`：今日信号页面。
- `settings-strategy.png`：设置页的策略参数区域。
- `settings-update.png`：设置页的数据更新与筛选区域。

## 推荐截图

- `today-signals.png`：今日信号页面。
- `settings-strategy.png`：设置页的策略参数区域。
- `settings-update.png`：设置与智能更新页面。
- `update-progress.png`：缓存更新进度。
- `app-update.png`：检测程序更新。
- `demo-flow.gif`：10 到 20 秒操作录屏。

截图建议放到：

```text
docs/screenshots/
```

## 用 adb 采集

Windows 上可以运行：

```powershell
.\scripts\capture-android-screenshots.ps1
```

脚本会检测 `adb`、连接设备和 App 包名，然后按提示截图。每张截图前请手动把手机切到对应页面。

如果没有 Android SDK，可以下载 Google 官方 platform-tools 后，把 `adb.exe` 所在目录加入 PATH，或把脚本参数 `-AdbPath` 指向完整路径。

## 录屏

```powershell
adb shell screenrecord /sdcard/astockselector-demo.mp4
adb pull /sdcard/astockselector-demo.mp4 docs/screenshots/demo-flow.mp4
```

GitHub README 更适合展示 GIF。可以用常见视频工具把 `mp4` 转为 `gif`，再压缩到 10 MB 以内。

## 注意

- 截图不要暴露个人 Token、账号、手机号或其他隐私。
- 股票筛选结果不代表投资建议。
- 不要把“重建缓存”的二次确认弹窗作为展示截图。
