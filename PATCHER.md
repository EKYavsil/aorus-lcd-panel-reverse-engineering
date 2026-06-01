# Static Sector Patch Builder

This repository includes a minimal Mono.Cecil-based patch builder that creates a patched `ucVga.dll` from a locally obtained clean vendor DLL.

The repository does **not** include GIGABYTE DLLs, patched vendor DLL outputs, firmware images, extracted firmware packages, or raw device traces.

## Purpose

The builder modifies only the static custom-image upload metadata path in `ucVga.Api.GvLcdApi.SendImage`.

For the confirmed static image failure case, GIGABYTE Control Center generated this F1 upload header:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

The successful local workaround generated:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

The only changed byte is:

```text
F1[0x11]: 0x02 -> 0x01
```

Offline AP firmware analysis indicates that this changes the erase granularity used for the static custom-image media slot from the 64 KB block-erase path to the 4 KB sector-erase path.

## Guard Conditions

The builder inserts a narrow runtime guard. The F1 byte is changed only when all of these conditions are true:

```text
nType == 1
SendImage local destination == 0x01300000
F1 packet length >= 0x13
F1 packet magic == F1 CB 55 AC 38
F1 packet destination bytes == 01 30 00 00
F1[0x11] == 0x02
```

If any guard fails, the packet is left unchanged.

## Recommended: Use The Root Executable

For normal use, use the prebuilt executable included in the repository root:

```text
BuildFinalStaticSectorPatch.exe
```

The original GIGABYTE DLL is usually located here:

```text
C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll
```

Quick use:

1. Copy a clean local `ucVga.dll` from your own GIGABYTE Control Center installation into the same folder as `BuildFinalStaticSectorPatch.exe`.
   - Typical source path: `C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll`
2. Run `BuildFinalStaticSectorPatch.exe`.
3. The tool creates `ucVga.static-sector.dll` in the same folder.
4. Back up your original vendor `ucVga.dll` before using the generated DLL as a replacement.

The included executable is provided for convenience. It does **not** contain `ucVga.dll`, patched vendor DLLs, firmware images, GIGABYTE installers, raw traces, or proprietary vendor binaries.

If you do not trust the prebuilt binary, build the patcher yourself from the public source code.

## Developer Build From Source

Requirements:

- .NET 8 SDK
- A clean local `ucVga.dll` obtained from the user's own GIGABYTE Control Center installation
- Any required dependency DLLs available in the same directory or in the optional assembly search directory

Build from the repository root:

```powershell
dotnet build .\StaticSectorPatcher.csproj -c Release
```

Generate a patched DLL from a clean local vendor DLL:

```powershell
.\bin\Release\net8.0\BuildFinalStaticSectorPatch.exe .\ucVga.clean.dll .\ucVga.static-sector.dll "C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA"
```

Arguments:

```text
BuildFinalStaticSectorPatch <input-ucVga.dll> <output-ucVga.dll> [assembly-search-dir]
```

When run without arguments, the executable looks for `ucVga.dll` in its own folder and writes `ucVga.static-sector.dll` to that same folder.

The tool writes a new output DLL. It does not install, replace, or modify any live GIGABYTE Control Center files.

## Installation Note

Installation or replacement of a live vendor DLL is intentionally not automated in this public repository. Review and apply any generated DLL manually and only on systems where you understand the risk and have a clean restore path.

This project does not bypass vendor update mechanisms and does not recommend distributing patched vendor binaries.

## Current Limitations

- The static custom-image corruption has been fixed locally with this one-byte metadata change.
- The lower-level reason why this panel/card state fails on the 64 KB block-erase path is still under investigation.
- The custom GIF path is still unresolved and is intentionally not modified by this builder.
- Future GIGABYTE Control Center versions may change `ucVga.dll` IL layout; unsupported versions should be rejected rather than patched broadly.
