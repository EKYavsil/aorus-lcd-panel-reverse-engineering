# Safety Notes

This repository contains research notes and a firmware payload patcher/repair tool.

## Main Risk

Flashing LCD controller firmware is inherently risky. A wrong package, wrong model, interrupted update, incompatible AP/AP1 pair, or unexpected updater behavior can leave the LCD controller unusable.

The repair tool can flash, but only after strict validation and after the user presses `Start / Baslat` in the graphical interface. Treat this as a high-risk firmware operation.

Running the executable opens the graphical repair screen. It does not validate or flash until the user selects a folder and presses `Start / Baslat`.

## Safer Workflow

Recommended boundaries:

1. Start from the official GIGABYTE LCD firmware `1.4` package for the exact tested card variant.
2. Keep the original package untouched.
3. Select the extracted official firmware folder in the GUI.
4. Let the tool create and verify the stage folder before flashing.
5. Keep backups of original `AP` and `AP1`.
6. Do not mix AP/AP1 files between firmware versions or GPU variants.
7. Do not flash during unstable power, driver installation, GCC use, or service changes.
8. Run the flashing command only from an elevated Administrator terminal.

## What The Patcher Guarantees

The patcher:

- Reads local files.
- Verifies size and byte-pattern guards.
- Verifies the strict AP/AP1 SHA256 for the tested payload.
- Writes new `AP` and `AP1` files to the local stage folder.
- Writes a manifest and report.
- Stages only the required updater DLL/runtime files and patched AP/AP1.
- Flashes only after all strict checks pass and the user presses `Start / Baslat`.

The patcher does not:

- Flash from the GUI before the user selects a folder and presses `Start / Baslat`.
- Run GIGABYTE updater executables.
- Modify GIGABYTE DLLs or EXEs.
- Modify files in Program Files.
- Start or stop services.
- Edit registry.

## Tested Payload

The confirmed local test used AP/AP1 payloads from the official GIGABYTE LCD firmware `1.4` package with:

```text
Size:   58328
SHA256: DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
```

The patched output was:

```text
SHA256: FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737
CRC16:  0xFFFE
```

Different firmware versions or card variants may need separate analysis.
