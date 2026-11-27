param([string]$Class = 'com.blitz.downloader.data.RecoveryDatabaseTest')
$ErrorActionPreference = 'Stop'
$serial = 'emulator-5562'
$package = 'com.blitz.downloader.recoveryvalidation'
if ((adb -s $serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw '需要固定的 emulator-5562 模拟器' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sdk = Split-Path (Split-Path (Get-Command adb).Source)
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending |
    Where-Object { Test-Path (Join-Path $_.FullName 'aapt.exe') } | Select-Object -First 1
$artifacts = @(
    @{ path = 'app/build/outputs/apk/debug/app-debug.apk'; id = $package },
    @{ path = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'; id = "$package.test" }
)
# 安装前从两个 APK 本身读取包名和证书；任一不符即停止。
$handles = @()
try {
foreach ($artifact in $artifacts) {
    $apk = Join-Path $root $artifact.path
    $handles += [System.IO.File]::Open($apk, 'Open', 'Read', 'Read')
    $info = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0 -or !($info -match "^package: name='$([regex]::Escape($artifact.id))'")) { throw '验证包名不符' }
    if ($artifact.id -eq "$package.test") {
        $manifest = & (Join-Path $buildTools.FullName 'aapt.exe') dump xmltree $apk AndroidManifest.xml
        if ($LASTEXITCODE -ne 0 -or !($manifest -match ('android:targetPackage\([^)]*\)="' + [regex]::Escape($package) + '"'))) {
            throw 'instrumentation 目标与独立包不一致'
        }
    }
    $cert = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $apk
    if ($LASTEXITCODE -ne 0 -or !($cert -match 'certificate SHA-256 digest: 84e3c4e7fe4a940f2ef74efe2186e400f5492267ef4e86ff84c3ed8532539436$')) { throw '验证证书不符' }
}
foreach ($artifact in $artifacts) {
    adb -s $serial install -r (Join-Path $root $artifact.path)
    if ($LASTEXITCODE -ne 0) { throw '模拟器安装失败' }
}
} finally {
    foreach ($handle in $handles) { $handle.Dispose() }
}
$result = adb -s $serial shell am instrument -w -e class $Class "$package.test/com.blitz.downloader.RecoveryValidationRunner"
$result
if ($LASTEXITCODE -ne 0 -or !($result -match 'OK \([0-9]+ tests?\)')) { throw '模拟器验证未通过' }
