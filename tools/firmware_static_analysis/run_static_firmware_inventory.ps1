param(
    [string] $OutDir = ".\firmware_static_analysis_output",
    [string] $FirmwareDownloadDir = ".\firmware",
    [string] $SevenZip = "C:\Program Files\7-Zip\7z.exe",
    [string] $TopLevelScanRoot = "."
)

$ErrorActionPreference = "Stop"
$ExtractDir = Join-Path $OutDir "extracted"
$InventoryMd = Join-Path $OutDir "Firmware_File_Inventory.md"
$StringHitsMd = Join-Path $OutDir "Firmware_String_Hits.md"
$PatternHitsMd = Join-Path $OutDir "Firmware_Opcode_Pattern_Hits.md"
$AnalysisMd = Join-Path $OutDir "IAP_Erase_Analysis_Report.md"
$RunLog = Join-Path $OutDir "run_static_firmware_inventory.log"

$CandidateRoots = @(
    $FirmwareDownloadDir,
    "C:\Program Files (x86)\GIGABYTE\AORUS LCD Panel Setting",
    "C:\Program Files (x86)\GIGABYTE\AORUS LCD Panel Setting\Updater",
    "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA",
    "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\Service",
    "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\GvDll",
    "C:\Program Files\GIGABYTE\Control Center\Lib\Update_Center"
)

$TopLevelDesktop = [System.IO.Path]::GetFullPath($TopLevelScanRoot)

$NameRegex = '(?i)(FWUpgrade|Updater|Gv_lcd_fw_update|AorusLcdService|ucVga|GvDisplayA?|GvIntelI2C|RLE_Compress|GvFvSDK|Flash|FBIOS|lcd|firmware|iap|HT\d+|5080|AORUSM|ICE|\.bin$|\.rom$|\.dat$|\.fw$|\.hex$|\.pack$|\.zip$|\.7z$|\.rar$)'
$FirmwarePackageRegex = '(?i)(Gv_lcd_fw_update|LCD_F|AORUSM_ICE|FWUpgrade|Updater).*\.(exe|zip|7z|rar|pack)$'
$ScanExtRegex = '(?i)(\.exe|\.dll|\.bin|\.rom|\.dat|\.fw|\.hex|\.pack|\.ini|\.txt|\.cfg|\.json|\.text|version\.txt|7z_list\.txt)$'
$ExactInterestingNames = @(
    "FWUpgrade.exe", "Updater.exe",
    "AorusLcdService.exe", "ucVga.dll",
    "GvDisplay.dll", "GvDisplayA.dll", "GvIntelI2C.dll",
    "RLE_Compress.dll", "GvFvSDK.dll", "GvFvSDKCSharp.exe",
    "Flash.dll", "FBIOS.dll", "FlashBios.exe",
    "Gv_lcd_fw_update_N50_ICE_F1.2.exe",
    "GV-N5080AORUSM_ICE-16GD_LCD_F1.3.exe",
    "GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe"
)

$Keywords = @(
    "I2CAPChangeToIAP", "I2CIAPChangeToErase12ByteMode", "ChangeToIAP",
    "Erase12Byte", "Erase12ByteMode", "IAP", "Flash fail", "FWUpgrade",
    "Updater", "bootloader", "erase", "reset", "factory", "wipe", "format",
    "storage", "cache", "slot", "bank", "firmware", "flash", "init",
    "initialize", "reinit", "GvWriteI2C", "GvReadI2C", "GvLcd", "GvLcdEx",
    "I2CApi", "I2CApiEx", "I2CApi4LcdEx", "0x61", "0x76", "0xC2", "0xEC",
    "CB 55 AC 38", "D6", "DE", "E7", "E5", "E1", "F3", "F2", "F1", "AA"
)

$Patterns = [ordered]@{
    "CB_55_AC_38"    = "CB 55 AC 38"
    "D6_CB_55_AC_38" = "D6 CB 55 AC 38"
    "DE_CB_55_AC_38" = "DE CB 55 AC 38"
    "E7_CB_55_AC_38" = "E7 CB 55 AC 38"
    "E5_CB_55_AC_38" = "E5 CB 55 AC 38"
    "E1_CB_55_AC_38" = "E1 CB 55 AC 38"
    "F3_CB_55_AC_38" = "F3 CB 55 AC 38"
    "F2_CB_55_AC_38" = "F2 CB 55 AC 38"
    "F1_CB_55_AC_38" = "F1 CB 55 AC 38"
    "AA_CB_55_AC_38" = "AA CB 55 AC 38"
}

