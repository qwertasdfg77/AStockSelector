# AStockSelector 0.3.5 下载页

这是 AStockSelector 0.3.5 发布说明。正式发布后可从 GitHub Release 下载 `v0.3.5` 的签名 APK。

## 下载

- APK：AStockSelector-v0.3.5-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v0.3.5/AStockSelector-v0.3.5-release.apk>

## 三阳战法调整

- “建仓三阳”采用平衡版量化：连续 3 至 5 根实体至少占振幅 20% 的阳线，包含非涨停光头阳，并至少出现一天相对前一日 3 倍且相对前 5 日均量 1.5 倍的确认量。
- “拉升三阳”采用平衡版量化：最近 3 根实体至少占振幅 20% 的阳线，收盘和成交量逐日提高，第三阳收盘创近 5 日新高并直接形成信号。
- 两套战法均要求收盘站上 MA20，且 MA20 不低于 5 个交易日前。
- 删除原有的 75% 缩量、3.5% 回调和 MA10 支撑硬条件。
- 升级后旧策略结果自动失效并重新计算，已有日 K 缓存不会删除或重新下载。

## 安装说明

App 会校验 APK 大小和 SHA256，Android 还会校验安装包签名。正常覆盖安装会保留已有缓存和设置。

本软件只用于学习、复盘和策略研究，不构成投资建议。
