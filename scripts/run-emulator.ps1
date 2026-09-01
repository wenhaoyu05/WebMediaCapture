$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$JavaHome = "C:\Users\why\.cache\web-media-capture-toolchain\jdk\jdk-21.0.12.1+1"
$Sdk = "C:\Users\why\.cache\web-media-capture-toolchain\android-sdk"
$Avd = "WebMediaCaptureEmu"

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$JavaHome\bin;$Sdk\emulator;$Sdk\platform-tools;$Sdk\cmdline-tools\classic\bin;" + $env:Path

function Wait-Boot {
    adb wait-for-device | Out-Null
    $deadline = (Get-Date).AddMinutes(6)
    do {
        Start-Sleep -Seconds 2
        $boot = adb shell getprop sys.boot_completed 2>$null
        if ($boot -match "1") { return }
    } while ((Get-Date) -lt $deadline)
    throw "Emulator boot timeout"
}

if (-not (adb devices | Select-String "emulator-")) {
    Write-Host "Starting emulator $Avd ..."
    Start-Process -FilePath "$Sdk\emulator\emulator.exe" -ArgumentList @("-avd", $Avd, "-no-snapshot-save", "-gpu", "swiftshader_indirect", "-no-boot-anim")
    Wait-Boot
} else {
    Write-Host "Emulator already running."
}

Push-Location $Root
try {
    .\gradlew.bat --console=plain assembleDebug installDebug
    adb shell pm grant com.webmediacapture.debug android.permission.POST_NOTIFICATIONS 2>$null
    adb shell am start -n com.webmediacapture.debug/com.webmediacapture.ui.browser.BrowserActivity
    Write-Host "App installed and launched on emulator."
} finally {
    Pop-Location
}
