param(
    [string]$AdbPath = "adb",
    [string]$PackageName = "com.codex.astockselector",
    [string]$OutputDir = "docs/screenshots"
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    & $AdbPath @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: $($args -join ' ')"
    }
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$devices = & $AdbPath devices | Select-String -Pattern "device$"
if (-not $devices) {
    throw "No authorized Android device found. Connect the phone and allow USB debugging."
}

Invoke-Adb shell monkey -p $PackageName 1 | Out-Null
Start-Sleep -Seconds 2

$shots = @(
    @{ Name = "today-signals.png"; Prompt = "切到 今日信号 页面后按回车截图" },
    @{ Name = "custom-filter.png"; Prompt = "切到 自定义筛选 页面后按回车截图" },
    @{ Name = "settings-update.png"; Prompt = "切到 设置 页面后按回车截图" },
    @{ Name = "update-progress.png"; Prompt = "启动缓存更新并出现进度后按回车截图" }
)

foreach ($shot in $shots) {
    Read-Host -Prompt $shot.Prompt | Out-Null
    $remote = "/sdcard/$($shot.Name)"
    $local = Join-Path $OutputDir $shot.Name
    Invoke-Adb shell screencap -p $remote
    Invoke-Adb pull $remote $local
    Invoke-Adb shell rm $remote
    Write-Host "Saved $local"
}
