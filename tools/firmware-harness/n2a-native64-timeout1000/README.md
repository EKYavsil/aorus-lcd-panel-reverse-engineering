# N2a Native64 Timeout1000 Harness

Public dry-run verifier derived from the controlled harness used during the successful AP firmware repair test.

The local research harness was designed to avoid the vendor updater's resource extraction overwrite behavior and to log the exact AP/AP1 hash, CRC, and patch bytes before any live call. The public version in this repository has the live firmware-flashing path removed and verifies local staged files only.

## Candidate

```text
BA44 timeout: 300 -> 1000
B4D0: propagate BA44 result instead of forcing success
```

This keeps the native 64 KB erase path:

```text
F1[0x11] == 0x02
SPI erase opcode 0xD8
erase unit 0x10000
```

## What Is Not Included

- `AP`
- `AP1`
- GIGABYTE DLLs
- compiled harness binaries
- live logs

Those files are proprietary or machine-specific and must be supplied locally by the researcher.

## Safety

This is research code retained for reproducibility and auditability. The public repository does not include the live launcher script, AP/AP1 payloads, vendor DLLs, compiled binaries, live logs, or live flashing code.

The supported public tool is the root firmware payload patcher, which generates patched files only and does not flash firmware.

