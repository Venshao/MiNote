# 将 release 包覆盖到对外分享的固定路径（GitHub README 下载链接）
# 目标：.build-outputs/app-debug.apk —— 文件名保持不变，避免用户侧 URL 变化
param(
    [string]$ReleaseApk = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\release\app-release.apk'),
    [string]$SharedApk = (Join-Path $PSScriptRoot '..\.build-outputs\app-debug.apk')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ReleaseApk)) {
    Write-Host "Release APK not found: $ReleaseApk"
    Write-Host "Run: .\\gradlew.bat assembleRelease"
    exit 1
}

$sharedDir = Split-Path -Parent $SharedApk
if (-not (Test-Path -LiteralPath $sharedDir)) {
    New-Item -ItemType Directory -Path $sharedDir -Force | Out-Null
}

Copy-Item -LiteralPath $ReleaseApk -Destination $SharedApk -Force
$item = Get-Item -LiteralPath $SharedApk
Write-Host ("Synced shared APK: {0} ({1:N1} MB)" -f $item.FullName, ($item.Length / 1MB))
