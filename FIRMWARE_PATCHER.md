# Firmware Patcher Guide

This project provides a guarded patcher/repair tool for the AORUS LCD AP firmware payloads used by the tested GIGABYTE LCD update package.

The executable opens a graphical repair screen. It stages patched `AP` and `AP1` files and flashes only after strict validation and after the user presses `Start / Baslat`.

## What It Fixes

The patch repairs the AP firmware's native 64 KB erase path:

```text
BA44 timeout: 300 -> 1000
B4D0 forced success: removed / BA44 result propagated
```

The byte-level changes are:

```text
AP file offset 0xAA4A: 4F F4 96 70 -> 40 F2 E8 30
AP file offset 0xA534: 01 20       -> 00 BF
```

The same changes are applied to `AP1`. The tool refuses to patch if the expected guard bytes are not present.

## Input Requirements

Use the locally obtained official GIGABYTE LCD firmware `1.4` package for the tested AORUS RTX 5080 ICE LCD panel and extract or stage the files so one folder contains:

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

The repair tool accepts only this exact tested AP/AP1 payload. Other firmware versions or GPU variants should be treated as unsupported unless their AP/AP1 and updater DLL are separately analyzed and validated.

## Usage

For non-technical users, download `AorusLcdFirmwarePatcher-win-x64-self-contained.exe` from GitHub Releases and run it directly. It opens a graphical screen with:

- a firmware flashing warning;
- a folder selector for the extracted official GIGABYTE LCD firmware `1.4` folder;
- a default suggested location when one can be found;
- `Start / Baslat` and `Cancel / Iptal` buttons.

The GUI does not validate or flash until `Start / Baslat` is pressed.

The release executable is self-contained and does not require the Microsoft .NET Desktop Runtime. It is intentionally distributed as a GitHub Release asset rather than committed into the repository because the runtime-free build is large.

After `Start / Baslat`, the tool validates the selected folder, creates a stage folder, patches `AP`/`AP1`, validates the patched output, and then flashes through the official updater DLL.

If the folder does not contain the exact tested official firmware payload, the tool stops before flashing.

The generated manifest stores file names rather than absolute local paths, so it can be shared without exposing the user's Windows profile path.

The flash path refuses to continue unless all of these are true:

- The official input AP/AP1 match the known tested SHA256.
- The official `GvLcdFwUpdate.dll` matches the known tested SHA256.
- The staged AP/AP1 contain the expected patch bytes.
- The staged AP/AP1 hash is `FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737`.
- The staged AP/AP1 CRC16 over `0x28..EOF` is `0xFFFE`.
- The process is running on Windows as Administrator.
- The user has pressed `Start / Baslat` in the GUI.

Repair mode writes `repair-flash.log` into the stage folder during flashing.

Expected original AP/AP1 CRC16 over `0x28..EOF`:

```text
0x4560
```

Expected patched AP/AP1 values:

```text
SHA256: FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737
CRC16:  0xFFFE
```

## Build From Source

Install the .NET 8 SDK and run:

```powershell
dotnet build .\AorusLcdFirmwarePatcher.csproj -c Release
```

Build the standalone executable used for GitHub Releases:

```powershell
dotnet publish .\AorusLcdFirmwarePatcher.csproj -c Release -r win-x64 -p:PublishSingleFile=true --self-contained true -o .\publish\firmware-patcher-self-contained
```

## Guard Conditions

The patcher checks:

- `AP` and `AP1` exist.
- `GvLcdFwUpdate.dll` exists.
- `AP` and `AP1` are both 58328 bytes.
- `AP` and `AP1` are identical.
- Offset `0xAA4A` contains `4F F4 96 70`.
- Offset `0xA534` contains `01 20`.
- `AP` and `AP1` match the known tested SHA256.

If any guard fails, the tool stops.

## What This Tool Does Not Do

- It does not include GIGABYTE firmware files.
- It does not modify `GvLcdFwUpdate.dll`.
- It does not modify GIGABYTE Control Center files.
- It does not replace live files.
- It does not run GIGABYTE updater EXEs.
- It does not start or stop services.
- It does not edit registry or Program Files.
