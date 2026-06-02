# AP Patch CRC And Bootloader Acceptance Notes - 2026-06-01

Scope: offline analysis only. No updater execution, no DLL loading, no I2C, no panel access.

## Question

If `AP_option3_16x4k.bin` is delivered through the firmware updater path, is there evidence that a static AP header checksum/signature must be edited too?

Current answer: no static header checksum/signature was identified. The native updater computes a fresh CRC over the AP bytes it has loaded.

## Files Compared

| File | Size | SHA256 |
|---|---:|---|
| Original `<local-firmware-package-dir>\AP` | 58328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| Patched `AP_option3_16x4k.bin` | 58328 | `821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643` |

## Header Result

The first `0x28` bytes are identical.

The first `0x80` bytes are also identical.

This matters because native `I2CIAPSubmitCRCAP` skips the first `0x28` bytes when computing the submitted CRC.

## Diff Result

There is exactly one binary diff range:

| Start | End Inclusive | Length | Meaning |
|---:|---:|---:|---|
| `0xA4D0` | `0xA4ED` | 30 bytes | AP 64KB erase helper entry replaced with 16x4KB loop |

Original bytes at `0xA4D0`:

```text
2D E9 F0 41 04 46 B0 F1 80 6F 02 D3 00 20 BD E8
F0 81 00 F0 E7 FA 00 28 F9 D0 16 4E 16 4D
```

Patched bytes at `0xA4D0`:

```text
30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1 04 F5
80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD
```

## CRC Result

Native CRC style observed at `GvLcdFwUpdate.dll` RVA `0x3E90`:

- CRC input pointer: AP buffer pointer from `SetAPFlashTable` plus `0x28`.
- CRC length: AP size minus `0x28`.
- Polynomial: `0x1021`.
- Initial CRC: `0`.
- Command sent after CRC calculation: `0x8104`.

Computed values:

| AP | CRC16 over `0x28..EOF` |
|---|---|
| Original F1.4 AP | `0x4560` |
| Patched option3 AP | `0x4BDD` |

Implication:

- The AP file does not need a pre-edited CRC field for this updater path.
- If the patched AP bytes are what `SetAPFlashTable` loads, `I2CIAPSubmitCRCAP` should naturally submit `0x4BDD`.
- This is different from a firmware format with an embedded signature/checksum that must be manually patched.

## Native Call Evidence

Managed updater flow:

```text
I2CIAPSetAPFlashTable(AP_size)
Sleep 200
I2CIAPSubmitCRCAP(active)
Sleep 200
I2CIAPFlashAP12ByteMode(active)
```

Native `I2CIAPSubmitCRCAP`:

```text
export I2CIAPSubmitCRCAP -> RVA 0x3E90
reads global AP buffer pointer/size from SetAPFlashTable
size -= 0x28
ptr  += 0x28
CRC16(poly 0x1021)
builds packet 0x8104
sends through the same IAP command helper
```

So the CRC is not supplied by managed code and is not a constant in the AP file.

## Remaining Acceptance Unknowns

This does not prove the bootloader accepts modified AP code. It only removes one blocker.

Still unknown:

1. Whether bootloader validates only the submitted CRC or also checks an internal AP signature after flashing.
2. Whether there is a version/build allowlist in bootloader or updater.
3. Whether patched AP code is copied exactly to the expected address range when the updater's erase window is also corrected.
4. Whether `0xF3D7` exact erase end or `0xFFFF` sector erase end is safer for F1.4 delivery.

## Practical Consequence

If a future live firmware test is approved, the delivery problem is now clearer:

- AP patch itself has a clean one-range diff.
- Header stays unchanged.
- Native CRC will adjust automatically if the patched AP is actually loaded.
- The biggest packaging issue is still that original `FWUpgrade.exe` extracts embedded resources and can overwrite loose AP/AP1 files.

Therefore the safest next offline step is not live flashing. It is to design a controlled updater harness/package that guarantees the native DLL loads the staged AP candidate and logs the exact CRC/range decisions before any flash attempt.


