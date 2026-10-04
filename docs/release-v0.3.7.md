# AStockSelector 0.3.7 下载页

这是 AStockSelector 0.3.7 发布页。

## 下载

- APK：AStockSelector-v0.3.7-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.3.7/AStockSelector-v0.3.7-release.apk>

## 安装

1. 用手机下载 APK，或从电脑发送到手机。
2. 打开 APK。
3. 如果系统提示未知来源，允许当前浏览器或文件管理器安装。
4. 首次打开 App 后，点击“智能更新并筛选”。

详细说明见：[docs/install.md](https://github.com/qwertasdfg77/AStockSelector/blob/main/docs/install.md)

## 当前 APK 类型

当前只发布正式签名 release APK，不再在 Release 中上传 debug APK。

签名说明见：[docs/signing-release.md](https://github.com/qwertasdfg77/AStockSelector/blob/main/docs/signing-release.md)

## 主要变化

- 新增恐慌修复、趋势反包、缩量再起，保留原六个战法规则，合计九个预设战法。
- 新战法使用120条有效K线，接入缓存粗筛、增量结果、且组合、同股去重及选择保存。
- 旧勾选项保持不变，不自动勾选新增战法；规则升级后只重算筛选结果，不清空K线缓存。
- 保持新浪/腾讯读取方式、更新间隔和约270个交易日的缓存保留规则不变。
- 修复窗口外异常数值导致滚动均线失效的边界问题，并增加规则、选择保存和数据校验测试。
- 评分100只表示硬条件通过，不代表胜率；手机日线不能完整复现历史证券状态和公司行动证据过滤。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日通过公开行情样本确认，仍依赖数据源正常返回。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
