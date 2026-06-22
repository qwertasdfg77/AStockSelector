# AStockSelector 0.3.2 下载页

这是 AStockSelector 0.3.2 发布页。

## 下载

- APK：AStockSelector-v0.3.2-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.3.2/AStockSelector-v0.3.2-release.apk>

## 安装

1. 用手机下载 APK，或从电脑发送到手机。
2. 打开 APK。
3. 如果系统提示未知来源，允许当前浏览器或文件管理器安装。
4. 打开 App 后点击“智能更新并筛选”。

详细说明见：[docs/install.md](install.md)

## 当前 APK 类型

当前只发布正式签名 release APK，不在 Release 中上传 debug APK。
签名说明见：[docs/signing-release.md](signing-release.md)

## 主要变化

- 第一次刷新时，交易日确认不再先读取完整 A 股列表。
- 改为直接使用固定样本股票读取少量日 K，先确认数据源实际最新交易日。
- 当缓存已经是实际最新交易日时，可跳过完整股票列表读取和全市场补读，减少 App 打开后的第一次刷新耗时。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
