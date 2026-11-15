# 用法: 在 PowerShell 中运行 .\scripts\build-windows.ps1
# 需要已安装 Android Studio (SDK Platform 35)；脚本自动下载 Gradle 8.9（仅首次）。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path (Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar'))) {
    throw "缺少 Android SDK Platform 35。请先在 Android Studio > SDK Manager 安装。SDK 目录：$env:ANDROID_HOME"
}

$gradle = Get-Command 'gradle' -ErrorAction SilentlyContinue
if ($gradle) {
    $gradleCmd = $gradle.Source
} else {
    $distribution = Join-Path $root '.build-tools'
    $gradleCmd = Join-Path $distribution 'gradle-8.9\bin\gradle.bat'
    if (-not (Test-Path $gradleCmd)) {
        New-Item -ItemType Directory -Path $distribution -Force | Out-Null
        $zip = Join-Path $distribution 'gradle-8.9-bin.zip'
        Write-Host '首次构建：获取 Gradle 8.9（官方发行包）...'
        Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile $zip
        Expand-Archive -LiteralPath $zip -DestinationPath $distribution -Force
        Remove-Item -LiteralPath $zip -Force
    }
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $androidStudioJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $androidStudioJbr 'bin\java.exe')) {
        $env:JAVA_HOME = $androidStudioJbr
        $env:PATH = (Join-Path $androidStudioJbr 'bin') + ';' + $env:PATH
    } else {
        throw '找不到 Java。请安装 JDK 17 或设置 JAVA_HOME。'
    }
}
& $gradleCmd --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
Write-Host "APK: $root\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
