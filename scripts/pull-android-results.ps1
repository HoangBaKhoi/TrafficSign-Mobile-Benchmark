<#
Lấy CSV benchmark từ Downloads/TrafficSignApp/ trên điện thoại (qua adb) và tự xếp
vào đúng thư mục Results/<Manufacturer>_<DeviceModel>/ trong repo.

Cách dùng: cắm điện thoại, bật USB debugging, chạy xong benchmark trên app, rồi:
    powershell -ExecutionPolicy Bypass -File scripts\pull-android-results.ps1
#>

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $repoRoot "Results"
$deviceRemoteDir = "/sdcard/Download/TrafficSignApp"

# Tìm adb: ưu tiên PATH, rồi tới vị trí cài mặc định của Android Studio.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $candidate = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $candidate) {
        $adb = $candidate
    }
}
if (-not $adb) {
    throw "Khong tim thay adb. Cai Android SDK platform-tools hoac them adb vao PATH."
}

$devices = & $adb devices | Select-String "`tdevice$"
if (-not $devices) {
    throw "Khong co dien thoai nao dang ket noi qua adb (adb devices rong)."
}

$stagingDir = Join-Path $env:TEMP "TrafficSignApp_pull"
if (Test-Path $stagingDir) {
    Remove-Item $stagingDir -Recurse -Force
}
New-Item -ItemType Directory -Path $stagingDir | Out-Null

Write-Output "Dang pull tu $deviceRemoteDir ..."
& $adb pull $deviceRemoteDir $stagingDir
if ($LASTEXITCODE -ne 0) {
    throw "adb pull that bai. Kiem tra da bam nut benchmark tren app chua."
}

$pulledFiles = Get-ChildItem -Path $stagingDir -Recurse -Filter "*.csv"
if (-not $pulledFiles) {
    throw "Khong tim thay file .csv nao. Kiem tra da chay xong benchmark tren app chua."
}

$pattern = '^android_(?<run>4model_100|quant_sweep)_(?<kind>summary|detail|detections)_(?<tag>.+)_(?<ts>\d{8}_\d{6})\.csv$'

$copied = @()
$skipped = @()

foreach ($file in $pulledFiles) {
    $match = [regex]::Match($file.Name, $pattern)
    if (-not $match.Success) {
        Write-Warning "Bo qua file khong dung dinh dang ten: $($file.Name)"
        continue
    }

    $deviceTag = $match.Groups["tag"].Value
    $destDir = Join-Path $resultsRoot $deviceTag
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir | Out-Null
    }

    $destPath = Join-Path $destDir $file.Name
    if (Test-Path $destPath) {
        $skipped += $file.Name
        continue
    }

    Copy-Item $file.FullName $destPath
    $copied += "$deviceTag/$($file.Name)"
}

Write-Output ""
Write-Output "Da copy $($copied.Count) file vao Results/:"
$copied | ForEach-Object { Write-Output "  $_" }

if ($skipped.Count -gt 0) {
    Write-Output ""
    Write-Output "Bo qua $($skipped.Count) file (da ton tai san trong Results/):"
    $skipped | ForEach-Object { Write-Output "  $_" }
}
