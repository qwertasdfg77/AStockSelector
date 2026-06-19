# AStockSelector 0.3.0 下载页

这是 AStockSelector 0.3.0 发布页。

## 下载

- APK：AStockSelector-v0.3.0-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.3.0/AStockSelector-v0.3.0-release.apk>

## 安装

1. 用手机下载 APK，或从电脑发送到手机。
2. 打开 APK。
3. 如果系统提示未知来源，允许当前浏览器或文件管理器安装。
4. 首次打开 App 后，点击“智能更新并筛选”。

详细说明见：[docs/install.md](install.md)

## 当前 APK 类型

当前只发布正式签名 release APK，不再在 Release 中上传 debug APK。
签名说明见：[docs/signing-release.md](signing-release.md)

## 主要变化

- Release 流程改为只发布正式签名 release APK，不再上传 debug APK。
- GitHub Actions artifact 和日志保留期改为 7 天，减少旧构建产物堆积。
- Release 发布后自动读取远端 latest.json 并下载 APK，校验版本、大小和 SHA256。
- App 内程序更新增加下载进度条、百分比、重试提示和更清楚的失败原因。
- 下载失败或中断后自动清理未完成 APK，避免残留损坏安装包。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
