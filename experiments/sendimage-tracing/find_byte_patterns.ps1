param(
    [string[]]$Roots = @(
        "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA",
        "C:\Program Files\GIGABYTE\Control Center\Lib\Update_Center"
    ),
    [string[]]$Files = @()
)

$patterns = [ordered]@{
    "CB55AC38" = [byte[]](0xCB,0x55,0xAC,0x38)
    "F1CB55AC38" = [byte[]](0xF1,0xCB,0x55,0xAC,0x38)
    "F2CB55AC38" = [byte[]](0xF2,0xCB,0x55,0xAC,0x38)
    "AACB55AC38" = [byte[]](0xAA,0xCB,0x55,0xAC,0x38)
    "EACB55AC38" = [byte[]](0xEA,0xCB,0x55,0xAC,0x38)
    "EDCB55AC38" = [byte[]](0xED,0xCB,0x55,0xAC,0x38)
    "01300000_BE" = [byte[]](0x01,0x30,0x00,0x00)
    "01320000_BE" = [byte[]](0x01,0x32,0x00,0x00)
    "01F25800_BE" = [byte[]](0x01,0xF2,0x58,0x00)
    "01F00000_BE" = [byte[]](0x01,0xF0,0x00,0x00)
}

function Find-Pattern([byte[]]$data, [byte[]]$pat) {
    $hits = New-Object System.Collections.Generic.List[int]
    $i = 0
    while ($i -le $data.Length - $pat.Length) {
        $i = [Array]::IndexOf($data, $pat[0], $i)
        if ($i -lt 0 -or $i -gt $data.Length - $pat.Length) { break }
        $ok = $true
        for ($j = 0; $j -lt $pat.Length; $j++) {
            if ($data[$i + $j] -ne $pat[$j]) { $ok = $false; break }
        }
        if ($ok) { $hits.Add($i) }
        $i++
    }
    return $hits
}

$targets = @()
if ($Files.Count -gt 0) {
    $targets = $Files | ForEach-Object { Get-Item -LiteralPath $_ -ErrorAction Stop }
} else {
    $targets = foreach ($root in $Roots) {
        Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in ".dll",".exe" }
    }
}

$targets | ForEach-Object {
            $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
            foreach ($kv in $patterns.GetEnumerator()) {
                $hits = Find-Pattern $bytes $kv.Value
                if ($hits.Count -gt 0) {
                    $sample = ($hits | Select-Object -First 12 | ForEach-Object { "0x{0:X}" -f $_ }) -join ","
                    [pscustomobject]@{
                        File = $_.FullName
                        Pattern = $kv.Key
                        Count = $hits.Count
                        Offsets = $sample
                    }
                }
            }
}
