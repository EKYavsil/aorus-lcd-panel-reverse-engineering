# Offline Firmware Patch Staging - 2026-06-01

Scope: offline artifact staging only.

No updater was executed. No DLL was loaded or called. No I2C/GvWriteI2C/GvReadI2C call was made. No service was stopped or started. No live `Program Files` or `Downloads` input file was modified.

## Why This Stage Exists

The current strongest GIF-side root-cause direction is the AP firmware 64KB erase path, not the host GIF converter.

Known facts:

- Static image became reliable when the host-side F1 erase selector was changed from `0x02` to `0x01`, which made the panel use the 4KB erase path instead of the 64KB erase path.
- GIF naturally needs the `0x02` path because AP firmware also couples that selector to GIF timing/finalization state.
- Forcing GIF to `0x01` host-side is not equivalent to a clean fix and caused unstable behavior in tests.
- Therefore the preferred repair candidate is: keep host/GCC GIF metadata semantics unchanged, but repair the AP firmware's internal 64KB erase helper.

## Staged Directory

`<local-gif-investigation-dir>\offline_firmware_patch_candidates_20260601`

## Inputs

| Input | Size | SHA256 |
|---|---:|---|
| `<local-firmware-package-dir>\AP` | 58328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `<local-firmware-package-dir>\AP1` | 58328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `<local-firmware-package-dir>\GvLcdFwUpdate.dll` | 2496752 | `DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70` |

## Staged Outputs

| Output | Size | SHA256 | Notes |
|---|---:|---|---|
| `AP_option3_16x4k.bin` | 58328 | `821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643` | AP option3 candidate |
| `AP1_option3_16x4k.bin` | 58328 | `821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643` | Same patch applied to AP1 |
| `GvLcdFwUpdate_erase_end_F3D7.dll` | 2496752 | `514D062A7AD57C1E0114ACFA9DBC4F06DD78A2654C5E2528CAE393B689F55556` | Native updater exact AP end candidate |
| `GvLcdFwUpdate_erase_end_FFFF.dll` | 2496752 | `9BBE689850A5BD3C6D18242D79833AAE19B667DF9FB807BEF778F66A72EC9AB9` | Native updater sector end candidate |
| `GvDisplay.dll` | 2763880 | `42BF2FC0855BF8736C4608F98A62C5919356BF74D651E130229E9DC8DD495FD0` | Copied unmodified |
| `GvDisplayA.dll` | 2744560 | `131B085FA7AB86EC86248C83CC1A865D2C4DE7F43ED5465B4003DC80FC33CEC1` | Copied unmodified |
| `GvIntelI2C.dll` | 68200 | `EC9701D7D1BABA70E838F309FE6BA75B3BE8CDC99635477CE900820EC939BC93` | Copied unmodified |
| `config.db` | 8192 | `E75E8D9044E281D344D159CDAFD839BF1DD05EE9C75BA64D1EBC2AC8B1331BF0` | Copied unmodified |

## AP Option3 Patch

Target:

- AP function: `FUN_0000B4D0`
- AP address: `0x0000B4D0`
- File offset: `0xA4D0`
- Original prefix: `2D E9 F0 41 04 46`

Patch bytes:

```text
30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1
04 F5 80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD
```

Meaning:

- Replace the AP 64KB erase helper entry with a loop that calls the known 4KB sector erase helper `FUN_0000B910`.
- Loop count: 16 sectors.
- Address step: `0x1000`.
- Return `0` if a sector erase fails.
- Return `1` only if all 16 sector erases succeed.

This preserves the host-visible `F1[0x11] == 0x02` GIF semantics while avoiding the suspected broken AP-native 64KB erase operation.

Verification:

- `AP_option3_16x4k.bin` and `AP1_option3_16x4k.bin` have identical SHA256.
- Patch bytes are present at file offset `0xA4D0`.
- The old function tail remains in the file after the patch, but the patched helper returns before the old body is reached.

## Native Updater Erase-Range Patch Candidates

The managed updater first calls `I2CIAPChangeToErase12ByteMode`, then later flashes the AP image.

Cross-version finding:

