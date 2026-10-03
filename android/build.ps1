$ErrorActionPreference = "Stop"

$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $SCRIPT_DIR

$ANDROID_HOME = "C:\Users\RinCynar\AndroidSDK"
$BUILD_TOOLS = "$ANDROID_HOME\build-tools\36.1.0"
$PLATFORM = "$ANDROID_HOME\platforms\android-36\android.jar"
$AAPT2 = "$BUILD_TOOLS\aapt2.exe"
$D8 = "$BUILD_TOOLS\d8.bat"
$ZIPALIGN = "$BUILD_TOOLS\zipalign.exe"
$APKSIGNER = "$BUILD_TOOLS\apksigner.bat"
$JDK_BIN = "C:\Program Files\Eclipse Adoptium\jdk-17\bin"
$JAVAC = "$JDK_BIN\javac.exe"
$JAR = "$JDK_BIN\jar.exe"

Write-Host "==> 1. Preparing build directories..." -ForegroundColor Cyan
if (Test-Path "build") { Remove-Item -Recurse -Force "build" }
New-Item -ItemType Directory -Force -Path "build/gen", "build/classes", "build/dex" | Out-Null

Write-Host "==> 2. Compiling resources with aapt2..." -ForegroundColor Cyan
& $AAPT2 compile --dir "src/main/res" -o "build/compiled_res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> 3. Linking resources and generating R.java..." -ForegroundColor Cyan
& $AAPT2 link "build/compiled_res.zip" -I $PLATFORM --manifest "src/main/AndroidManifest.xml" --java "build/gen" -o "build/base.apk" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> 4. Compiling Java sources with Java 8 bytecode compatibility..." -ForegroundColor Cyan
$javaFiles = Get-ChildItem -Path "build/gen", "src/main/java" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& $JAVAC -encoding UTF-8 -source 8 -target 8 -cp $PLATFORM -d "build/classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "==> 5. Converting bytecode to DEX with d8..." -ForegroundColor Cyan
$classFiles = Get-ChildItem -Path "build/classes" -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
& $D8 --release --min-api 21 --lib $PLATFORM --output "build/dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> 6. Adding classes.dex to base.apk..." -ForegroundColor Cyan
Push-Location "build/dex"
try {
    & $JAR -uf "..\base.apk" "classes.dex"
} finally {
    Pop-Location
}

Write-Host "==> 7. Aligning APK with zipalign..." -ForegroundColor Cyan
& $ZIPALIGN -p -f 4 "build/base.apk" "build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "==> 8. Signing APK with apksigner (v1 + v2 + v3)..." -ForegroundColor Cyan
& $APKSIGNER sign --ks "release.keystore" --ks-key-alias stronghold --ks-pass pass:123456 --key-pass pass:123456 --min-sdk-version 21 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "Stronghold-Protocol.apk" "build/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "==> 9. Verifying signed APK..." -ForegroundColor Cyan
& $APKSIGNER verify -v "Stronghold-Protocol.apk"

Copy-Item -Path "Stronghold-Protocol.apk" -Destination "..\Stronghold-Protocol.apk" -Force

$apk = Get-Item "Stronghold-Protocol.apk"
Write-Host ""
Write-Host ("SUCCESS! Output APK: " + $apk.FullName + " (" + [math]::Round($apk.Length / 1KB, 2) + " KB)") -ForegroundColor Green
