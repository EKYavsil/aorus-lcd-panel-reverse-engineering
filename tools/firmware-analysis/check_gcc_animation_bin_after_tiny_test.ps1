$ErrorActionPreference = "Stop"

$AssetsDir = "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\Assets"
$BinPath = Join-Path $AssetsDir "animation.bin"
$IniPath = Join-Path $AssetsDir "animation.ini"
$GifPath = Join-Path $AssetsDir "animation.gif"

function Read-U16LE([byte[]]$Bytes, [int]$Offset) {
    [BitConverter]::ToUInt16($Bytes, $Offset)
}

function Read-U32LE([byte[]]$Bytes, [int]$Offset) {
    [BitConverter]::ToUInt32($Bytes, $Offset)
}

Write-Host "GCC animation asset check"
Write-Host "AssetsDir: $AssetsDir"

foreach ($path in @($GifPath, $IniPath, $BinPath)) {
    if (Test-Path -LiteralPath $path) {
        $item = Get-Item -LiteralPath $path
        $hash = (Get-FileHash -Algorithm MD5 -LiteralPath $path).Hash.ToUpperInvariant()
        Write-Host ("{0} length={1} lastWrite={2} md5={3}" -f $item.Name, $item.Length, $item.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss"), $hash)
    }
    else {
        Write-Host "MISSING: $path" -ForegroundColor Yellow
    }
}

if (Test-Path -LiteralPath $IniPath) {
    Write-Host ""
    Write-Host "animation.ini:"
    Get-Content -LiteralPath $IniPath
}

if (!(Test-Path -LiteralPath $BinPath)) {
    exit 1
}

$bytes = [IO.File]::ReadAllBytes($BinPath)
if ($bytes.Length -lt 2) {
    throw "animation.bin too short"
}

$count = Read-U16LE $bytes 0
Write-Host ""
Write-Host "animation.bin parsed:"
Write-Host "length=$($bytes.Length)"
Write-Host "frame_count=$count"
Write-Host "natural_chunk_mode_expected=$($(if ($bytes.Length -lt 20480) { '1 (<20480 bytes)' } else { '2 (>=20480 bytes)' }))"

$max = [Math]::Min($count, 12)
for ($i = 0; $i -lt $max; $i++) {
    $offset = 2 + (10 * $i)
    if ($offset + 10 -gt $bytes.Length) {
        Write-Host "frame[$i] header out of range" -ForegroundColor Red
        break
    }
    $end = Read-U32LE $bytes $offset
    $w = Read-U16LE $bytes ($offset + 4)
    $h = Read-U16LE $bytes ($offset + 6)
    $fmt = Read-U16LE $bytes ($offset + 8)
    Write-Host ("frame[{0}] end_offset={1} width={2} height={3} format={4}" -f $i, $end, $w, $h, $fmt)
}

if ($bytes.Length -lt 20480) {
    Write-Host ""
    Write-Host "OK: animation.bin is below 20480 bytes; GCC should naturally use F1 chunk_mode=1." -ForegroundColor Green
}
else {
    Write-Host ""
    Write-Host "WARNING: animation.bin is >= 20480 bytes; this still uses the large F1 chunk_mode=2 path." -ForegroundColor Yellow
}


