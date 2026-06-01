param(
    [string] $OutDir = ".\windows_lcd_cache_investigation_output",
    [string] $Root = "C:\",
    [int] $RecentDays = 30
)

$ErrorActionPreference = 'SilentlyContinue'
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Log = Join-Path $OutDir 'windows_lcd_cache_hunt_run.log'

function Log($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $Log -Value $line -Encoding UTF8
    Write-Output $line
}

function Safe-Hash($path) {
    try { return (Get-FileHash -LiteralPath $path -Algorithm SHA256 -ErrorAction Stop).Hash } catch { return $null }
}

function Get-ImageInfo($path) {
    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue | Out-Null
        $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $img = [System.Drawing.Image]::FromStream($fs, $false, $false)
            try {
                return [pscustomobject]@{
                    Width = $img.Width
                    Height = $img.Height
                    PixelFormat = $img.PixelFormat.ToString()
                    FrameCount = try { $img.GetFrameCount([System.Drawing.Imaging.FrameDimension]::Time) } catch { 1 }
                }
            } finally {
                $img.Dispose()
            }
        } finally {
            $fs.Dispose()
        }
    } catch {
        return $null
    }
}

$root = $Root
$since = (Get-Date).AddDays(-1 * [Math]::Abs($RecentDays))

$namePathRegex = '(?i)(gigabyte|aorus|gcc|gbt|gvprofile|lcd|panel|vga|ucvga|gvdisplay|gvlcd|418c|custom|tpl|image|gif|display|screen|cache)'
$gigabytePathRegex = '(?i)(gigabyte|aorus|gcc|gbt|gvprofile|ucvga|gvdisplay|gvlcd|418c)'
$imageExtRegex = '(?i)\.(bmp|png|jpg|jpeg|gif|webp)$'
$dataExtRegex = '(?i)\.(bin|dat|db|sqlite|sqlite3|json|xml|ini|config|cfg|txt|log|cache|tmp)$'

Log "Starting full read-only C: inventory. This may take a while."

$all = Get-ChildItem -LiteralPath $root -Recurse -Force -File -ErrorAction SilentlyContinue |
    Select-Object FullName, Name, Extension, Length, LastWriteTime, CreationTime

Log ("Total files enumerated: {0}" -f (($all | Measure-Object).Count))

$pathNameHits = $all | Where-Object {
    $_.FullName -match $namePathRegex -or $_.Name -match $namePathRegex
} | Sort-Object FullName -Unique

$pathNameHits | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'windows_path_name_cache_candidates.csv')
Log ("Path/name candidates: {0}" -f (($pathNameHits | Measure-Object).Count))

$recentImageHits = $all | Where-Object {
    $_.Extension -match $imageExtRegex -and ($_.LastWriteTime -ge $since -or $_.CreationTime -ge $since)
} | Sort-Object FullName -Unique

$recentImageHits | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'windows_recent_image_candidates.csv')
Log ("Recent image candidates: {0}" -f (($recentImageHits | Measure-Object).Count))

$likelyCacheFiles = $all | Where-Object {
    ($_.FullName -match $gigabytePathRegex -and ($_.Extension -match $imageExtRegex -or $_.Extension -match $dataExtRegex)) -or
    ($_.Name -match $namePathRegex -and ($_.Extension -match $imageExtRegex -or $_.Extension -match $dataExtRegex))
} | Sort-Object FullName -Unique

$likelyCacheFiles | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'windows_likely_lcd_cache_files.csv')
Log ("Likely LCD/GIGABYTE cache files: {0}" -f (($likelyCacheFiles | Measure-Object).Count))

Log "Inspecting image dimensions for recent/path-matched image files."
$imagesToInspect = @()
$imagesToInspect += $recentImageHits
$imagesToInspect += ($pathNameHits | Where-Object { $_.Extension -match $imageExtRegex })
$imagesToInspect = $imagesToInspect | Sort-Object FullName -Unique