function Log([string]$Message) {
    $line = (Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff") + " " + $Message
    Add-Content -LiteralPath $RunLog -Value $line -Encoding UTF8
    Write-Host $line
}

function Escape-Md([string]$Text) {
    if ($null -eq $Text) { return "" }
    return ($Text -replace '\|','\|' -replace "`r"," " -replace "`n"," ")
}

function Get-Sha256([string]$Path) {
    try { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant() } catch { "ERROR: $($_.Exception.Message)" }
}

function Get-VersionSummary([string]$Path) {
    try {
        $v = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
        (@(
            if ($v.FileVersion) { "FileVersion=$($v.FileVersion)" }
            if ($v.ProductVersion) { "ProductVersion=$($v.ProductVersion)" }
            if ($v.CompanyName) { "Company=$($v.CompanyName)" }
            if ($v.ProductName) { "Product=$($v.ProductName)" }
            if ($v.FileDescription) { "Description=$($v.FileDescription)" }
        ) -join "; ")
    } catch { "ERROR: $($_.Exception.Message)" }
}

function Get-SignatureSummary([string]$Path) {
    try {
        $sig = Get-AuthenticodeSignature -LiteralPath $Path
        $subject = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { "" }
        "Status=$($sig.Status); Subject=$subject"
    } catch { "ERROR: $($_.Exception.Message)" }
}

function Hex-ToBytes([string]$Hex) {
    [byte[]]@($Hex -split '\s+' | Where-Object { $_ } | ForEach-Object { [Convert]::ToByte($_, 16) })
}

function Find-Bytes([byte[]]$Data, [byte[]]$Needle) {
    $hits = New-Object System.Collections.Generic.List[int]
    if ($Needle.Length -eq 0 -or $Data.Length -lt $Needle.Length) { return $hits }
    for ($i = 0; $i -le $Data.Length - $Needle.Length; $i++) {
        $ok = $true
        for ($j = 0; $j -lt $Needle.Length; $j++) {
            if ($Data[$i + $j] -ne $Needle[$j]) { $ok = $false; break }
        }
        if ($ok) { $hits.Add($i) }
    }
    $hits
}

function Extract-Strings([byte[]]$Data, [int]$MinLen) {
    $list = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $Data) {
        if ($b -ge 32 -and $b -le 126) { [void]$sb.Append([char]$b) }
        else { if ($sb.Length -ge $MinLen) { $list.Add($sb.ToString()) }; $null = $sb.Clear() }
    }
    if ($sb.Length -ge $MinLen) { $list.Add($sb.ToString()) }
    $null = $sb.Clear()
    for ($i = 0; $i -lt $Data.Length - 1; $i += 2) {
        $code = [BitConverter]::ToUInt16($Data, $i)
        if ($code -ge 32 -and $code -le 126) { [void]$sb.Append([char]$code) }
        else { if ($sb.Length -ge $MinLen) { $list.Add($sb.ToString()) }; $null = $sb.Clear() }
    }
    if ($sb.Length -ge $MinLen) { $list.Add($sb.ToString()) }
    $list
}

function Add-File($List, [string]$Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $List.Add((Get-Item -LiteralPath $Path))
    }
}

function Collect-Candidates {
    $files = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    foreach ($root in $CandidateRoots) {
        if (!(Test-Path -LiteralPath $root)) { continue }
        Log "Scanning candidate root: $root"
        Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $ExactInterestingNames -contains $_.Name -or $_.Name -match $FirmwarePackageRegex -or $_.Name -match '(?i)\.(bin|rom|dat|fw|hex|pack)$' } |
            ForEach-Object { $files.Add($_) }
    }
    if (Test-Path -LiteralPath $TopLevelDesktop) {
        Log "Scanning Desktop top-level firmware-like files only"
        Get-ChildItem -LiteralPath $TopLevelDesktop -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match $FirmwarePackageRegex -or $_.Name -match '(?i)(firmware|LCD|AORUSM|5080|GIGABYTE).*\.(exe|zip|7z|rar|pack)$' } |
            ForEach-Object { $files.Add($_) }
    }
    @($files | Sort-Object FullName -Unique)
}

