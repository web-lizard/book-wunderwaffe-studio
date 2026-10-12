param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

if (-not $env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath $defaultSdk)) {
    $env:ANDROID_SDK_ROOT = $defaultSdk
}
if (-not $env:ANDROID_SDK_ROOT) {
    throw 'ANDROID_SDK_ROOT is not set and the standard Android SDK folder was not found.'
}

Push-Location $projectRoot
try {
    $tasks = @()
    if (-not $SkipTests) {
        $tasks += 'testDebugUnitTest'
    }
    $tasks += 'assembleDebug'
    & .\gradlew.bat --no-daemon @tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }

    $sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $releaseDir = Join-Path $projectRoot 'releases'
    $releaseApk = Join-Path $releaseDir 'book-wunderwaffe-pocket-v0.1.0-debug.apk'
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    Copy-Item -LiteralPath $sourceApk -Destination $releaseApk -Force
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $releaseApk
    Write-Host "APK: $releaseApk"
    Write-Host "SHA-256: $($hash.Hash)"
} finally {
    Pop-Location
}
