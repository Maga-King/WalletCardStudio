param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
if (!$env:JAVA_HOME -and !(Get-Command java -ErrorAction SilentlyContinue)) {
    throw '请安装 JDK 17 或更新版本，并配置 JAVA_HOME 或 PATH。'
}
$gradle = Join-Path $project 'gradlew.bat'
$arguments = @('--console=plain', ':app:assembleDebug', ':app:lintDebug')
if ($Offline) { $arguments = @('--offline') + $arguments }
Push-Location $project
try {
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) { throw '构建或 lint 检查失败。' }
    $gradleText = Get-Content -LiteralPath (Join-Path $project 'app\build.gradle') -Raw -Encoding UTF8
    $version = [regex]::Match($gradleText, "versionName '([^']+)'").Groups[1].Value
    if (!$version) { throw '无法从 app/build.gradle 读取版本号。' }
    New-Item -ItemType Directory -Path (Join-Path $project 'dist') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $project "dist\WalletCardStudio-$version.apk") -Force
} finally { Pop-Location }
