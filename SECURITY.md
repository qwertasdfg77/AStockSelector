# 安全说明

## 报告安全问题

如果你发现安全问题，请使用 GitHub 的[私密漏洞报告](https://github.com/qwertasdfg77/AStockSelector/security/advisories/new)提交复现步骤，不要先创建公开 Issue。不要在公开内容中粘贴个人 Token、密钥或隐私数据。

## 敏感信息

请不要提交：

- `local.properties`
- `*.jks`
- `*.keystore`
- `keystore.properties`
- 数据源 Token 或其他 API Token
- GitHub Token
- 缓存数据库 `market_cache.db`
- 任何真实账户或交易相关信息

## 权限说明

App 使用的主要权限：

- `INTERNET`：读取公开行情和更新信息。
- `POST_NOTIFICATIONS`：后台更新进度通知。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`：保持更新任务运行。
- `WAKE_LOCK`：降低后台更新被中断的概率。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：提示用户允许后台读取。

## 构建与发布

- Gradle Wrapper 固定下载 SHA256，并启用发行地址校验。
- GitHub Actions 固定到完整提交 SHA。
- Release APK 必须使用正式密钥签名，证书 SHA256 必须匹配已发布证书。
- 发布前校验 tag、版本、APK 大小、APK SHA256 和更新元数据。
