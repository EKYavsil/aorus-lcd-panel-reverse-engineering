# AP F1.2/F1.3/F1.4 Flash Write Failure Propagation Analysis

Date: 2026-05-31

Scope: offline-only Ghidra analysis. No live GIGABYTE files, services, firmware updater, panel commands, raw I2C, or DLL deploy were touched.

## Files Produced

- `APFirmwareExactFunctionDump.java`
- `ap_exact_function_dump_AP_F12_flash_helpers.txt`
- `ap_exact_function_dump_AP_F13_flash_helpers.txt`
- `ap_exact_function_dump_AP_F14_flash_helpers.txt`
- `ap_exact_function_dump_AP_F12_upload_loop.txt`
- `ap_exact_function_dump_AP_F13_upload_loop.txt`
- `ap_exact_function_dump_AP_F14_upload_loop.txt`

## Short Finding

The AP firmware upload/storage writer can report success even when lower flash pages are not actually programmed.

This is now confirmed across the package AP firmware variants:

- F1.2 and F1.3 have weaker flash helper semantics: erase/program helpers are `void`, so their success cannot propagate to the caller.
- F1.4 added return values for erase/page-program helpers, and the high-level loop checks those returns.
- But F1.4 page-program still ignores the SPI status-poll return, so it can return success after a program timeout/failure.

This fits the observed symptom:

- host upload sends all chunks successfully
- `SendImage` returns true
- upper image area changes
- lower part remains black/stale
- firmware reinstall does not reliably wipe custom media storage

## Helper Map

| Version | Block erase | Sector erase | Page program | Write-enable | Status poll | High-level upload loop |
|---|---:|---:|---:|---:|---:|---:|
| F1.2 package | `FUN_0000af30` | `FUN_0000b2f4` | `FUN_0000b110` | `FUN_0000b44c` | `FUN_0000b3f8` | `FUN_0000c4dc` |
| F1.3 package | `FUN_0000aff0` | `FUN_0000b3b4` | `FUN_0000b1d0` | `FUN_0000b50c` | `FUN_0000b4b8` | `FUN_0000c59c` |
| F1.4 package | `FUN_0000b4d0` | `FUN_0000b910` | `FUN_0000b6cc` | `FUN_0000bab4` | `FUN_0000ba44` | `FUN_0000cb54` |

## F1.2 Behavior

F1.2 page program helper:

```c
void FUN_0000b110(undefined1 *param_1,uint param_2,int param_3)
{
  FUN_0000b44c();          // WREN, no returned status
  FUN_0000b360(2);         // SPI page program opcode 0x02
  FUN_0000b360(addr bytes);
  while (param_3 != 0) {
    FUN_0000b360(*param_1);
    param_1 = param_1 + 1;
  }
  FUN_0000b3f8();          // status poll, void
  return;
}
```

F1.2 high-level upload loop:

```c
FUN_0000aefc(...,0);
FUN_0000b110(staging, base + offset, 0x100);
offset = offset + 0x100;
remaining = remaining - 1;
```

There is no possible page-program failure propagation because `FUN_0000b110` returns `void`. Offset and remaining chunk count advance unconditionally.

The same pattern exists for sector/block erase:

```c
FUN_0000af30(base + offset);  offset += 0x10000;
FUN_0000b2f4(base + offset);  offset += 0x1000;
```

## F1.3 Behavior

F1.3 is structurally the same as F1.2 with shifted addresses.

F1.3 page program helper:

```c
void FUN_0000b1d0(undefined1 *param_1,uint param_2,int param_3)
{
  FUN_0000b50c();          // WREN, no returned status
  FUN_0000b420(2);         // SPI page program opcode 0x02
  FUN_0000b420(addr bytes);
  while (param_3 != 0) {
    FUN_0000b420(*param_1);
    param_1 = param_1 + 1;
  }
  FUN_0000b4b8();          // status poll, void
  return;
}
```

F1.3 high-level upload loop:

```c
FUN_0000afbc(...,0);
FUN_0000b1d0(staging, base + offset, 0x100);
offset = offset + 0x100;
remaining = remaining - 1;
```

Again: no failure propagation.

## F1.4 Behavior

F1.4 improved the high-level loop. It checks helper return values:

```c
iVar10 = FUN_0000b910(base + offset);       // sector erase
if (iVar10 == 0) {
  return;
}

iVar10 = FUN_0000b6cc(staging, base + offset, 0x100);  // page program
if (iVar10 == 0) {
  return;
}
offset = offset + 0x100;
remaining = remaining - 1;
```

Sector erase checks the SPI status poll:

```c
iVar4 = FUN_0000ba44();
if (iVar4 != 0) {
  return 1;
}
return 0;
```

But page program does not:

```c
undefined4 FUN_0000b6cc(undefined1 *param_1,uint param_2,int param_3)
{
  iVar4 = FUN_0000bab4();  // WREN
  if (iVar4 != 0) {
    FUN_0000b990(2);       // SPI page program opcode 0x02
    FUN_0000b990(addr bytes);
    while (param_3 != 0) {
      FUN_0000b990(*param_1);
      param_1 = param_1 + 1;
    }
    FUN_0000ba44();        // status poll return is ignored
    return 1;
  }
  return 0;
}
```

So F1.4 still allows this failure mode:

1. page-program command is issued
2. status poll times out or reports failure internally
3. return value is ignored
4. page-program helper returns `1`
5. high-level upload loop advances offset/chunk counters
6. host sees successful upload/finalize
7. lower media area remains stale/black

## Why The 64 KB Boundary Still Matters

The static image write range is:

- slot base/header: `0x01300000`
- visible data start: `0x0130000C`
- visible data size: `108800` bytes (`320 * 170 * 2`)
- visible data end: `0x0131A90C`
- first internal 64 KB boundary: `0x01310000`

The distance from visible data start to the boundary is:

```text
0x01310000 - 0x0130000C = 65524 bytes
65524 / (320 * 2) ~= 102.38 rows
```

That is approximately the top 60% of a 170-row image. The observed lower black/stale region begins close to this boundary.

This does not prove the physical flash cannot write beyond `0x01310000`, but it makes a media-area page-program failure around/after the first 64 KB boundary the strongest current explanation.

## Boundary Constant Search

I searched the F1.2/F1.3/F1.4 AP dumps for custom media slot and boundary constants.

Found repeatedly:

- `0x01300000`
- `0x01320000`
- `0x01400000`
- `0x10000`
- `0x1000`
- `0x100`

Not found as an intentional media slot marker:

- `0x01310000`

Interpretation:

- AP firmware knows `0x01300000` and `0x01320000` as real media-slot base/range values.
- `0x01310000` is not a designed static/GIF slot boundary in the parser.
- The observed visible break near `0x01310000` is therefore more likely a technical write/erase/program boundary, not a deliberate display-layout or slot-layout boundary.

This is important because it shifts the suspicion away from "wrong destination slot" and toward "write path fails after crossing the first 64 KB region."

## IAP / Firmware Updater Relation

The updater/IAP path previously identified is real but targets AP firmware, not the custom media slot:

- `I2CAPChangeToIAP(AP_ADDRESS)` uses AP address `0xC2`
- `I2CIAPChangeToErase12ByteMode(IAP_ADDRESS)` uses erase range `0x1000..0xEFFF`
- `I2CIAPFlashAP12ByteMode(IAP_ADDRESS)` writes AP firmware chunks

This is not a custom media erase for `0x01300000`.

The observed updater error:

```text
Flash fail,I2CIAPChangeToErase12ByteMode fail!
```

means the AP firmware erase/program path can fail too, but it does not explain or repair stale custom image bytes at `0x01310000+`.

## Current Diagnosis

Most likely:

1. The host/GCC upload path is sending valid pData.
2. The AP receives chunks and reports success to the host.
3. The AP firmware write path has insufficient page-program failure propagation.
4. The lower part of the custom media slot is not actually being programmed, probably around/after the first 64 KB boundary.
5. Firmware reinstall does not clear custom media storage because the updater erase range is AP firmware space, not `0x01300000`.

Less likely now:

- converter/pData corruption
- `SendImage` metadata size/chunk-count mismatch
- display mode/loop/overlay commit issue
- Windows/GCC cache alone
- ExApi reset channel as the primary blocker

## Decision

Current category: AP firmware media-write failure propagation bug, likely involving page programming after the first 64 KB of the static custom media slot.

This is stronger than the previous display-state commit theory and stronger than the firmware-updater erase theory.
