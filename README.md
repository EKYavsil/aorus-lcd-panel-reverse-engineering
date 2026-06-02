# AORUS LCD Panel Reverse Engineering

Independent reverse-engineering notes and an MIT-licensed firmware repair tool for the custom image/GIF corruption issue observed on the GIGABYTE AORUS GeForce RTX 5080 MASTER ICE LCD panel.

> [!WARNING]
> This project contains an experimental firmware repair tool. Firmware flashing is inherently risky and can make the LCD controller unusable if the wrong package is selected, the wrong hardware is used, or the process is interrupted.
>
> The repair tool was tested only with the official GIGABYTE LCD firmware `1.4` package for the tested AORUS RTX 5080 MASTER ICE card variant. Do not use it on other GPU models, firmware versions, or LCD packages unless they have been separately analyzed and validated.
>
> Prefer an official GIGABYTE firmware/software fix when one is available.

## Practical Summary

The observed issue affected custom LCD media uploaded through GIGABYTE Control Center:

- Custom static images could update only the upper part of the LCD.
- The lower region could remain black or stale.
- Custom GIFs could upload but display incorrectly, freeze, stay on loading, or leave the panel in an inconsistent state.
- Built-in/default LCD modes could still display correctly.

The investigation indicates that the panel-side AP firmware has a defective native 64 KB flash erase path. The local repair patches the AP firmware payload so the native 64 KB erase path waits longer and no longer hides status-poll failure from the caller.

This repository contains:

- public research notes;
- buildable source code for a guarded repair tool;
- documentation for using the tool safely;
- no GIGABYTE firmware files, DLLs, installers, extracted packages, or proprietary binaries.

## Current Status

```text
Static custom image corruption: fixed locally by AP firmware repair
Custom GIF corruption:          fixed locally by AP firmware repair
Public deliverable:             guarded source code + optional GitHub Release executable
```

The tool does not include vendor firmware or vendor binaries. It asks the user to select a locally created/extracted official GIGABYTE LCD firmware folder, validates the expected files strictly, stages patched `AP`/`AP1` payloads, and flashes only after the user presses `Start`.

## Supported Hardware And Firmware

Confirmed local test target:

```text
GPU:  GIGABYTE AORUS GeForce RTX 5080 MASTER ICE 16G
SSID: 0x418C
VID:  0x10DE
DID:  0x2C02
SVID: 0x1458
```

Confirmed firmware package:

```text
Official LCD firmware: GV-N5080AORUSM_ICE-16GD_LCD_F1.4.exe
AP size:               58328 bytes
AP SHA256:             DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
AP1 SHA256:            DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
GvLcdFwUpdate.dll:     DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70
```

Other firmware versions or GPU variants should be treated as unsupported until their AP/AP1 payloads and updater DLL have been separately analyzed.

## Repair Tool Usage

- [Firmware patcher guide](FIRMWARE_PATCHER.md)
- [Safety notes](SAFETY.md)

## Root Cause Summary

Two AP firmware sites were identified as central to the issue:

```text
FUN_0000BA44: SPI status/WIP poll helper
FUN_0000B4D0: native 64 KB block erase helper
```

The bug has two parts:

```text
BA44 polls erase completion with timeout 300, which is too short for the observed 64 KB erase path.
B4D0 calls BA44 after issuing a 0xD8 64 KB erase, then overwrites the result with success.
```

The local repair changes:

```text
AP file offset 0xAA4A: 4F F4 96 70 -> 40 F2 E8 30   ; BA44 timeout 300 -> 1000
AP file offset 0xA534: 01 20       -> 00 BF         ; B4D0 no longer forces success
```

The same changes are applied to `AP1`.

Do not manually apply these offsets to other firmware versions. These offsets are valid only for the exact tested AP/AP1 SHA256 listed above.

## Investigation Highlights

- Verified that the GPU and LCD controller were reachable through the existing GIGABYTE/NVAPI I2C path.
- Proved that static image `pData` rendered correctly offline, eliminating the image converter as the root cause.
- Reconstructed relevant LCD commands including `F1`, `F2`, `E1`, `E5`, `E7`, `F3`, and `AA`.
- Confirmed that the Windows upload path could report success even when panel flash state was stale or partially erased.
- Built and tested a temporary static-image host workaround, then superseded it with the AP firmware root-cause fix.
- Mapped the AP firmware native 64 KB erase helper and identified the short timeout/failure-masking defect.
- Verified the native AP repair in local testing.

## Repository Layout

```text
AorusLcdFirmwarePatcher.csproj       Root .NET project for the firmware repair tool
src/Program.cs                       Patcher source code
FIRMWARE_PATCHER.md                  Patcher usage, validation, and output format
SAFETY.md                            Firmware safety notes and boundaries
GIGABYTE_BUG_REPORT.md               Vendor-facing technical issue report
docs/evidence/                       Final success notes and test evidence
docs/gif-firmware-analysis/          AP/GIF firmware investigation reports
docs/analysis/                       Earlier protocol and SendImage analysis
docs/research-log/                   Curated research history and artifact map
experiments/                         Historical experiments and rejected hypotheses, not required for normal use
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

## AI Assistance

Parts of this investigation, documentation cleanup, code review, and reverse-engineering workflow were assisted by OpenAI Codex/ChatGPT. The technical claims in this repository are based on included traces, offline firmware analysis notes, source code, my own hardware testing, and findings validated through my direct guidance and review, rather than AI output alone.

## License

The original source code and documentation in this repository are licensed under the MIT License.

This license does not grant rights to GIGABYTE software, firmware, DLLs, installers, assets, trademarks, update packages, or other proprietary vendor material.

## Disclaimer

This is an independent reverse-engineering investigation. It is not affiliated with, endorsed by, or supported by GIGABYTE.

Use at your own risk. Do not redistribute proprietary vendor binaries, firmware files, update packages, or extracted vendor assets.
