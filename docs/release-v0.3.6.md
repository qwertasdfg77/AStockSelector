# AStockSelector 0.3.6 下载页

这是 AStockSelector 0.3.6 发布页。

## 下载

- APK：AStockSelector-v0.3.6-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.3.6/AStockSelector-v0.3.6-release.apk>

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

- 新浪 K 线非空但日期未达目标时立即切换腾讯备用源，主备源都精确校验目标收盘日。
- App 更新下载增加 APK 大小安全上限、Content-Length 校验和流式越界保护。
- 后台服务启动失败与协程取消处理更稳，通知权限不再重复请求。
- 后台进度通知限制为最多每秒刷新一次，阶段切换和最终状态仍立即更新，避免系统丢弃通知。
- Gson 升级到 2.14.0，纳入上游 JSON 解析正确性与兼容性修复。
- 修复发布准备脚本的版本同步和 PRD 自动生成，更新安全报告与维护文档。

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日通过公开行情样本确认，仍依赖数据源正常返回。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