$imageRows = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($f in $imagesToInspect) {
    $i++
    if (($i % 500) -eq 0) { Log ("Image inspect progress: {0}/{1}" -f $i, (($imagesToInspect | Measure-Object).Count)) }
    $info = Get-ImageInfo $f.FullName
    if ($info) {
        $isPanelLike = (
            ($info.Width -eq 320 -and ($info.Height -eq 170 -or $info.Height -eq 172 -or $info.Height -eq 240 -or $info.Height -eq 320)) -or
            ($info.Width -eq 480 -and $info.Height -eq 480) -or
            ($info.Width -eq 240 -and $info.Height -eq 320)
        )
        $isInteresting = $isPanelLike -or ($f.FullName -match $namePathRegex)
        if ($isInteresting) {
            $imageRows.Add([pscustomobject]@{
                FullName = $f.FullName
                Length = $f.Length
                LastWriteTime = $f.LastWriteTime
                CreationTime = $f.CreationTime
                Width = $info.Width
                Height = $info.Height
                PixelFormat = $info.PixelFormat
                FrameCount = $info.FrameCount
                PanelLike = $isPanelLike
                SHA256 = if ($f.Length -le 250MB) { Safe-Hash $f.FullName } else { $null }
            })
        }
    }
}

$imageRows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $OutDir 'windows_image_dimension_hits.csv')
Log ("Interesting image dimension hits: {0}" -f $imageRows.Count)

Log "Searching small text/config files for LCD/profile/cache strings."
$contentPatterns = @(
    'LcdSetting','ImgConfig','GifConfig','ListCarousel','FontText','418C','GV-N5080',
    'AORUS','GIGABYTE','Gigabyte','GvProfile','SetImageTpl','GetImageTpl',
    'SetDisplay','GetDisplay','SetMode','Save','SendImage','ucVga','GvLcd','GvDisplay',
    'CB 55 AC 38','nType','imgPos','dataPos'
)

$textSearchFiles = $all | Where-Object {
    $_.Length -gt 0 -and $_.Length -le 20MB -and
    (
        $_.FullName -match $gigabytePathRegex -or
        $_.Extension -match '(?i)\.(json|xml|ini|config|cfg|txt|log|dat|db|sqlite|sqlite3|cache)$' -or
        $_.Name -match $namePathRegex
    )
} | Sort-Object FullName -Unique

$textHitsOut = Join-Path $OutDir 'windows_lcd_text_content_hits.csv'
$textHits = New-Object System.Collections.Generic.List[object]
$t = 0
foreach ($f in $textSearchFiles) {
    $t++
    if (($t % 1000) -eq 0) { Log ("Text search progress: {0}/{1}" -f $t, (($textSearchFiles | Measure-Object).Count)) }
    try {
        $matches = Select-String -LiteralPath $f.FullName -Pattern $contentPatterns -SimpleMatch -CaseSensitive:$false -ErrorAction SilentlyContinue | Select-Object -First 20
        foreach ($m in $matches) {
            $textHits.Add([pscustomobject]@{
                FullName = $f.FullName
                Length = $f.Length
                LastWriteTime = $f.LastWriteTime
                Pattern = $m.Pattern
                LineNumber = $m.LineNumber
                Line = (($m.Line -replace "`0",'') -replace '\s+',' ').Trim()
            })
        }
    } catch {}
}

$textHits | Export-Csv -NoTypeInformation -Encoding UTF8 $textHitsOut
Log ("Text/content hits: {0}" -f $textHits.Count)

Log "Searching registry hives for narrow GIGABYTE/LCD strings."
$regOut = Join-Path $OutDir 'windows_registry_gigabyte_lcd_hits.txt'
Remove-Item -LiteralPath $regOut -Force -ErrorAction SilentlyContinue
foreach ($hive in @('HKCU','HKLM')) {
    foreach ($needle in @('LcdSetting','ImgConfig','GvProfile','Gigabyte','GIGABYTE','AORUS','418C')) {
        Log "Registry query $hive /f $needle"
        try {
            & reg.exe query $hive /f $needle /s 2>$null | Add-Content -LiteralPath $regOut -Encoding UTF8
        } catch {}
    }
}

