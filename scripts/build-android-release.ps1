param([string]$AndroidSdk = $env:ANDROID_HOME, [string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $JavaHome) { throw 'Set JAVA_HOME to a JDK or pass -JavaHome' }
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$jarsigner = Join-Path $JavaHome 'bin\jarsigner.exe'
if (-not (Test-Path $keytool) -or -not (Test-Path $jarsigner)) { throw 'A full JDK with keytool and jarsigner is required' }
$buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object Name -Match '^\d+\.\d+\.\d+$' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android SDK build-tools not found' }
$keyDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('koalacast-signing-' + [guid]::NewGuid())
$keyFile = Join-Path $keyDirectory 'release.keystore'
$savedEnvironment = @{}
$names = @('ANDROID_KEYSTORE_FILE', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD', 'JAVA_HOME')
foreach ($name in $names) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    & node scripts/check-android-release.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Android version preflight failed' }
    & node --test scripts/check-android-release.test.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Android release guard tests failed' }
    & node apps/web/scripts/check-release-policy.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Release policy failed' }
    $plaintext = & sops --decrypt --input-type dotenv --output-type json android-signing.env.enc
    if ($LASTEXITCODE -ne 0) { throw 'SOPS signing backup decryption failed' }
    $signing = $plaintext | ConvertFrom-Json
    foreach ($name in @('ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace($signing.$name)) { throw "Missing signing field: $name" }
    }
    $null = New-Item -ItemType Directory -Path $keyDirectory
    # Private material inherits access only for the current Windows identity.
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $keyDirectory /inheritance:r /grant:r "${identity}:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict signing directory permissions' }
    [System.IO.File]::WriteAllBytes($keyFile, [Convert]::FromBase64String($signing.ANDROID_KEYSTORE_BASE64))
    $env:ANDROID_KEYSTORE_FILE = $keyFile
    foreach ($name in @('ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $signing.$name, 'Process')
    }
    $plaintext = $null
    $signing = $null
    $certificate = & $keytool '-J-Duser.language=en' -list -v -keystore $keyFile -storepass:env ANDROID_KEYSTORE_PASSWORD -alias $env:ANDROID_KEY_ALIAS
    if ($LASTEXITCODE -ne 0 -or ($certificate -join "`n") -match 'CN=Android Debug') { throw 'Invalid production signing key' }
    $expected = (($certificate | Select-String 'SHA256:').Line -replace '.*SHA256:\s*', '' -replace ':', '').Trim().ToLowerInvariant()
    if ($expected -notmatch '^[a-f0-9]{64}$') { throw 'Signing certificate SHA-256 missing' }
    Push-Location apps/android
    try {
        foreach ($task in @('testDebugUnitTest', 'testReleaseUnitTest', 'lintRelease', 'assembleRelease', 'bundleRelease')) {
            & .\gradlew.bat --no-daemon $task
            if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $task" }
        }
    } finally { Pop-Location }
    $apk = Join-Path $repo 'apps/android/app/build/outputs/apk/release/app-release.apk'
    $bundle = Join-Path $repo 'apps/android/app/build/outputs/bundle/release/app-release.aab'
    $apkCertificate = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature invalid' }
    if (($apkCertificate -join "`n") -notmatch "certificate SHA-256 digest: $expected") { throw 'APK signer differs from production key' }
    & (Join-Path $buildTools.FullName 'zipalign.exe') -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment invalid' }
    & $jarsigner -verify $bundle
    if ($LASTEXITCODE -ne 0) { throw 'AAB signature invalid' }
    $bundleCertificate = & $keytool '-J-Duser.language=en' -printcert -jarfile $bundle
    if ($LASTEXITCODE -ne 0) { throw 'AAB signer missing' }
    $actual = (($bundleCertificate | Select-String 'SHA256:').Line -replace '.*SHA256:\s*', '' -replace ':', '').Trim().ToLowerInvariant()
    if ($actual -ne $expected) { throw 'AAB signer differs from production key' }
    & (Join-Path $PSScriptRoot 'check-android-native-alignment.ps1') -Archive @($apk, $bundle)
    Write-Output "Verified production certificate SHA-256: $expected"
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk, $bundle
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $plaintext = $null
    $signing = $null
    # Delete only the exact ephemeral key created by this invocation, no recursion.
    if (Test-Path -LiteralPath $keyFile) { Remove-Item -LiteralPath $keyFile -Force }
    if (Test-Path -LiteralPath $keyDirectory) { Remove-Item -LiteralPath $keyDirectory }
    Pop-Location
}
