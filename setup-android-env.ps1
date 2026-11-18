# setup-android-env.ps1
# 用途：把本机已有的 Android 工具链接到当前会话/用户环境变量上。
# 仅写用户级环境变量（HKCU\Environment），不改系统环境；重复执行安全。
# 注：Android Studio 未安装 cmdline-tools，需要 -InstallCmdlineTools 才补齐 sdkmanager。

[CmdletBinding()]
param(
    [switch]$InstallCmdlineTools,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'

$SdkRoot     = 'K:\sdk'
$JdkHome     = 'K:\Tools\jdk-21.0.12.1+1'   # Temurin 21.0.12.1 LTS
$JbrHome     = 'K:\Android studio\jbr'    # 备选：Studio 内置 JBR
$GradleHome  = 'K:\Tools\gradle\gradle-8.14.6'

function Assert-Path([string]$p, [string]$label) {
    if (-not (Test-Path $p)) { throw "$label 不存在: $p" }
    Write-Host ("OK   {0,-14} {1}" -f $label, $p) -ForegroundColor Green
}

Write-Host "== 1. 校验关键路径 =="
Assert-Path $SdkRoot    'ANDROID_HOME'
if (-not (Test-Path $JdkHome)) { Write-Host "WARN 独立 JDK 缺失，回退 Studio JBR" -ForegroundColor Yellow; $JdkHome = $JbrHome }
Assert-Path $JdkHome    'JAVA_HOME'
Assert-Path (Join-Path $GradleHome 'bin\gradle.bat') 'GRADLE_HOME'
Assert-Path (Join-Path $SdkRoot 'platform-tools\adb.exe') 'platform-tools'
Assert-Path (Join-Path $SdkRoot 'build-tools\36.1.0\aapt2.exe') 'build-tools'

if ($InstallCmdlineTools) {
    Write-Host "`n== 1b. 安装 cmdline-tools ==" -ForegroundColor Cyan
    $zipUrl  = 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip'
    $tmpZip  = Join-Path $env:TEMP 'cmdline-tools.zip'
    $staging = Join-Path $SdkRoot 'cmdline-tools\_staging'
    if (-not (Test-Path $tmpZip)) {
        Invoke-WebRequest -Uri $zipUrl -OutFile $tmpZip -UseBasicParsing
    }
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    Expand-Archive -Path $tmpZip -DestinationPath $staging -Force
    # zip 内层是 cmdline-tools/，规范布局要求落到 cmdline-tools/latest/
    $inner = Join-Path $staging 'cmdline-tools'
    $dest  = Join-Path $SdkRoot 'cmdline-tools\latest'
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    New-Item -ItemType Directory -Path (Split-Path $dest) -Force | Out-Null
    Move-Item $inner $dest
    Remove-Item $staging -Recurse -Force
    Write-Host "OK   sdkmanager 就绪: $dest\bin\sdkmanager.bat" -ForegroundColor Green
}

if ($VerifyOnly) {
    Write-Host "`n== VerifyOnly：仅校验，不写环境变量 =="
    Write-Host "`n== 3. 子进程内临时生效自检 =="
    $env:JAVA_HOME     = $JdkHome
    $env:ANDROID_HOME  = $SdkRoot
    $env:ANDROID_SDK_ROOT = $SdkRoot
    $env:GRADLE_HOME   = $GradleHome
    & (Join-Path $JdkHome 'bin\javac.exe') -version
    & (Join-Path $SdkRoot 'platform-tools\adb.exe') version | Select-Object -First 2
    & (Join-Path $SdkRoot 'build-tools\36.1.0\aapt2.exe') version
    & (Join-Path $GradleHome 'bin\gradle.bat') -v 2>&1 | Select-String 'Gradle |Launcher JVM|Daemon JVM'
    exit 0
}

Write-Host "`n== 2. 写入用户环境变量 =="
[Environment]::SetEnvironmentVariable('JAVA_HOME',    $JdkHome,      'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkRoot,      'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $SdkRoot,  'User')
[Environment]::SetEnvironmentVariable('GRADLE_HOME', $GradleHome,   'User')

# PATH：用户级去重后追加
$want = @(
    (Join-Path $SdkRoot    'platform-tools'),
    (Join-Path $SdkRoot    'emulator'),
    (Join-Path $SdkRoot    'build-tools\36.1.0'),
    (Join-Path $SdkRoot    'cmdline-tools\latest\bin'),
    (Join-Path $GradleHome 'bin'),
    (Join-Path $JdkHome    'bin')
)
$cur = [Environment]::GetEnvironmentVariable('PATH', 'User')
$parts = @()
if ($cur) { $parts = $cur -split ';' | Where-Object { $_ -ne '' } }
foreach ($p in $want) {
    if ($parts -notcontains $p) { $parts += $p }
}
[Environment]::SetEnvironmentVariable('PATH', ($parts -join ';'), 'User')
Write-Host "OK   PATH 已补 $(($want | Where-Object { ($cur -split ';') -notcontains $_ }).Count) 项（用户级）" -ForegroundColor Green

Write-Host "`n== 3. 当前进程生效并自检 =="
$env:JAVA_HOME     = $JdkHome
$env:ANDROID_HOME  = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:GRADLE_HOME   = $GradleHome

& (Join-Path $JdkHome 'bin\javac.exe') -version
& (Join-Path $SdkRoot 'platform-tools\adb.exe') version | Select-Object -First 2
& (Join-Path $SdkRoot 'build-tools\36.1.0\aapt2.exe') version
& (Join-Path $GradleHome 'bin\gradle.bat') -v 2>&1 | Select-String 'Gradle |Launcher JVM|Daemon JVM'

Write-Host "`n完成。新开一个终端后 java/javac/gradle/adb/aapt2/sdkmanager 全部可用。" -ForegroundColor Cyan
