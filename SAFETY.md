# Safety Notes

This repository contains reverse-engineering notes and an experimental firmware payload repair tool.

> [!WARNING]
> Firmware flashing can make the LCD controller unusable. Use this project only if you understand the risk and only with the exact tested firmware package and hardware variant.

## Main Risk

Flashing LCD controller firmware is inherently risky.

A wrong package, wrong model, interrupted update, incompatible AP/AP1 pair, modified vendor file, or unexpected updater behavior can leave the LCD controller unusable.

The repair tool can flash, but only after strict validation and only after the user presses `Start` in the graphical interface.

Running the executable opens the graphical repair screen. It does not validate or flash until the user selects a folder and presses `Start`.

## Supported Boundary

The confirmed local test used the official GIGABYTE LCD firmware `1.4` package for the tested AORUS RTX 5080 MASTER ICE card variant.

Confirmed AP/AP1 payload:

```text
Size:   58328
SHA256: DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
```

Confirmed updater DLL:

```text
SHA256: DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70
```

Patched AP/AP1 output:

```text
SHA256: 046CB6D001EA6787C789E78E8103450478EAE4FAA21F00A0E4219454F7DDD333
CRC16:  0xCB8A
```

Different firmware versions or card variants may need separate analysis.

## Safer Workflow

Recommended boundaries:

1. Start from the official GIGABYTE LCD firmware `1.4` package for the exact tested card variant.
2. Download firmware only from the official GIGABYTE support page.
3. Do not download firmware packages from third-party mirrors.
4. The official GIGABYTE package is downloaded as an `.exe`, not as a `.zip`.
5. Run the official GIGABYTE LCD firmware `1.4` `.exe` package so it creates/extracts its firmware working folder.
6. Keep the original official package untouched.
7. Select the created/extracted official firmware folder in the patcher GUI.
8. Run the patcher GUI as Administrator before pressing `Start`.
9. Do not mix AP/AP1 files between firmware versions or GPU variants.
10. Do not flash during unstable power, driver installation, GCC use, firmware updates, or service changes.
11. Prefer an official GIGABYTE firmware/software fix when one is available.

## What The Patcher Guarantees

The patcher:

- reads local files selected by the user;
- verifies AP/AP1 size;
- verifies AP/AP1 byte-pattern guards;
- verifies the strict AP/AP1 SHA256 for the tested payload;
- verifies the strict `GvLcdFwUpdate.dll` SHA256 for the tested updater DLL;
- writes patched `AP` and `AP1` files to a local stage folder;
- verifies the patched AP/AP1 SHA256;
- verifies the patched AP/AP1 CRC16;
- writes a manifest and report;
- flashes only after all strict checks pass and the user presses `Start`.

## What The Patcher Does Not Do

The patcher does not:

- flash before the user selects a folder and presses `Start`;
- include GIGABYTE firmware files;
- include GIGABYTE DLLs in the repository;
- modify GIGABYTE DLLs or EXEs;
- modify files in Program Files;
- run GIGABYTE updater EXEs;
- start or stop services;
- edit the Windows registry;
- support arbitrary firmware versions;
- support arbitrary GPU variants.

## Do Not Bypass Validation

If the tool rejects a folder, do not rename files, hex-edit payloads, or mix files from another package to bypass validation.

A validation failure means the selected package does not match the exact tested payload or updater DLL expected by this repair tool.

## Public Sharing Boundary

Do not upload or share:

```text
GIGABYTE firmware packages
GIGABYTE DLLs
GIGABYTE EXEs
extracted vendor package folders
raw traces containing proprietary payloads
logs containing personal paths or private machine information
```

This repository is intended to share source code, documentation, and research notes without redistributing proprietary vendor material.
