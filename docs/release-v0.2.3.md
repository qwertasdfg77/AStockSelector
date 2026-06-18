# AStockSelector 0.2.3 下载页

这是本地筛选和缓存更新优化版本，重点减少筛选计算开销、降低联网更新调度开销，并将缓存窗口回退到约 270 个交易日。

## 下载

- APK：`AStockSelector-v0.2.3-debug.apk`
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.2.3/AStockSelector-v0.2.3-debug.apk>
- SHA256：`1d887eb53138c7f83cd501c152fee2a5717ce9eb83ddcb0fbef590518fb2201a`

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

- 本地策略筛选的 MA5/10/20/60/250 改为前缀和计算，减少重复均线计算。
- 缓存联网更新改为固定 worker 池，避免为每只股票创建一个协程。
- HTTP 重试等待改为协程 delay，避免阻塞后台线程。
- 缓存保留窗口回退为约 270 个交易日。
- 新增策略单元测试，覆盖年线首板、九阳蓄势、K 线不足和 ST 股票过滤。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。
- 首次更新或重建缓存可能较慢，具体耗时取决于手机性能、网络和数据源可用性。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
