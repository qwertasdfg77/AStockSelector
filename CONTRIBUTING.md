# 贡献指南

欢迎提交 Issue、讨论和 Pull Request。

## 适合贡献的方向

- 修复 Android 兼容性问题。
- 改进中文文档。
- 优化本地筛选速度。
- 增加可解释的策略指标。
- 增加测试、CI、截图和使用教程。
- 改进数据源容错。

## 提交代码前

请尽量确认：

- 不提交 `local.properties`、APK、缓存数据库、密钥文件。
- 不把个人 Token 写进源码。
- 代码能通过 Android Studio Gradle Sync。
- 修改策略规则时同步更新 `docs/strategy-rules.md`。
- 修改数据库结构时同步更新 `docs/AStockSelector_schema.sql`。

## 讨论策略

策略规则需要可解释、可复现。请在 Issue 或 PR 中说明：

- 新规则想过滤什么形态。
- 需要哪些日 K 字段。
- 可能误判的情况。
- 是否会明显增加筛选耗时。
