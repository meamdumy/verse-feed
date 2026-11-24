# Builds a signed Verse Feed APK using only the Android SDK build tools and a JDK (no Gradle).
# Usage:  powershell -ExecutionPolicy Bypass -File build.ps1 [-VersionName 1.0] [-VersionCode 1]
param(
    [string]$VersionName = '1.0',
    [int]$VersionCode = 1
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Invoke-Tool([string]$exe, [string[]]$toolArgs) {
    & $exe @toolArgs
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed with exit code $LASTEXITCODE" }
}

# --- locate tools ---
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version](($_.Name -split '-')[0]) } | Select-Object -Last 1 -ExpandProperty FullName
$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
$androidJar = Join-Path $platform 'android.jar'
$jdk = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME.TrimEnd('\') } else { throw 'Set JAVA_HOME to a JDK 17 or newer.' }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"   # d8 and apksigner run on this JDK
Write-Host "build-tools: $bt"
Write-Host "platform:    $platform"
Write-Host "jdk:         $jdk"

# --- clean ---
$out = Join-Path $root 'build'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null

# --- resources ---
Write-Host '[1/6] aapt2 compile'
Invoke-Tool "$bt\aapt2.exe" @('compile', '--dir', "$root\res", '-o', "$out\res.zip")
Write-Host '[2/6] aapt2 link'
Invoke-Tool "$bt\aapt2.exe" @('link', '-o', "$out\base.apk", '-I', $androidJar,
    '--manifest', "$root\AndroidManifest.xml", '-A', "$root\assets", '--java', "$out\gen",
    '--min-sdk-version', '26', '--target-sdk-version', '36',
    '--version-code', "$VersionCode", '--version-name', $VersionName, "$out\res.zip")

# --- code ---
Write-Host '[3/6] javac'
$sources = Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
[IO.File]::WriteAllLines("$out\sources.txt", $sources)
Invoke-Tool "$jdk\bin\javac.exe" @('--release', '17', '-encoding', 'UTF-8', '-Xlint:deprecation',
    '-classpath', $androidJar, '-d', "$out\classes", "@$out\sources.txt")

Write-Host '[4/6] d8'
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
[IO.File]::WriteAllLines("$out\classes.txt", $classes)
Invoke-Tool "$bt\d8.bat" @('--release', '--min-api', '26', '--lib', $androidJar, '--output', "$out\dex", "@$out\classes.txt")

# --- package ---
Write-Host '[5/6] package + zipalign'
Copy-Item "$out\base.apk" "$out\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
try { [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') } finally { $zip.Dispose() }
Invoke-Tool "$bt\zipalign.exe" @('-f', '4', "$out\unsigned.apk", "$out\aligned.apk")

# --- sign ---
Write-Host '[6/6] sign'
$signDir = Join-Path $root 'signing'
$ks = Join-Path $signDir 'release.jks'
$pwFile = Join-Path $signDir 'keystore-password.txt'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force $signDir | Out-Null
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pw = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
    [IO.File]::WriteAllText($pwFile, $pw)
    $env:VF_KS_PW = $pw
    Invoke-Tool "$jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $ks, '-storetype', 'PKCS12',
        '-alias', 'versefeed', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=Verse Feed', '-storepass:env', 'VF_KS_PW', '-keypass:env', 'VF_KS_PW')
    Write-Host "Created signing key: $ks (keep this folder; updates must be signed with the same key)"
}
$env:VF_KS_PW = ([IO.File]::ReadAllText($pwFile)).Trim()
$apk = Join-Path $dist "VerseFeed-$VersionName.apk"
Invoke-Tool "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-key-alias', 'versefeed',
    '--ks-pass', 'env:VF_KS_PW', '--key-pass', 'env:VF_KS_PW', '--out', $apk, "$out\aligned.apk")
Remove-Item Env:VF_KS_PW
Invoke-Tool "$bt\apksigner.bat" @('verify', $apk)
if (Test-Path "$apk.idsig") { Remove-Item "$apk.idsig" }

$size = [math]::Round((Get-Item $apk).Length / 1MB, 2)
Write-Host "Done: $apk ($size MB)"