function Extract-FirmwarePackages([System.IO.FileInfo[]]$Files) {
    if (!(Test-Path -LiteralPath $SevenZip)) {
        Log "7z not found; extraction skipped."
        return
    }
    if (Test-Path -LiteralPath $ExtractDir) {
        Log "Removing previous analysis-only extraction folder: $ExtractDir"
        Remove-Item -LiteralPath $ExtractDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $ExtractDir -Force | Out-Null
    foreach ($f in $Files) {
        if ($f.Name -notmatch $FirmwarePackageRegex) { continue }
        $safe = ($f.BaseName -replace '[^\w\.-]+','_')
        $dest = Join-Path $ExtractDir $safe
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        Log "7z listing firmware package: $($f.FullName)"
        & $SevenZip l $f.FullName | Out-File -LiteralPath (Join-Path $dest "7z_list.txt") -Encoding UTF8
        Log "7z extracting firmware package to analysis folder only: $dest"
        & $SevenZip x -y "-o$dest" $f.FullName | Out-File -LiteralPath (Join-Path $dest "7z_extract.txt") -Encoding UTF8
    }
}

function Write-Inventory($Files) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("# Firmware File Inventory")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("| Path | Size | Last Write | SHA256 | Signature | Version |")
    [void]$sb.AppendLine("|---|---:|---|---|---|---|")
    foreach ($f in $Files) {
        Log "Inventory: $($f.FullName)"
        [void]$sb.AppendLine("| $(Escape-Md $f.FullName) | $($f.Length) | $($f.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")) | $(Get-Sha256 $f.FullName) | $(Escape-Md (Get-SignatureSummary $f.FullName)) | $(Escape-Md (Get-VersionSummary $f.FullName)) |")
    }
    Set-Content -LiteralPath $InventoryMd -Value $sb.ToString() -Encoding UTF8
}

