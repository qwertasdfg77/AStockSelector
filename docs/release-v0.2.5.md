# AStockSelector 0.2.5 下载页

这是 AStockSelector 0.2.5 发布页。

## 下载

- APK：AStockSelector-v0.2.5-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.2.5/AStockSelector-v0.2.5-release.apk>

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

- 程序更新下载后校验 APK SHA256 和大小，校验通过后再安装。
- 更新服务优先发布正式签名 release APK，并继续保留自动发布链路。
- 补充发布脚本、Dependabot、CodeQL 和单元测试。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