| AP Version | AP Size | Flash Range | Fits Original Fixed Erase `0x1000..0xEFFF` |
|---|---:|---|---|
| F1.2 | `0xDC54` | `0x1000..0xEC53` | Yes |
| F1.3 | `0xDD90` | `0x1000..0xED8F` | Yes |
| F1.4 | `0xE3D8` | `0x1000..0xF3D7` | No |

The F1.4 AP flash write extends 984 bytes beyond the native updater's fixed erase window. That is a separate updater bug candidate from the AP 64KB erase helper bug.

DLL target:

- File: `GvLcdFwUpdate.dll`
- Offset: `0x2A1C`
- Original bytes: `C7 44 24 2C FF EF 00 00`
- Original meaning: fixed erase end `0xEFFF`

Candidate A, exact AP end:

```text
C7 44 24 2C D7 F3 00 00
```

Meaning: erase end becomes `0xF3D7`.

Candidate B, sector end:

```text
C7 44 24 2C FF FF 00 00
```

Meaning: erase end becomes `0xFFFF`.

Tradeoff:

- `0xF3D7` is narrower and exactly matches the observed F1.4 flash end.
- `0xFFFF` covers the full containing flash sector and is likely cleaner if the bootloader expects sector-aligned erase ranges.
- Without bootloader confirmation, neither should be treated as ready for live use.

## Authenticode Status

| File | Status | Interpretation |
|---|---|---|
| `GvLcdFwUpdate_erase_end_F3D7.dll` | `HashMismatch` | Expected: modified signed DLL |
| `GvLcdFwUpdate_erase_end_FFFF.dll` | `HashMismatch` | Expected: modified signed DLL |
| `GvDisplay.dll` | `Valid` | Copied unmodified |
| `GvDisplayA.dll` | `Valid` | Copied unmodified |
| `GvIntelI2C.dll` | `Valid` | Copied unmodified |

The modified DLL candidates are not signature-valid. Any future live plan must account for whether the updater process, Windows policy, or loader path enforces signature validity.

## Critical Packaging Warning

The original managed `FWUpgrade.exe` extracts embedded resources through `DisposeResource()`.

Observed behavior:

- It writes `AP`, `AP1`, `config.db`, and native DLL resources to disk using file creation.
- That means simply placing loose patched `AP` or `AP1` files beside the original updater is not enough if the original EXE is executed normally.
- The original EXE can overwrite loose AP files before flashing.

Therefore a real live test would need one of these controlled approaches:

1. A harness that calls the native updater DLL with explicit staged AP/AP1 files.
2. A modified package whose embedded resources are replaced.
3. A carefully controlled extraction-time interception strategy.

No live path has been executed or recommended yet.

## Current Decision

The AP option3 patch is the cleaner engineering direction for the GIF problem:

- It keeps GCC/host GIF command semantics intact.
- It targets the suspected broken implementation detail: AP firmware's 64KB erase helper.
- It reuses the already-working AP 4KB sector erase routine.

The native updater erase-window patch is a separate firmware-delivery correctness issue:

- It explains why F1.4 flashing can be inconsistent or fail with erase/write mismatch.
- It does not by itself prove that custom GIF storage erase is fixed.
- It may be needed only to deliver a patched F1.4 AP safely.

## Live-Test Blockers

Do not proceed to live firmware flashing until these are resolved:

1. Confirm whether the bootloader validates AP payload only through the submitted CRC or also through embedded metadata/signature.
2. Confirm how the native updater selects loose AP/AP1 files after `DisposeResource()`.
3. Decide exact-end `0xF3D7` versus sector-end `0xFFFF` for the native erase-window patch.
4. Prepare a non-destructive dry-run harness if possible, or a minimal flash harness with explicit logs.
5. Define a recovery path using known-good official firmware and exact steps before any modified AP is flashed.
6. Keep static-image fixed GCC DLL backed up separately and do not mix it with firmware experiments.

## Bottom Line

Most likely current root cause:

- AP firmware's 64KB erase path is unreliable or semantically incompatible with the panel custom-media storage path.
- Static image was fixed by avoiding that path from the host side.
- GIF cannot safely avoid `F1[0x11] == 0x02` host-side because AP uses that same selector for GIF-specific state/timing.

Best next offline step:

- Disassemble or emulate enough of the bootloader/update validation path to confirm whether `AP_option3_16x4k.bin` can be accepted after CRC submission.
- Do not run a live updater yet.


