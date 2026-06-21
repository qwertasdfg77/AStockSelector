# AStockSelector 0.3.1 下载页

这是 AStockSelector 0.3.1 发布页。

## 下载

- APK：AStockSelector-v0.3.1-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.3.1/AStockSelector-v0.3.1-release.apk>

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

- 智能更新的目标交易日不再只按周末和 15:30 估算。
- 当本地缓存落后日历目标时，先读取少量样本股票的最新日 K 日期，用数据源实际最新交易日作为更新目标。
- 节假日或数据源未出当天 K 线时，避免每次刷新都反复下载不存在的数据。
- 实际目标交易日会短时写入缓存 metadata，频繁刷新时可以直接复用。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
