# build.ps1 - aapt2 + javac + d8 + zipalign + apksigner pipeline (no Gradle, no external deps)
[CmdletBinding()]
param(
    [string]$Task = 'apk',          # apk | clean | compile-only
    [switch]$Release                # unsigned release-ish (still signed with local key)
)

# Windows PowerShell 5.1 turns native stderr into a terminating error under 'Stop'; use explicit exit checks.
$ErrorActionPreference = 'Continue'
$Root      = $PSScriptRoot
$App       = Join-Path $Root 'app'
# SDK / JDK 可用环境变量覆盖，方便在其它机器或 CI 上构建
$Sdk       = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'K:\sdk' }
$Jdk       = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'K:\Tools\jdk-21.0.12.1+1' }
$BtVer     = if ($env:BUILD_TOOLS_VERSION) { $env:BUILD_TOOLS_VERSION } else { '36.1.0' }
$BuildTools = Join-Path $Sdk ('build-tools\' + $BtVer)
$AndroidJar = Join-Path $Sdk 'platforms\android-35\android.jar'
$MinSdk    = 26
$TargetSdk = 35
$Out       = Join-Path $Root 'out'
$Bld       = Join-Path $Root 'build'

$Aapt2    = Join-Path $BuildTools 'aapt2.exe'
$D8       = Join-Path $BuildTools 'd8.bat'
$ZipAlign = Join-Path $BuildTools 'zipalign.exe'
$ApkSigner= Join-Path $BuildTools 'apksigner.bat'
$Javac    = Join-Path $Jdk 'bin\javac.exe'
$KeyTool  = Join-Path $Jdk 'bin\keytool.exe'
$env:JAVA_HOME = $Jdk

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERR $msg" -ForegroundColor Red; exit 1 }

if ($Task -eq 'clean') {
    foreach ($d in @($Bld, $Out)) { if (Test-Path $d) { Remove-Item $d -Recurse -Force } }
    Write-Host 'cleaned'; exit 0
}

foreach ($p in @($Aapt2, $D8, $ZipAlign, $ApkSigner, $Javac, $AndroidJar)) {
    if (-not (Test-Path $p)) { Fail "missing tool: $p" }
}
New-Item -ItemType Directory -Force -Path $Bld, $Out | Out-Null
$gen = Join-Path $Bld 'gen'; $cls = Join-Path $Bld 'classes'; $dex = Join-Path $Bld 'dex'
foreach ($d in @($gen, $cls, $dex)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

$ts = [DateTime]::Now
$versionCode = 12
$versionName = '2.00'

# ---------- 1. AAPT2 compile ----------
Step 'aapt2 compile'
$resZip = Join-Path $Bld 'res.zip'
if (Test-Path $resZip) { Remove-Item $resZip -Force }
& $Aapt2 compile --dir (Join-Path $App 'res') -o $resZip
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile failed' }

# ---------- 2. AAPT2 link -> base apk + R.java ----------
Step 'aapt2 link'
$baseApk = Join-Path $Bld 'base.apk'
if (Test-Path $baseApk) { Remove-Item $baseApk -Force }
& $Aapt2 link -o $baseApk `
    -I $AndroidJar `
    --manifest (Join-Path $App 'AndroidManifest.xml') `
    --java $gen `
    --min-sdk-version $MinSdk `
    --target-sdk-version $TargetSdk `
    --version-code $versionCode `
    --version-name $versionName `
    --auto-add-overlay `
    $resZip
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link failed' }

# ---------- 3. javac ----------
Step 'javac'
$sources = @()
$sources += Get-ChildItem (Join-Path $App 'src') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$sources += Get-ChildItem $gen -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& $Javac -encoding UTF-8 -nowarn -source 17 -target 17 `
    -classpath $AndroidJar `
    -d $cls `
    @sources
if ($LASTEXITCODE -ne 0) { Fail 'javac failed' }

if ($Task -eq 'compile-only') { Write-Host 'compile-only ok'; exit 0 }

# ---------- 4. d8 -> classes.dex ----------
Step 'd8'
Get-ChildItem $dex -Filter *.dex -ErrorAction SilentlyContinue | Remove-Item -Force
$classesJar = Join-Path $Bld 'classes.jar'
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
Push-Location $cls
& (Join-Path $Jdk 'bin\jar.exe') cf $classesJar .
$jarExit = $LASTEXITCODE
Pop-Location
if ($jarExit -ne 0) { Fail 'jar classes failed' }
& $D8 --lib $AndroidJar --min-api $MinSdk --release --output $dex $classesJar
if ($LASTEXITCODE -ne 0) { Fail 'd8 failed' }

# ---------- 5. pack dex into apk ----------
Step 'pack'
$unAligned = Join-Path $Bld 'unaligned.apk'
Copy-Item $baseApk $unAligned -Force
Push-Location $dex
& (Join-Path $Jdk 'bin\jar.exe') uf $unAligned classes.dex
$jarOk = $LASTEXITCODE
Pop-Location
if ($jarOk -ne 0) { Fail 'jar pack failed' }

# ---------- 6. zipalign ----------
Step 'zipalign'
$aligned = Join-Path $Bld 'aligned.apk'
if (Test-Path $aligned) { Remove-Item $aligned -Force }
& $ZipAlign -p 4 $unAligned $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign failed' }

# ---------- 7. sign ----------
Step 'sign'
$ks = Join-Path $Root 'keystore.jks'
if (-not (Test-Path $ks)) {
    & $KeyTool -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias serein -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Serein Chat, O=Serein, C=CN"
    if ($LASTEXITCODE -ne 0) { Fail 'keytool failed' }
}
$final = Join-Path $Out 'SereinChat-2.00.apk'
if (Test-Path $final) { Remove-Item $final -Force }
& $ApkSigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias serein --min-sdk-version $MinSdk `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { Fail 'apksigner failed' }

Step 'verify'
& $ApkSigner verify --min-sdk-version $MinSdk $final
if ($LASTEXITCODE -ne 0) { Fail 'apksigner verify failed' }

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host ''
Write-Host "BUILD OK  $final  (${size} KB)" -ForegroundColor Green
