# Firmware Patcher Guide

This document explains how to use the AORUS LCD firmware repair tool.

Current patcher version: `1.1.0`

> [!WARNING]
> Firmware flashing is risky. A wrong firmware package, wrong GPU model, interrupted flash, or unexpected updater behavior can make the LCD controller unusable.
>
> This tool was tested only with the official GIGABYTE LCD firmware `1.4` package for the tested AORUS RTX 5080 MASTER ICE card variant.

## What The Tool Fixes

The repair patches the AP firmware's native 64 KB erase path:

```text
BA44 timeout: 300 -> 1000
B4D0 forced success: removed / BA44 result propagated
B6CC forced success: removed / page-program result propagated
```

The byte-level changes are:

```text
AP file offset 0xAA4A: 4F F4 96 70 -> 40 F2 E8 30
AP file offset 0xA534: 01 20       -> 00 BF
AP file offset 0xA74E: 01 20       -> 00 BF
```

The same changes are applied to `AP1`.

Do not manually apply these offsets to other firmware versions. These offsets are valid only for the exact tested AP/AP1 SHA256 listed below.

## Input Requirements

Use the locally obtained official GIGABYTE LCD firmware `1.4` package for the exact tested card model.

The selected folder must contain:

```text
AP
AP1
GvLcdFwUpdate.dll
```

The known tested official firmware `1.4` payload has:

```text
AP size:      58328
AP1 size:     58328
AP SHA256:    DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
AP1 SHA256:   DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
Updater DLL:  DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70
```

The repair tool accepts only this exact tested AP/AP1 payload and updater DLL. Other firmware versions or GPU variants are unsupported unless their AP/AP1 payloads and updater DLL have been separately analyzed and validated.

## Preparing The Official Firmware Folder

For non-technical users, the recommended preparation flow is:

1. Download the official LCD firmware `1.4` package from the GIGABYTE support page for the exact card model.
2. Do not download firmware packages from third-party mirrors.
3. The official GIGABYTE package is downloaded as an `.exe`
4. Run the official GIGABYTE LCD firmware `1.4` `.exe` package so it creates/extracts its firmware working folder.
5. Locate the created/extracted folder that contains:

   ```text
   AP
   AP1
   GvLcdFwUpdate.dll
   ```

6. Select that created/extracted folder in the patcher GUI.

Do not rename, edit, replace, or mix `AP`, `AP1`, or `GvLcdFwUpdate.dll` from different firmware packages.

## Running The Tool

For non-technical users, download the prebuilt Windows x64 executable from GitHub Releases when available:

```text
AorusLcdFirmwarePatcher-win-x64-self-contained.exe
```

Run the GUI as Administrator before pressing `Start`.

The GUI provides:

- a firmware flashing warning;
- a folder selector for the created/extracted official firmware folder;
- a default suggested location when one can be found;
- a `Start` button;
- a `Cancel` button.

The tool does not validate or flash until `Start` is pressed.

After `Start`, the tool:

1. validates the selected folder;
2. verifies the official AP/AP1 size and SHA256;
3. verifies the official updater DLL SHA256;
4. creates a local stage folder;
5. patches `AP` and `AP1`;
6. validates the patched output;
7. flashes through the official updater DLL.

If any guard fails, the tool stops before flashing.

## Stage Folder

During repair, the tool creates a local stage folder under:

```text
%LOCALAPPDATA%\AorusLcdFirmwareRepair\stage
```

The stage folder contains:

```text
AP
AP1
GvLcdFwUpdate.dll
patch-manifest.json
patch-report.md
repair-flash.log
```

Additional updater/runtime files may also be staged if they are present in the selected official firmware working folder and required by the updater DLL.

The generated manifest stores file names rather than absolute local paths, so it can be shared without exposing the user's Windows profile path.

## Guard Conditions

The tool refuses to continue unless all required checks pass.

Input checks:

- `AP` exists.
- `AP1` exists.
- `GvLcdFwUpdate.dll` exists.
- `AP` and `AP1` are both 58328 bytes.
- `AP` and `AP1` are identical.
- `AP` and `AP1` match the known tested SHA256.
- `GvLcdFwUpdate.dll` matches the known tested SHA256.
- Offset `0xAA4A` contains `4F F4 96 70`.
- Offset `0xA534` contains `01 20`.
- Offset `0xA74E` contains `01 20`.

Patched output checks:

- staged `AP` and `AP1` are both 58328 bytes;
- staged `AP` and `AP1` are identical;
- staged `AP` and `AP1` contain the expected patch bytes;
- staged `AP` and `AP1` have SHA256:

  ```text
  046CB6D001EA6787C789E78E8103450478EAE4FAA21F00A0E4219454F7DDD333
  ```

- staged `AP` and `AP1` have CRC16 over `0x28..EOF`:

  ```text
  0xCB8A
  ```

Runtime checks:

- the process is running on Windows;
- the process is running as Administrator;
- the user pressed `Start` in the GUI.

## Expected Original Values

Original AP/AP1 CRC16 over `0x28..EOF`:

```text
0x4560
```

Original AP/AP1 SHA256:

```text
DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
```

Original updater DLL SHA256:

```text
DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70
```

## Expected Patched Values

Patched AP/AP1 SHA256:

```text
046CB6D001EA6787C789E78E8103450478EAE4FAA21F00A0E4219454F7DDD333
```

Patched AP/AP1 CRC16 over `0x28..EOF`:

```text
0xCB8A
```

## Build From Source

Install the .NET 8 SDK and run:

```powershell
dotnet build .\AorusLcdFirmwarePatcher.csproj -c Release
```

Build the standalone Windows x64 executable used for GitHub Releases:

```powershell
dotnet publish .\AorusLcdFirmwarePatcher.csproj -c Release -r win-x64 -p:PublishSingleFile=true --self-contained true -o .\publish\firmware-patcher-self-contained
```

The project is buildable from source, but it does not currently claim deterministic/reproducible release builds.

## What This Tool Does Not Do

The tool does not:

- include GIGABYTE firmware files;
- include GIGABYTE DLLs in the repository;
- modify `GvLcdFwUpdate.dll`;
- modify GIGABYTE Control Center files;
- replace live files in Program Files;
- run GIGABYTE updater EXEs;
- start or stop services;
- edit the Windows registry;
- support arbitrary firmware versions;
- support arbitrary GPU variants.

## If The Tool Fails

If validation fails, do not try to bypass the check.

Common causes:

- wrong firmware version;
- wrong GPU model package;
- official package did not create/extract the expected firmware folder;
- files copied from different packages;
- modified vendor files;
- missing Administrator elevation.

Check the message shown by the GUI and inspect:

```text
%LOCALAPPDATA%\AorusLcdFirmwareRepair\stage\repair-flash.log
```

when a flashing attempt has already started.
