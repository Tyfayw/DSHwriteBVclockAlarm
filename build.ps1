# Builds BVAlarm without Gradle/AndroidX, using only the Android SDK build tools.
# Usage:  pwsh -File build.ps1
param(
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if ([string]::IsNullOrEmpty($OutDir)) { $OutDir = Join-Path $root "build" }
$sdk = Join-Path (Split-Path $root -Parent) ".android-sdk"
$bt = Join-Path $sdk "build-tools\34.0.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
$keystore = Join-Path $root "keystore\bvalarm.keystore"
$ksPass = "bvalarm123"

foreach ($p in @($bt, $androidJar)) {
    if (-not (Test-Path $p)) { throw "Missing toolchain path: $p" }
}

$build = $OutDir
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\gen", "$build\classes", "$build\dex", "$build\flat" | Out-Null

Write-Host "[1/6] aapt2 compile resources"
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
& "$bt\aapt2.exe" link `
    -o "$build\unsigned.apk" `
    -I "$androidJar" `
    --manifest "$root\AndroidManifest.xml" `
    "$build\res.zip" `
    --java "$build\gen" `
    --min-sdk-version 26 `
    --target-sdk-version 34 `
    --version-code 8 `
    --version-name "1.7"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] javac"
$sources = @()
Get-ChildItem -Path "$root\src" -Recurse -Filter *.java | ForEach-Object { $sources += $_.FullName }
Get-ChildItem -Path "$build\gen" -Recurse -Filter *.java | ForEach-Object { $sources += $_.FullName }
& javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$androidJar" `
    -d "$build\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] d8"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$classesZip = "$build\classes.zip"
if (Test-Path $classesZip) { Remove-Item $classesZip -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory("$build\classes", $classesZip)
& cmd /c "`"$bt\d8.bat`" --lib `"$androidJar`" --min-api 26 --output `"$build\dex`" `"$classesZip`""
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] package + align"
$apk = "$build\unsigned.apk"
$zip = [System.IO.Compression.ZipFile]::Open($apk, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, "$build\dex\classes.dex", "classes.dex",
    [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
$zip.Dispose()

& "$bt\zipalign.exe" -f -p 4 $apk "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $keystore -Parent) | Out-Null
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keystore `
        -storepass $ksPass -keypass $ksPass -alias bvalarm -keyalg RSA -keysize 2048 `
        -validity 10000 -dname "CN=BVAlarm, OU=Personal, O=BVAlarm, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$finalApk = Join-Path $root "BVAlarm-1.7.apk"
& cmd /c "`"$bt\apksigner.bat`" sign --ks `"$keystore`" --ks-pass pass:$ksPass --key-pass pass:$ksPass --out `"$finalApk`" `"$build\aligned.apk`""
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& cmd /c "`"$bt\apksigner.bat`" verify --print-certs `"$finalApk`""
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "Done: $finalApk"

