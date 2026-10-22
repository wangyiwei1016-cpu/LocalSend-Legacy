# LocalSend 老机版（LocalSend Legacy）一键构建 / 签名校验 / 交付 / 安装
#
# 用法（PowerShell 7）:
#   .\build.ps1                          # 构建 debug APK + 校验 + 复制到 dist/
#   .\build.ps1 -Release                 # 构建 release APK（需要 keystore.properties）
#   .\build.ps1 -Install                 # 构建 debug 并安装到设备后启动
#   .\build.ps1 -Release -Install        # 构建 release 并安装启动
#   .\build.ps1 -Install -Serial <serial>
#   .\build.ps1 -NoBuild                 # 跳过 Gradle，仅校验/复制已有 APK
#
# 校验项:
#   * aapt2 dump badging: 包名 / minSdk / targetSdk / versionCode / versionName / 启动 Activity
#   * apksigner verify -v: v1(JAR) 与 v2 签名（Android 2.2/API 8 只认 v1，必须为 true）
#
# 交付物:
#   dist\LocalSendLegacy-debug.apk
#   dist\LocalSendLegacy-release.apk
#
# 环境变量（可选，未设置时自动探测）:
#   JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME

param(
    [switch]$Release,
    [switch]$Install,
    [switch]$NoBuild,
    [string]$Serial = "",
    [string]$ExpectedPackage = "com.legacy.localsend",
    [int]$ExpectedMinSdk = 8,
    [int]$ExpectedTargetSdk = 30,
    [string]$ExpectedVersionName = "1.2",
    [int]$ExpectedVersionCode = 3
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$distDir = Join-Path $root "dist"
$localDir = Join-Path $root "_local"
$logDir = Join-Path $localDir "logs"
New-Item -ItemType Directory -Force -Path $distDir, $logDir | Out-Null

$variant = if ($Release) { "release" } else { "debug" }
$task = if ($Release) { "assemble$($variant.Substring(0,1).ToUpper() + $variant.Substring(1))" } else { "assembleDebug" }
$apkRel = if ($Release) { "app\build\outputs\apk\release\app-release.apk" } else { "app\build\outputs\apk\debug\app-debug.apk" }
$apkPath = Join-Path $root $apkRel
$deliverApk = Join-Path $distDir "LocalSendLegacy-$variant.apk"

function Write-Step([string]$text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

function Resolve-BuildTool([string]$exeName) {
    $btRoot = Join-Path $env:ANDROID_HOME "build-tools"
    if (-not (Test-Path $btRoot)) { throw "找不到 build-tools 目录: $btRoot" }
    $dirs = Get-ChildItem $btRoot -Directory | Sort-Object -Property @{ Expression = { try { [version]$_.Name } catch { [version]"0.0" } } } -Descending
    foreach ($d in $dirs) {
        $p = Join-Path $d.FullName $exeName
        if (Test-Path $p) { return $p }
    }
    throw "在 $btRoot 下找不到 $exeName"
}

function Invoke-GradleBuild([string]$buildTask) {
    $gradlew = Join-Path $root "gradlew.bat"
    if (-not (Test-Path $gradlew)) { throw "找不到 $gradlew" }
    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $logPath = Join-Path $logDir ("gradle-{0}-attempt{1}.log" -f $buildTask, $attempt)
        Write-Host ">>> gradlew $buildTask（第 $attempt/$maxAttempts 次，日志: $logPath）"
        $output = & $gradlew -p $root --console=plain $buildTask 2>&1 | Tee-Object -FilePath $logPath
        $code = $LASTEXITCODE
        if ($code -eq 0) { return $logPath }
        $text = ($output | Out-String)
        if ($text -match "Timeout waiting to lock|Could not acquire|waiting to lock|has been locked by another|file lock" -and $attempt -lt $maxAttempts) {
            Write-Host "Gradle 文件锁冲突，8 秒后重试 ..." -ForegroundColor Yellow
            Start-Sleep -Seconds 8
            continue
        }
        throw "Gradle $buildTask 失败（exit=$code），日志: $logPath"
    }
}

# ---------------------------------------------------------------- 环境变量
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $jdkCandidates = @(
        "D:\Program Files\Huawei\DevEco Studio\jbr",
        "D:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Java\jdk-17",
        "C:\Program Files\Java\jdk-21"
    )
    $jdk = $jdkCandidates | Where-Object { Test-Path (Join-Path $_ "bin\javac.exe") } | Select-Object -First 1
    if (-not $jdk) { throw "未找到 JDK 17+，请设置 JAVA_HOME" }
    $env:JAVA_HOME = $jdk
}
if (-not $env:ANDROID_HOME) {
    $localProps = Join-Path $root "local.properties"
    if (Test-Path $localProps) {
        $sdkLine = (Get-Content $localProps | Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1)
        if ($sdkLine) { $env:ANDROID_HOME = ($sdkLine -replace "^sdk\.dir=", "").Replace("\:", ":").Replace("\\", "\") }
    }
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    throw "未找到 Android SDK，请设置 ANDROID_HOME 或在 local.properties 中配置 sdk.dir"
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

if ($Release -and -not (Test-Path (Join-Path $root "keystore.properties"))) {
    throw "release 构建需要仓库根目录的 keystore.properties（见 keystore.properties.example），否则只能构建 debug"
}

Write-Step "环境"
Write-Host "JAVA_HOME        = $env:JAVA_HOME"
Write-Host "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Host "variant          = $variant"

try {
    # ------------------------------------------------------------ 构建
    if ($NoBuild) {
        Write-Step "跳过构建（-NoBuild）"
    }
    else {
        Write-Step "Gradle 构建 $task"
        $log = Invoke-GradleBuild $task
        Write-Host "构建成功，日志: $log" -ForegroundColor Green
    }

    if (-not (Test-Path $apkPath)) { throw "APK 不存在: $apkPath" }

    # ------------------------------------------------------------ aapt2 badging 校验
    Write-Step "aapt2 dump badging 校验"
    $aapt2 = Resolve-BuildTool "aapt2.exe"
    $badging = (& $aapt2 dump badging $apkPath) 2>&1
    $badgingText = ($badging | Out-String)
    $badgingText | Set-Content -Path (Join-Path $logDir "aapt2-badging-$variant.txt") -Encoding UTF8
    Write-Host $badgingText.Trim()

    $checks = [System.Collections.Generic.List[object]]::new()
    function Add-Check([string]$name, [bool]$ok, [string]$detail) {
        $checks.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    }

    $pkgLine = ($badging | Where-Object { $_ -match "^package:" } | Select-Object -First 1)
    if (-not $pkgLine) { throw "aapt2 badging 输出中没有 package 行，APK 可能损坏" }
    $pkgName = if ($pkgLine -match "name='([^']*)'") { $Matches[1] } else { "" }
    $verCode = if ($pkgLine -match "versionCode='([^']*)'") { $Matches[1] } else { "" }
    $verName = if ($pkgLine -match "versionName='([^']*)'") { $Matches[1] } else { "" }
    $sdkLine = ($badging | Where-Object { $_ -match "^(minSdkVersion|sdkVersion):" } | Select-Object -First 1)
    $minSdk = if ($sdkLine -and $sdkLine -match ":'(\d+)'") { $Matches[1] } else { "" }
    $targetLine = ($badging | Where-Object { $_ -match "^targetSdkVersion:" } | Select-Object -First 1)
    $targetSdk = if ($targetLine -and $targetLine -match ":'(\d+)'") { $Matches[1] } else { "" }
    $launchLine = ($badging | Where-Object { $_ -match "^launchable-activity:" } | Select-Object -First 1)
    $launchActivity = if ($launchLine -and $launchLine -match "name='([^']*)'") { $Matches[1] } else { "" }

    Add-Check "包名 = $ExpectedPackage" ($pkgName -eq $ExpectedPackage) "实际: $pkgName"
    Add-Check "minSdk = $ExpectedMinSdk" ([string]$minSdk -eq [string]$ExpectedMinSdk) "实际: $minSdk"
    Add-Check "targetSdk = $ExpectedTargetSdk" ([string]$targetSdk -eq [string]$ExpectedTargetSdk) "实际: $targetSdk"
    Add-Check "versionCode = $ExpectedVersionCode" ([string]$verCode -eq [string]$ExpectedVersionCode) "实际: $verCode"
    Add-Check "versionName = $ExpectedVersionName" ([string]$verName -eq [string]$ExpectedVersionName) "实际: $verName"
    Add-Check "启动 Activity = $ExpectedPackage.ui.MainActivity" ($launchActivity -eq "$ExpectedPackage.ui.MainActivity") "实际: $launchActivity"

    # ------------------------------------------------------------ apksigner 校验
    Write-Step "apksigner verify 校验（v1 必须为 true）"
    $apksigner = Resolve-BuildTool "apksigner.bat"
    $sigOut = (& $apksigner verify -v --print-certs $apkPath) 2>&1
    $sigCode = $LASTEXITCODE
    $sigText = ($sigOut | Out-String)
    $sigText | Set-Content -Path (Join-Path $logDir "apksigner-$variant.txt") -Encoding UTF8
    Write-Host $sigText.Trim()

    $v1 = if ($sigText -match "Verified using v1 scheme \(JAR signing\):\s*(true|false)") { $Matches[1] } else { "unknown" }
    $v2 = if ($sigText -match "Verified using v2 scheme \(APK Signature Scheme v2\):\s*(true|false)") { $Matches[1] } else { "unknown" }
    Add-Check "apksigner 校验通过" ($sigCode -eq 0) "exit=$sigCode"
    Add-Check "v1 (JAR) 签名 = true（API 8 必需）" ($v1 -eq "true") "实际: $v1"
    Add-Check "v2 签名 = true" ($v2 -eq "true") "实际: $v2"

    # ------------------------------------------------------------ 复制交付 APK
    Write-Step "复制交付 APK"
    Copy-Item -Path $apkPath -Destination $deliverApk -Force
    $size = (Get-Item $deliverApk).Length
    $sha = (Get-FileHash -Algorithm SHA256 $deliverApk).Hash
    Write-Host ("交付 APK : {0}" -f $deliverApk)
    Write-Host ("文件大小 : {0:N0} bytes ({1:N2} MB)" -f $size, ($size / 1MB))
    Write-Host ("SHA256   : {0}" -f $sha)

    # ------------------------------------------------------------ 结果汇总
    Write-Step "校验结果"
    $failed = @()
    foreach ($c in $checks) {
        if ($c.Ok) {
            Write-Host ("[PASS] {0}  ({1})" -f $c.Name, $c.Detail) -ForegroundColor Green
        }
        else {
            Write-Host ("[FAIL] {0}  ({1})" -f $c.Name, $c.Detail) -ForegroundColor Red
            $failed += $c
        }
    }
    Write-Host ""
    Write-Host ("badging : package={0} versionCode={1} versionName={2} minSdk={3} targetSdk={4}" -f $pkgName, $verCode, $verName, $minSdk, $targetSdk)
    Write-Host ("signing : v1={0} v2={1} (apksigner exit={2})" -f $v1, $v2, $sigCode)
    Write-Host ("deliver : {0}" -f $deliverApk)
    Write-Host ("sha256  : {0}" -f $sha)

    if ($failed.Count -gt 0) { throw "APK 校验失败（$($failed.Count) 项）" }
    Write-Host ""
    Write-Host "APK 构建与校验全部通过。" -ForegroundColor Green

    # ------------------------------------------------------------ 安装（可选）
    if ($Install) {
        Write-Step "安装到设备并启动"
        $adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
        if (-not (Test-Path $adb)) { throw "找不到 adb: $adb" }
        $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
        if ($devices.Count -eq 0) { throw "没有在线设备（adb devices 为空）" }
        $serials = @($devices | ForEach-Object { ($_ -split "\s+")[0] })
        if (-not $Serial) {
            $physical = @($serials | Where-Object { $_ -notmatch "^emulator-" })
            $Serial = if ($physical.Count -gt 0) { $physical[0] } else { $serials[0] }
        }
        if (-not ($serials -contains $Serial)) { throw "设备 $Serial 不在线（在线: $($serials -join ', ')）" }
        Write-Host "目标设备: $Serial"
        & $adb -s $Serial install -r $deliverApk
        if ($LASTEXITCODE -ne 0) { throw "adb install -r 失败（exit=$LASTEXITCODE）" }
        & $adb -s $Serial shell am force-stop $ExpectedPackage | Out-Null
        $startOut = & $adb -s $Serial shell am start -n "$ExpectedPackage/.ui.MainActivity" 2>&1
        Write-Host ($startOut | Out-String).Trim()
        if (($startOut | Out-String) -match "Error") { throw "am start 失败: $startOut" }
        Start-Sleep -Seconds 3
        $psOut = & $adb -s $Serial shell ps 2>&1 | Select-String -Pattern "com.legacy.localsend" -SimpleMatch
        if ($psOut) { Write-Host "进程已启动:" -ForegroundColor Green; Write-Host ($psOut | Out-String).Trim() }
        else { Write-Host "警告: adb shell ps 未看到应用进程，请检查 logcat" -ForegroundColor Yellow }
    }
}
catch {
    Write-Host ""
    Write-Host ("构建/校验/安装失败: {0}" -f $_.Exception.Message) -ForegroundColor Red
    exit 1
}
