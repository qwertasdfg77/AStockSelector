# 正式签名 APK

本仓库不会提交正式签名用的 keystore。keystore 是发布密钥，泄露后别人可以伪造同包名更新包，必须只保存在维护者自己手里或 GitHub Secrets 中。

## 生成 keystore

在本机执行：

```powershell
keytool -genkeypair -v `
  -keystore astockselector-release.jks `
  -alias astockselector `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

请妥善保存：

- `astockselector-release.jks`
- keystore 密码
- alias
- key 密码

## 本地签名构建

设置环境变量后执行：

```powershell
$env:ANDROID_KEYSTORE_FILE="D:\keys\astockselector-release.jks"
$env:ANDROID_KEYSTORE_PASSWORD="你的keystore密码"
$env:ANDROID_KEY_ALIAS="astockselector"
$env:ANDROID_KEY_PASSWORD="你的key密码"
gradle --no-daemon :app:assembleRelease
```

生成文件通常位于：

```text
app/build/outputs/apk/release/
```

如果没有配置环境变量，Gradle 仍可生成未签名 release 包，但不能作为正式安装更新包发布。

## GitHub Actions 自动签名

把 keystore 转为 Base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\keys\astockselector-release.jks")) | Set-Clipboard
```

在 GitHub 仓库中进入：

```text
Settings -> Secrets and variables -> Actions -> New repository secret
```

添加这些 secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

之后推送 `v*` tag 时，`.github/workflows/release-apk.yml` 会自动：

1. 构建 debug APK。
2. 如果 secrets 完整，解码 keystore。
3. 构建正式签名 release APK。
4. 校验 release APK 签名并生成签名报告。
5. 把 APK、SHA256、构建信息和签名报告上传到对应 GitHub Release。

## Release 附件说明

自动发布后通常会看到这些文件：

- `AStockSelector-v版本-debug.apk`：调试包，适合测试。
- `AStockSelector-v版本-release.apk`：正式签名包，只有 secrets 完整时生成。
- `SHA256SUMS.txt`：APK 校验值。
- `BUILD_INFO.txt`：构建时间、提交、签名状态和 Actions 链接。
- `release-signing-report.txt`：正式签名 APK 的证书校验报告。

普通用户优先安装 `release.apk`。如果没有 `release.apk`，说明当前版本还没有配置正式签名密钥。

## 发布 tag 示例

```powershell
git tag v0.2.3
git push origin v0.2.3
```

注意：每次发布 APK 都应同步提升 `versionName` 和 `versionCode`。
