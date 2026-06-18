# AStockSelector 0.2.7 下载页

这是 AStockSelector 0.2.7 发布页。

## 下载

- APK：AStockSelector-v0.2.7-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.2.7/AStockSelector-v0.2.7-release.apk>

## 安装

1. 用手机下载 APK，或从电脑发送到手机。
2. 打开 APK。
3. 如果系统提示未知来源，允许当前浏览器或文件管理器安装。
4. 首次打开 App 后，点击“智能更新并筛选”。

详细说明见：[docs/install.md](install.md)

## 当前 APK 类型

当前优先发布正式签名 release APK。Release 中也会保留 debug APK，用于开源测试。

签名说明见：[docs/signing-release.md](signing-release.md)

## 主要变化

- App 内更新改为先检测后下载，显示当前版本、最新版本和更新说明。
- APK 下载增加进度百分比、三次重试、失败清理和更明确的错误提示。
- 发布流程增加 APK 签名、SHA256、大小、latest.json 和 tag 一致性校验。
- 升级低风险 GitHub Actions 版本，并整理 Dependabot 分组和 PR 数量。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