function Run-Scans($Files) {
    $stringHitRows = New-Object System.Collections.Generic.List[object]
    $patternHitRows = New-Object System.Collections.Generic.List[object]
    foreach ($f in $Files) {
        if ($f.Length -gt 80MB -or $f.Name -notmatch $ScanExtRegex) { continue }
        Log "Scanning strings/patterns: $($f.FullName)"
        try { $data = [System.IO.File]::ReadAllBytes($f.FullName) } catch { Log "Read failed: $($f.FullName)"; continue }
        foreach ($kv in $Patterns.GetEnumerator()) {
            $hits = @(Find-Bytes $data (Hex-ToBytes $kv.Value))
            if ($hits.Count -gt 0) {
                $patternHitRows.Add([pscustomobject]@{
                    Path = $f.FullName
                    Pattern = $kv.Key
                    Count = $hits.Count
                    FirstOffsets = ((@($hits | Select-Object -First 20 | ForEach-Object { "0x{0:X}" -f $_ }) -join ", "))
                })
            }
        }
        $strings = @(Extract-Strings $data 4)
        foreach ($kw in $Keywords) {
            $matches = @($strings | Where-Object { $_.IndexOf($kw, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | Select-Object -First 20)
            if ($matches.Count -gt 0) {
                $stringHitRows.Add([pscustomobject]@{
                    Path = $f.FullName
                    Keyword = $kw
                    CountShown = $matches.Count
                    Examples = (($matches | ForEach-Object { $_.Trim() }) -join " || ")
                })
            }
        }
    }

    $sh = New-Object System.Text.StringBuilder
    [void]$sh.AppendLine("# Firmware String Hits")
    [void]$sh.AppendLine("")
    [void]$sh.AppendLine("| Path | Keyword | Count Shown | Examples |")
    [void]$sh.AppendLine("|---|---|---:|---|")
    foreach ($r in ($stringHitRows | Sort-Object Path,Keyword)) {
        [void]$sh.AppendLine("| $(Escape-Md $r.Path) | $(Escape-Md $r.Keyword) | $($r.CountShown) | $(Escape-Md $r.Examples) |")
    }
    Set-Content -LiteralPath $StringHitsMd -Value $sh.ToString() -Encoding UTF8

    $ph = New-Object System.Text.StringBuilder
    [void]$ph.AppendLine("# Firmware Opcode Pattern Hits")
    [void]$ph.AppendLine("")
    [void]$ph.AppendLine("| Path | Pattern | Count | First Offsets |")
    [void]$ph.AppendLine("|---|---|---:|---|")
    foreach ($r in ($patternHitRows | Sort-Object Path,Pattern)) {
        [void]$ph.AppendLine("| $(Escape-Md $r.Path) | $($r.Pattern) | $($r.Count) | $($r.FirstOffsets) |")
    }
    Set-Content -LiteralPath $PatternHitsMd -Value $ph.ToString() -Encoding UTF8

    Write-Analysis $stringHitRows $patternHitRows
}

function Write-Analysis($StringRows, $PatternRows) {
    $high = @($StringRows | Where-Object { $_.Keyword -match '(?i)(I2CAPChangeToIAP|I2CIAPChangeToErase12ByteMode|ChangeToIAP|Erase12Byte|IAP|GvWriteI2C|GvReadI2C|I2CApi|bootloader|erase|flash|GvLcd)' })
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("# IAP Erase Analysis Report")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("Static-only analysis. No firmware updater was executed. No live I2C/GCC operation was performed.")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("## High Signal Hits")
    if ($high.Count -eq 0) {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("No high-signal IAP/erase strings were found.")
    } else {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("| Path | Keyword | Examples |")
        [void]$sb.AppendLine("|---|---|---|")
        foreach ($r in ($high | Sort-Object Path,Keyword)) {
            [void]$sb.AppendLine("| $(Escape-Md $r.Path) | $(Escape-Md $r.Keyword) | $(Escape-Md $r.Examples) |")
        }
    }
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("## LCD Opcode Pattern Hits")
    if ($PatternRows.Count -eq 0) {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("No `CB 55 AC 38` command patterns were found in scanned files.")
    } else {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("| Path | Pattern | Count | First Offsets |")
        [void]$sb.AppendLine("|---|---|---:|---|")
        foreach ($r in ($PatternRows | Sort-Object Path,Pattern)) {
            [void]$sb.AppendLine("| $(Escape-Md $r.Path) | $($r.Pattern) | $($r.Count) | $($r.FirstOffsets) |")
        }
    }
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("## Preliminary Decision")
    if (($StringRows | Where-Object { $_.Keyword -match '(?i)(I2CAPChangeToIAP|I2CIAPChangeToErase12ByteMode|Erase12Byte|ChangeToIAP)' }).Count -gt 0) {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("C) IAP/erase strings are present but raw packet/protocol details need manual disassembly.")
    } elseif ($PatternRows.Count -gt 0) {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("C) LCD protocol patterns are present, but a verified IAP/erase flow is not decoded by this first-pass scanner.")
    } else {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("D) Further disassembly is required; first-pass scan did not decode a decisive IAP/erase flow.")
    }
    Set-Content -LiteralPath $AnalysisMd -Value $sb.ToString() -Encoding UTF8
}

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
Remove-Item -LiteralPath $RunLog -ErrorAction SilentlyContinue
Log "Focused static firmware analysis started."

$candidates = @(Collect-Candidates)
Extract-FirmwarePackages $candidates
$extractedFiles = @()
    if (Test-Path -LiteralPath $ExtractDir) {
    $extractedFiles = @(Get-ChildItem -LiteralPath $ExtractDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -in @(".text", "version.txt", "7z_list.txt", "7z_extract.txt") -or
        $_.Name -match $NameRegex -or
        $_.FullName -match '(?i)(FWUpgrade|Updater|IAP|LCD|Gv_lcd|5080|AORUSM|ICE)'
    })
}
$all = @($candidates + $extractedFiles | Sort-Object FullName -Unique)
Log "Candidate file count: $($all.Count)"
Write-Inventory $all
Run-Scans $all
Log "Reports written."
