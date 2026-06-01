param(
    [Parameter(Mandatory=$true)]
    [string]$InputBin,
    [int]$Width = 320,
    [int]$Height = 170
)

$ErrorActionPreference = "Stop"

function Write-BmpRgb565Preview {
    param(
        [byte[]]$Bytes,
        [int]$Offset,
        [int]$Width,
        [int]$Height,
        [string]$OutPath
    )

    $pixelBytes = $Width * $Height * 2
    if ($Bytes.Length -lt ($Offset + $pixelBytes)) {
        throw "Not enough data for ${Width}x${Height} RGB565 at offset $Offset. len=$($Bytes.Length), need=$($Offset + $pixelBytes)"
    }

    $stride = (($Width * 3 + 3) -band -4)
    $imageSize = $stride * $Height
    $fileSize = 54 + $imageSize
    $bmp = New-Object byte[] $fileSize

    $bmp[0] = [byte][char]'B'
    $bmp[1] = [byte][char]'M'
    [BitConverter]::GetBytes([int]$fileSize).CopyTo($bmp, 2)
    [BitConverter]::GetBytes([int]54).CopyTo($bmp, 10)
    [BitConverter]::GetBytes([int]40).CopyTo($bmp, 14)
    [BitConverter]::GetBytes([int]$Width).CopyTo($bmp, 18)
    [BitConverter]::GetBytes([int]$Height).CopyTo($bmp, 22)
    [BitConverter]::GetBytes([int16]1).CopyTo($bmp, 26)
    [BitConverter]::GetBytes([int16]24).CopyTo($bmp, 28)
    [BitConverter]::GetBytes([int]$imageSize).CopyTo($bmp, 34)

    for ($y = 0; $y -lt $Height; $y++) {
        $srcY = $Height - 1 - $y
        for ($x = 0; $x -lt $Width; $x++) {
            $src = $Offset + (($srcY * $Width + $x) * 2)
            $v = [int]$Bytes[$src] -bor ([int]$Bytes[$src + 1] -shl 8)
            $r5 = ($v -shr 11) -band 0x1F
            $g6 = ($v -shr 5) -band 0x3F
            $b5 = $v -band 0x1F
            $r = [byte](($r5 * 255 + 15) / 31)
            $g = [byte](($g6 * 255 + 31) / 63)
            $b = [byte](($b5 * 255 + 15) / 31)
            $dst = 54 + ($y * $stride) + ($x * 3)
            $bmp[$dst] = $b
            $bmp[$dst + 1] = $g
            $bmp[$dst + 2] = $r
        }
    }

    [IO.File]::WriteAllBytes($OutPath, $bmp)
}

$bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $InputBin))
$base = [IO.Path]::Combine(
    [IO.Path]::GetDirectoryName((Resolve-Path -LiteralPath $InputBin)),
    [IO.Path]::GetFileNameWithoutExtension($InputBin)
)

Write-BmpRgb565Preview -Bytes $bytes -Offset 0 -Width $Width -Height $Height -OutPath ($base + "_offset0_320x170_rgb565.bmp")
if ($bytes.Length -ge (($Width * $Height * 2) + 12)) {
    Write-BmpRgb565Preview -Bytes $bytes -Offset 12 -Width $Width -Height $Height -OutPath ($base + "_offset12_320x170_rgb565.bmp")
}

Write-Host "Wrote previews next to $InputBin"
