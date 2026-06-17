# AStockSelector 0.2.1 下载页

这是 AStockSelector 开源后的首个公开版本，适合安装试用、验证数据更新流程和阅读源码。

## 下载

- APK：`AStockSelector-0.2.1-debug.apk`
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.2.1/AStockSelector-0.2.1-debug.apk>
- SHA256：`d3259f49471e797db91d1bd4bb84d7098d58943fb6024042c84a9216c42f62a4`

## 安装

1. 用手机下载 APK，或从电脑发送到手机。
2. 打开 APK。
3. 如果系统提示未知来源，允许当前浏览器或文件管理器安装。
4. 首次打开 App 后，点击“智能更新并筛选”。

详细说明见：[docs/install.md](install.md)

## 当前 APK 类型

当前附件是 `debug APK`，用于开源试用和功能验证，不代表应用商店正式发布包。

正式签名 APK 流程已经在仓库中预留。维护者配置 GitHub Secrets 后，后续 tag 发布会自动生成 `release APK`、`SHA256SUMS.txt`、`BUILD_INFO.txt` 和签名报告。

签名说明见：[docs/signing-release.md](signing-release.md)

## 主要功能

- 今日信号页面。
- 智能更新并筛选。
- 本地 SQLite 缓存。
- 新浪主数据源、腾讯备用数据源。
- 四个预设战法：年线首板、九阳蓄势、博弈K、低位启动。
- 自定义筛选器。
- 新入选股票置顶和“新”标识。
- GitHub 程序更新检测。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。
- 首次更新缓存可能较慢，具体耗时取决于手机性能、网络和数据源可用性。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
