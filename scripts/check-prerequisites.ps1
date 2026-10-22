<#
.SYNOPSIS
Checks local build prerequisites without changing the machine by default.
.PARAMETER RunChecks
Runs host tests, lint, and the debug APK build. The wrapper may download the
pinned Gradle distribution and dependencies. It does not install an app or SDK.
#>
param([switch]$RunChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$missing = [System.Collections.Generic.List[string]]::new()
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaCommand.Source } else { $null }
}
if ($javaPath -and (Test-Path -LiteralPath $javaPath)) {
    & $javaPath -version
    if ($LASTEXITCODE -ne 0) { $missing.Add('The configured Java executable could not run.') }
} else {
    $missing.Add('A JDK (17 minimum); JAVA_HOME, when set, must point to its installation directory.')
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -and (Test-Path (Join-Path $projectRoot 'local.properties'))) {
    $sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) { $sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
if (-not $sdk -and $env:LOCALAPPDATA) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $sdk -or -not (Test-Path (Join-Path $sdk 'platforms\android-37.0\android.jar'))) {
    $missing.Add('Android SDK Platform 37.0 (platforms;android-37.0); set ANDROID_HOME or sdk.dir in local.properties.')
}
if (-not $sdk -or -not (Test-Path (Join-Path $sdk 'build-tools\37.0.0\aapt2.exe'))) {
    $missing.Add('Android SDK Build Tools 37.0.0.')
}
$gradleCache = $env:GRADLE_USER_HOME
if (-not $gradleCache) { $gradleCache = Join-Path $env:USERPROFILE '.gradle' }
$distributionRoot = Join-Path $gradleCache 'wrapper\dists\gradle-9.8.2-bin'
$cachedGradle = if (Test-Path $distributionRoot) {
    Get-ChildItem $distributionRoot -Filter gradle.bat -File -Recurse | Select-Object -First 1
} else { $null }
if ($missing.Count -gt 0) {
    Write-Output 'Build checks cannot run without the following prerequisites:'
    $missing | ForEach-Object { Write-Output ('- ' + $_) }
    Write-Output 'No tooling was installed or downloaded by this script.'
    exit 2
}
Write-Output 'Java, SDK Platform 37.0, and Build Tools 37.0.0 were found.'
if ($cachedGradle) {
    Write-Output 'The pinned Gradle 9.8.2 distribution is cached.'
} else {
    Write-Output 'The pinned Gradle distribution is not cached; -RunChecks permits its first download.'
}
if ($RunChecks) {
    $gradleArguments = @('--max-workers=2', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
    Write-Output 'Running checks; Gradle, dependency, and host-test runtime downloads are permitted. SDK auto-download remains disabled.'
    $checkExitCode = 0
    Push-Location $projectRoot
    try {
        & .\gradlew.bat @gradleArguments
        $checkExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    exit $checkExitCode
} else {
    Write-Output 'Read-only check complete. No tooling was installed or downloaded.'
}
