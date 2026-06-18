# AStockSelector 0.2.6 下载页

这是 AStockSelector 0.2.6 发布页。

## 下载

- APK：AStockSelector-v0.2.6-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.2.6/AStockSelector-v0.2.6-release.apk>

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

- 本地筛选新增增量结果表，按规则和交易日缓存每只股票的评估结果。
- 每天更新后只重算本次更新成功或尚未评估的股票，切换战法时优先复用结果表。
- 更新进度细化为读取列表、规划补读、补K线、保存缓存和增量筛选五个阶段。
- 后台前台服务通知增加进度条，补K线和筛选时显示当前进度。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
