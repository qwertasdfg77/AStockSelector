# AStockSelector 0.2.4 下载页

这是重建缓存安全提示版本，重点减少误触“重建缓存”造成的等待和流量消耗。

## 下载

- APK：`AStockSelector-v0.2.4-debug.apk`
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.2.4/AStockSelector-v0.2.4-debug.apk>
- SHA256：`4fc5d7dd1c0323a30cb2bfe5b4fedbc4319681f0365e350d6313df4a89fc7ef0`

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

## 主要变化

- 点击“重建缓存”后不再直接执行，会先显示二次确认弹窗。
- 弹窗明确警告会删除手机上的旧 K 线缓存，并重新联网下载最近约 270 个交易日数据。
- 弹窗提示重建过程可能耗时较长、消耗流量，网络失败时可能需要重新更新或再次重建。
- 确认按钮文案改为“删除并重建”，取消按钮可直接关闭弹窗。
- 战法选择和参数设置不会被清除。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。
- 首次更新或重建缓存可能较慢，具体耗时取决于手机性能、网络和数据源可用性。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
