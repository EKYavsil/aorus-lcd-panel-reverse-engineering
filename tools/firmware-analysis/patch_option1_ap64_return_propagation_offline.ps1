Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$BaseDir = "<local-gif-investigation-dir>"
$OutDir = Join-Path $BaseDir "offline_ap_patch_option1_return_propagation"
$SourceFiles = @(
    "<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_firmware_package_extracts\F14_AP_carve_26340.bin",
    "<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_firmware_package_extracts\F14_AP_carve_84673.bin"
)

$ExpectedSourceSha256 = "DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C"
$PatchOffset = 0xA534
$ExpectedOld = [byte[]](0x01, 0x20)
$PatchNew = [byte[]](0x00, 0xBF)

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$ManifestPath = Join-Path $OutDir "option1_patch_manifest.txt"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# AP option 1 offline patch manifest")
$lines.Add("created=$(Get-Date -Format o)")
$lines.Add("patch=FUN_0000B4D0 return propagation")
$lines.Add("ap_address=0x0000B534")
$lines.Add("file_offset=0x0000A534")
$lines.Add("old_bytes=01 20")
$lines.Add("new_bytes=00 BF")
$lines.Add("")

foreach ($src in $SourceFiles) {
    if (!(Test-Path -LiteralPath $src)) {
        throw "Source file missing: $src"
    }

    $srcHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash
    if ($srcHash -ne $ExpectedSourceSha256) {
        throw "Unexpected source SHA256 for $src : $srcHash"
    }

    $name = [IO.Path]::GetFileNameWithoutExtension($src)
    $dst = Join-Path $OutDir ($name + "_option1_return_propagation.bin")
    Copy-Item -LiteralPath $src -Destination $dst -Force

    $bytes = [IO.File]::ReadAllBytes($dst)
    if ($bytes.Length -le ($PatchOffset + 1)) {
        throw "Patch offset is outside file: $dst"
    }
    if ($bytes[$PatchOffset] -ne $ExpectedOld[0] -or $bytes[$PatchOffset + 1] -ne $ExpectedOld[1]) {
        throw ("Unexpected original bytes in {0} at 0x{1:X}: {2:X2} {3:X2}" -f $dst, $PatchOffset, $bytes[$PatchOffset], $bytes[$PatchOffset + 1])
    }

    $bytes[$PatchOffset] = $PatchNew[0]
    $bytes[$PatchOffset + 1] = $PatchNew[1]
    [IO.File]::WriteAllBytes($dst, $bytes)

    $verify = [IO.File]::ReadAllBytes($dst)
    if ($verify[$PatchOffset] -ne $PatchNew[0] -or $verify[$PatchOffset + 1] -ne $PatchNew[1]) {
        throw "Patched byte verification failed: $dst"
    }

    $dstHash = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash
    $lines.Add("source=$src")
    $lines.Add("source_sha256=$srcHash")
    $lines.Add("patched=$dst")
    $lines.Add("patched_sha256=$dstHash")
    $lines.Add(("patched_bytes_at_0x{0:X}=00 BF" -f $PatchOffset))
    $lines.Add("")
}

[IO.File]::WriteAllLines($ManifestPath, $lines)
Write-Host "Offline AP option 1 patch artifacts written to:"
Write-Host "  $OutDir"
Write-Host "Manifest:"
Write-Host "  $ManifestPath"