Log "Building markdown summary."
$report = Join-Path $OutDir 'Windows_LCD_Cache_Hunt_Report.md'
$topLikely = $likelyCacheFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 80
$topImages = $imageRows | Sort-Object @{Expression='PanelLike';Descending=$true}, @{Expression='LastWriteTime';Descending=$true} | Select-Object -First 80
$topText = $textHits | Where-Object { $_.FullName -match '(?i)(gigabyte|aorus|gcc|gvprofile|control center|418c|lcdsetting)' } | Select-Object -First 120

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# Windows LCD Cache Hunt Report")
$md.Add("")
$md.Add(("Date: {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')))
$md.Add("")
$md.Add("Scope: read-only scan across `C:\` for possible GIGABYTE/AORUS/GCC LCD image payloads, profile state, caches, and panel-sized image files.")
$md.Add("")
$md.Add("## Output Files")
$md.Add("")
$md.Add('- `windows_path_name_cache_candidates.csv`')
$md.Add('- `windows_recent_image_candidates.csv`')
$md.Add('- `windows_likely_lcd_cache_files.csv`')
$md.Add('- `windows_image_dimension_hits.csv`')
$md.Add('- `windows_lcd_text_content_hits.csv`')
$md.Add('- `windows_registry_gigabyte_lcd_hits.txt`')
$md.Add('- `windows_lcd_cache_hunt_run.log`')
$md.Add("")
$md.Add("## Counts")
$md.Add("")
$md.Add(('- Total files enumerated: `{0}`' -f (($all | Measure-Object).Count)))
$md.Add(('- Path/name candidates: `{0}`' -f (($pathNameHits | Measure-Object).Count)))
$md.Add(('- Recent image candidates: `{0}`' -f (($recentImageHits | Measure-Object).Count)))
$md.Add(('- Likely LCD/GIGABYTE cache files: `{0}`' -f (($likelyCacheFiles | Measure-Object).Count)))
$md.Add(('- Interesting image dimension hits: `{0}`' -f $imageRows.Count))
$md.Add(('- Text/content hits: `{0}`' -f $textHits.Count))
$md.Add("")
$md.Add("## Top Likely Cache/State Files")
$md.Add("")
foreach ($x in $topLikely) {
    $md.Add(('- `{0}` | {1} bytes | {2}' -f $x.FullName, $x.Length, $x.LastWriteTime))
}
$md.Add("")
$md.Add("## Top Image Dimension Hits")
$md.Add("")
foreach ($x in $topImages) {
    $md.Add(('- `{0}` | {1}x{2} | frames={3} | panelLike={4} | {5} bytes | {6}' -f $x.FullName, $x.Width, $x.Height, $x.FrameCount, $x.PanelLike, $x.Length, $x.LastWriteTime))
}
$md.Add("")
$md.Add("## Top GIGABYTE/LCD Text Hits")
$md.Add("")
foreach ($x in $topText) {
    $line = if ($x.Line.Length -gt 180) { $x.Line.Substring(0,180) + '...' } else { $x.Line }
    $md.Add(('- `{0}` line {1} pattern `{2}`: {3}' -f $x.FullName, $x.LineNumber, $x.Pattern, $line))
}
$md.Add("")
$md.Add("## Initial Interpretation")
$md.Add("")
$md.Add("This report is generated mechanically. Review the CSVs before drawing final conclusions. The main thing to look for is whether any panel-sized image/GIF/payload exists outside known test artifacts and whether any GCC profile state points the panel back to a custom slot.")

Set-Content -LiteralPath $report -Value $md -Encoding UTF8
Log "Done."
