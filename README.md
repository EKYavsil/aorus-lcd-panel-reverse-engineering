# AORUS LCD Panel Reverse Engineering

Independent reverse engineering notes and a reproducible firmware patcher for the GIGABYTE AORUS RTX 5080 ICE LCD panel custom image/GIF corruption bug.

## Practical Summary

The panel-side AP firmware has a broken native 64 KB erase path. The failure presents as custom static images with a black/stale lower region and custom GIFs that upload but display incorrectly, freeze, or fall back into invalid panel state.

This repository contains a source-available repair tool that generates repaired `AP` and `AP1` firmware payloads from a locally obtained official GIGABYTE LCD firmware package.

The currently supported/tested input is the official GIGABYTE LCD firmware `1.4` package for the tested AORUS RTX 5080 ICE LCD panel. The tool intentionally validates the exact `AP`/`AP1` and updater DLL hashes before flashing; other firmware versions or card variants should be treated as unsupported until separately analyzed.

The tool does **not** include GIGABYTE firmware, DLLs, installers, extracted packages, raw traces, or proprietary binaries. It asks the user to select a locally extracted official GIGABYTE LCD firmware folder, validates it strictly, stages patched `AP`/`AP1`, and starts flashing only after the user presses `Start / Baslat`.

For non-technical users, the prebuilt Windows x64 repair executable is intended to be distributed as a GitHub Release asset. It is self-contained and does not require the Microsoft .NET Desktop Runtime. The executable is not committed to this repository because the runtime-free build is large. Users who want full reproducibility can build the same executable from `src/Program.cs` with the .NET 8 SDK.

## Root Cause

The final local fix repairs the AP firmware's own 64 KB erase behavior instead of bypassing it from the Windows upload path.

Two AP firmware sites were identified:

```text
FUN_0000BA44: SPI status poll helper
FUN_0000B4D0: native 64 KB erase helper
```

The bug has two parts:

```text
BA44 polls erase completion with timeout 300, which is too short for the observed 64 KB erase path.
B4D0 calls BA44 after issuing 0xD8 64 KB erase, then overwrites the result with success.
```

The repaired payload changes:

```text
AP file offset 0xAA4A: 4F F4 96 70 -> 40 F2 E8 30   ; BA44 timeout 300 -> 1000
AP file offset 0xA534: 01 20       -> 00 BF         ; B4D0 propagates BA44 result
```

In local testing, this repaired the native 64 KB erase path and allowed full-screen static images and custom GIFs to write correctly.

## Repair Tool Usage

Start with [FIRMWARE_PATCHER.md](FIRMWARE_PATCHER.md).

The patcher expects a local extracted official GIGABYTE LCD firmware `1.4` folder containing:

```text
AP
AP1
GvLcdFwUpdate.dll
```

Download `AorusLcdFirmwarePatcher-win-x64-self-contained.exe` from GitHub Releases and run it directly. The graphical repair screen shows a firmware flashing warning, asks for the extracted official firmware folder, and provides `Start / Baslat` and `Cancel / Iptal` buttons.

The tool does not validate or flash until the user selects a folder and presses `Start / Baslat`.

If the selected folder is not the exact tested official firmware package, the tool stops before flashing.

During repair, it creates a local stage folder under:

```text
%LOCALAPPDATA%\AorusLcdFirmwareRepair\stage
```

The stage folder contains patched `AP`/`AP1`, required official updater DLL/runtime files, `patch-manifest.json`, `patch-report.md`, and `repair-flash.log`.

## Build From Source

Install the .NET 8 SDK, then build:

```powershell
dotnet build .\AorusLcdFirmwarePatcher.csproj -c Release
```

Create the standalone Windows executable used for GitHub Releases:

```powershell
dotnet publish .\AorusLcdFirmwarePatcher.csproj -c Release -r win-x64 -p:PublishSingleFile=true --self-contained true -o .\publish\firmware-patcher-self-contained
```

## Safety Boundary

Read [SAFETY.md](SAFETY.md) before using the patched firmware payloads.

Firmware flashing can leave the LCD controller unusable if the wrong package, wrong model, interrupted update, or bad payload is used. The repair command therefore refuses to flash unless the exact tested AP/AP1 hash, updater DLL hash, patched AP/AP1 hash, CRC, patch bytes, administrator context, and confirmation token are all present.

## Investigation Highlights

- Verified the GPU and LCD controller were reachable through the existing GIGABYTE/NVAPI I2C path.
- Proved static `pData` rendered correctly offline, eliminating the image converter as the root cause.
- Reconstructed relevant LCD commands including `F1`, `F2`, `E1`, `E5`, `E7`, `F3`, and `AA`.
- Confirmed the Windows upload path reported success even when panel flash state was stale or partially erased.
- Built and tested a temporary static-image host workaround, then superseded it with the AP firmware root-cause fix.
- Mapped the AP firmware native 64 KB erase helper and proved the short timeout/failure masking defect.
- Verified the native AP repair in local testing.

## Repository Layout

```text
AorusLcdFirmwarePatcher.csproj       Root .NET project for the firmware repair tool
src/Program.cs                       Patcher source code
GitHub Release asset                 Optional runtime-free prebuilt executable, not committed
FIRMWARE_PATCHER.md                  Patcher usage, validation, and output format
SAFETY.md                            Firmware safety notes and boundaries
docs/evidence/                       Final success notes and test evidence
docs/gif-firmware-analysis/          AP/GIF firmware investigation reports
docs/analysis/                       Earlier protocol and SendImage analysis
docs/research-log/                   Curated research history and artifact map
experiments/                         Historical traces and rejected hypotheses
tools/firmware-analysis/             Offline AP firmware analysis helpers
tools/firmware-harness/              Controlled firmware harness source
tools/host-analysis/                 Host-side managed assembly scanners
tools/ghidra-scripts/                Ghidra scripts for AP firmware analysis
```

Useful starting points:

- [Firmware patcher guide](FIRMWARE_PATCHER.md)
- [Safety notes](SAFETY.md)
- [Native 64 KB timeout success evidence](docs/evidence/gif-native64-timeout1000-success.md)
- [GIF firmware analysis summary](docs/gif-firmware-analysis/GIF_Issue_Public_Research_Summary.md)
- [External protocol references](docs/research-log/EXTERNAL_PROTOCOL_REFERENCES.md)
- [Offline artifact review](docs/research-log/OFFLINE_ARTIFACT_REVIEW_20260602.md)
- [English investigation journal](JOURNAL-en.md)
- [Turkish investigation journal](JOURNAL-tr.md)

## Current Status

```text
Static custom image corruption: fixed locally by AP firmware patch
Custom GIF corruption: fixed locally by AP firmware patch
Final product in this repo: safe payload patcher plus explicitly confirmed repair flashing mode
```

## AI Assistance

Parts of this investigation, documentation cleanup, code review, and reverse-engineering workflow were assisted by OpenAI Codex/ChatGPT. The technical claims in this repository are based on included traces, offline firmware analysis notes, source code, and user-observed test results rather than AI output alone.

## Disclaimer

This is an independent reverse-engineering investigation. It is not affiliated with, endorsed by, or supported by GIGABYTE. Use at your own risk. Do not redistribute proprietary vendor binaries or firmware files.
