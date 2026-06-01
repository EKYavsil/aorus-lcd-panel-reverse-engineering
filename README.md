# AORUS LCD Panel Reverse Engineering

Reverse engineering, firmware analysis, and a minimal reproducible patch builder for a GIGABYTE AORUS RTX 5080 ICE LCD panel static custom image corruption issue.

## Practical Summary

This repository includes a patch builder that can generate a working patched `ucVga.dll` from a clean local GIGABYTE Control Center DLL. In local testing, the generated DLL fixed the static custom-image corruption symptom where the lower portion of the LCD image stayed black or stale.

For convenience, a prebuilt `BuildFinalStaticSectorPatch.exe` is included in the repository root. Users who trust the provided binary can use it directly. Users who prefer full reproducibility can build the same tool from the public source code with the .NET 8 SDK.

The practical workaround is working and documented, but the deeper panel-side reason for the failure is still under investigation. The current evidence points to a problematic AP firmware erase/write path selected by one metadata byte in the static image upload header. The custom GIF corruption path is also still under investigation and is intentionally not patched by the current builder.

No proprietary vendor DLLs, patched vendor DLLs, firmware images, extracted update packages, raw traces, or machine-specific dumps are included in this repository.

## Executive Summary

The LCD panel accepted custom static image uploads, and host-side APIs reported success, but the panel often refreshed only the upper portion of the image. The lower region stayed black or stale, eventually producing a repeatable "lower 40 percent black" failure.

The working local fix changes one metadata byte in the static image `F1` upload header:

```text
F1[0x11]: 0x02 -> 0x01
```

This changes the AP firmware erase/write path used for static image uploads at the custom static slot:

```text
0x01300000
```

The fix does not modify image payloads, destination addresses, GIF handling, display modes, firmware images, or raw I2C commands.

## Technical Finding

Original static image upload header:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Working static image upload header:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

The failure boundary aligned with the second 64 KB flash erase block around:

```text
0x01310000
```

Analysis indicates that `0x02` selects a problematic block-erase path for this static image workflow, while `0x01` selects the 4 KB sector-erase path. With sector erase, the full 320x170 RGB565 static frame refreshed correctly.

## Investigation Highlights

- Proved the GPU and LCD panel were reachable through the existing GIGABYTE/NVAPI I2C path.
- Verified that custom static image payload data was generated correctly before being sent to the panel.
- Ruled out Windows-side cache, GCC profile state, display mode, overlay, loop mode, and firmware reinstall as primary causes.
- Reconstructed relevant LCD protocol commands including `F1`, `F2`, `E1`, `E5`, `E7`, `F3`, and `AA`.
- Performed offline AP firmware analysis to connect the `F1` header byte with flash erase behavior.
- Built a static-only patch guarded by image type, local destination, packet destination, packet magic, and expected original byte value.
- Preserved the unresolved GIF issue as a separate path instead of applying an unsafe broad patch.

## Fix Scope

The final patch is intentionally narrow:

```text
if nType == 1
and SendImage local destination == 0x01300000
and F1 packet magic == F1 CB 55 AC 38
and F1 packet destination bytes == 01 30 00 00
and F1[0x11] == 0x02:
    F1[0x11] = 0x01
```

No proprietary vendor DLLs or patched vendor DLLs are included in this repository. The patch builder source is provided so the modification can be reproduced from a locally obtained clean `ucVga.dll`.

## Repository Layout

```text
BuildFinalStaticSectorPatch.exe      Optional prebuilt patcher executable for convenience
build_final_static_sector_patch.cs   Root-level Mono.Cecil patch builder source
StaticSectorPatcher.csproj           Root-level .NET project for building from source
PATCHER.md                           Patch builder usage and guard documentation
docs/analysis/                       Technical reverse-engineering notes and protocol analysis
docs/evidence/                       Test evidence and final success notes
docs/research-log/                   Curated research history and artifact map
experiments/                         Historical traces and diagnostic notes
tools/firmware_*                     Offline firmware updater and IAP analysis helpers
tools/ghidra/                        Ghidra scripts for AP firmware and native binary analysis
tools/python/                        GIF-side experiment helpers; GIF bug remains unresolved
```

Start here:

- [Patch builder usage](PATCHER.md)
- [Patch builder source](build_final_static_sector_patch.cs)
- [F1 header to erase mode](docs/analysis/f1-header-to-erase-mode.md)
- [Static sector erase success notes](docs/evidence/static-sector-erase-success.md)
- [GIF erase-mode hypothesis](docs/analysis/gif-erase-mode-hypothesis.md)
- [English investigation journal](JOURNAL-en.md)
- [Turkish investigation journal](JOURNAL-tr.md)

## Easiest Usage: Prebuilt Root Executable

The easiest path is to use the prebuilt executable in the repository root:

```text
BuildFinalStaticSectorPatch.exe
```

The original GIGABYTE DLL is usually located here:

```text
C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll
```

Quick use:

1. Download or clone this repository.
2. Copy a clean local `ucVga.dll` from your own GIGABYTE Control Center installation into the same folder as `BuildFinalStaticSectorPatch.exe`.
   - Typical source path: `C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll`
3. Run `BuildFinalStaticSectorPatch.exe`.
4. The tool creates `ucVga.static-sector.dll` in the same folder.
5. Back up your original vendor `ucVga.dll` before using the generated DLL as a replacement.

The included executable is provided for convenience. It does **not** contain `ucVga.dll`, patched vendor DLLs, firmware images, GIGABYTE installers, raw traces, or proprietary vendor binaries.

This repository does not automate installation or replacement of live GIGABYTE files.

## Build From Source

If you do not trust the prebuilt binary, build the patcher yourself from the public source code with the .NET 8 SDK:

```powershell
dotnet build .\StaticSectorPatcher.csproj -c Release
```

Generate a patched DLL from a clean local vendor DLL:

```powershell
.\bin\Release\net8.0\BuildFinalStaticSectorPatch.exe .\ucVga.clean.dll .\ucVga.static-sector.dll "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA"
```

The patch builder only writes a new output DLL. Installation or replacement of a live vendor DLL is intentionally not automated in this public repository.

## Current Status

```text
Static custom image corruption: fixed locally and documented
Underlying AP/panel-side failure mechanism: still under investigation
Custom GIF corruption: unresolved, intentionally not patched here
```

## Use Of AI Assistance

Parts of this investigation, documentation cleanup, code review, and reverse-engineering analysis workflow were assisted by OpenAI Codex/ChatGPT. The technical claims in this repository are based on the included local traces, offline firmware analysis notes, patch-builder source, and user-observed test results rather than on AI output alone.

## Engineering Notes

This project is intentionally conservative:

- It does not redistribute proprietary GIGABYTE binaries.
- It does not include firmware blobs, extracted update packages, raw traces, or machine-specific scan output.
- It avoids broad protocol changes and raw I2C command injection.
- It keeps the working static-image fix separate from the unresolved GIF path.
- It licenses only the original source code and documentation in this repository, not vendor software or firmware.

## Disclaimer

This is an independent reverse-engineering investigation and local workaround. It is not affiliated with, endorsed by, or supported by GIGABYTE. Use at your own risk and do not redistribute proprietary vendor binaries.
