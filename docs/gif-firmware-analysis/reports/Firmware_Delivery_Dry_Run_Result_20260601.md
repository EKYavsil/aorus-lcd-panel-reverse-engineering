# Firmware Delivery Dry Run Result - 2026-06-01

Scope: offline only. The dry-run script did not load any native DLL, did not execute the updater, did not call I2C, and did not modify live files.

Script:

```text
<local-gif-investigation-dir>\firmware_delivery_dry_run.py
```

Output:

```text
<local-gif-investigation-dir>\firmware_delivery_dry_run_20260601.json
```

Verdict:

```text
PASS_OFFLINE
```

## What The Dry Run Verified

Using staged files under:

```text
<local-gif-investigation-dir>\offline_firmware_patch_candidates_20260601
```

The dry run verified:

| Check | Result |
|---|---|
| Patched AP size matches `FW_FileSize` | Pass |
| Patched AP1 size matches `FW_FileSize1` | Pass |
| Patched AP and AP1 hashes are identical | Pass |
| Exact-end updater DLL candidate exists | Pass |
| Sector-end updater DLL candidate exists | Pass |
| No live action performed | Pass |

Patch AP/AP1 hash:

```text
821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643
```

Patch AP/AP1 CRC over `0x28..EOF`:

```text
0x4BDD
```

## Important Naming Finding

The current staged patch files are intentionally named:

```text
AP_option3_16x4k.bin
AP1_option3_16x4k.bin
```

This is safe for offline storage, but the real updater/native path expects:

```text
AP
AP1
```

Therefore a future controlled package or harness must make sure:

```text
AP  == patched AP option3 bytes
AP1 == patched AP option3 bytes
```

Both names matter because the managed updater can replace `AP` with `AP1` if the fallback IAP address path succeeds.

## Simulated Call Sequence

The dry run reconstructed the expected sequence:

```text
DisposeResource()
I2CInitial(4096)
I2CAPChangeToIAP(0xC2)
I2CIAPChangeToErase12ByteMode(0x44)
fallback: I2CIAPChangeToErase12ByteMode(0x46)
if fallback succeeds: copy AP1 over AP
FileInfo(AP).Length == 58328
I2CIAPSetAPFlashTable(58328)
I2CIAPSubmitCRCAP(active IAP address)
I2CIAPFlashAP12ByteMode(active IAP address)
I2CIAPChangeToAP(active IAP address)
```

## Erase End Decision

Two native updater erase-window candidates exist:

| Candidate | End | Meaning |
|---|---:|---|
| exact end | `0xF3D7` | Erase exactly through observed F1.4 AP program end |
| sector end | `0xFFFF` | Erase through the full containing 4KB sector |

Current recommendation: prefer `0xFFFF` for any future controlled test design.

Reasoning:

- The original updater erased through `0xEFFF`, which is a 4KB sector boundary.
- F1.4 programming extends into the next sector: `0xF000..0xF3D7`.
- Flash erase generally works at sector granularity, not arbitrary byte granularity.
- Erasing only to `0xF3D7` may be accepted by the bootloader, but it is less aligned with the updater's existing pattern.
- Erasing through `0xFFFF` covers the whole containing sector and better matches the original sector-end style.

Remaining risk:

- We still need to confirm that `0x1000..0xFFFF` is entirely AP region and does not overlap bootloader/config storage.
- Based on the original code shape, `0x1000` is AP start and the old sector-aligned `0xEFFF` suggests this range is intended as AP flash area, but this should still be treated as a live-test blocker.

## Current Controlled Delivery Design

If live firmware testing is ever approved later, the least ambiguous design would be:

1. Create a separate staging directory.
2. Put patched AP bytes under both names:

   ```text
   AP
   AP1
   ```

3. Use the sector-end native updater DLL candidate:

   ```text
   GvLcdFwUpdate_erase_end_FFFF.dll
   ```

4. Ensure all dependent DLLs are loaded from that staging directory.
5. Log the exact AP/AP1 hashes and computed CRC before any firmware command.
6. Only then call the firmware sequence, and only with a documented official recovery path.

This is not a recommendation to run it now. It is the current safest design shape if the project later reaches a live firmware test stage.

## Next Offline Step

Before any live firmware work:

- Build an offline package verifier that creates a "would-be package" directory with real `AP` and `AP1` names.
- Verify every hash.
- Verify Authenticode status.
- Verify no original EXE resource extraction can overwrite the staged AP files in the selected delivery method.
- Keep this package separate from the public repo and from the working static DLL fix.

## Offline Would-Be Package Result

The package verifier was created and run:

```text
<local-gif-investigation-dir>\stage_offline_would_be_firmware_package.py
```

It produced:

```text
<local-gif-investigation-dir>\offline_would_be_firmware_package_sector_FFFF_20260601
```

Verdict:

```text
PASS_OFFLINE_PACKAGE_STAGING
```

This package uses the safer current design shape:

```text
AP  = AP option3 16x4KB erase-loop candidate
AP1 = same AP option3 16x4KB erase-loop candidate
GvLcdFwUpdate.dll = erase-end 0xFFFF candidate
```

Key manifest values:

| File | SHA256 / Status |
|---|---|
| `AP` | `821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643` |
| `AP1` | `821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643` |
| `AP` CRC over `0x28..EOF` | `0x4BDD` |
| `AP1` CRC over `0x28..EOF` | `0x4BDD` |
| `GvLcdFwUpdate.dll` | `9BBE689850A5BD3C6D18242D79833AAE19B667DF9FB807BEF778F66A72EC9AB9` |

Authenticode status:

| File | Status |
|---|---|
| `GvLcdFwUpdate.dll` | `HashMismatch` |
| `GvDisplay.dll` | `Valid` |
| `GvDisplayA.dll` | `Valid` |
| `GvIntelI2C.dll` | `Valid` |

The package folder is still offline-only. It should not be run directly. It exists only to prove that the intended file names, hashes, sizes, and CRCs can be staged without ambiguity.


