# AP SPI 4-Byte Address Mode And Status Poll Analysis

Date: 2026-05-31

Scope: offline AP firmware analysis only. No live device commands, no GCC files changed, no service actions.

## Why This Matters

The static custom image slot begins at:

- metadata/header base: `0x01300000`
- visible payload base: `0x0130000C`
- visible payload size: `108800` bytes (`320 * 170 * 2`)
- visible payload end: `0x0131A90C`

This address range is above `0x00FFFFFF`, so the AP flash path must handle addresses beyond the 24-bit SPI address limit. The firmware therefore either needs global 4-byte address mode, bank register logic, or dedicated 4-byte opcodes.

The offline dumps show that the AP firmware relies on global `0xB7` enter-4-byte-address-mode, then continues to use normal 3-byte opcodes with four address bytes.

## Key Finding

F1.2, F1.3, and F1.4 all send `0xB7` at upload-loop initialization for two SPI controller/chip selections.

F1.4 decompiled upload init:

```c
FUN_00003758(DAT_0000cf74,0x200);
FUN_0000b990(0xb7);
FUN_000038a8(uVar2,0x200);

uVar2 = DAT_0000cf78;
FUN_00003758(DAT_0000cf78,2);
FUN_0000b990(0xb7);
FUN_000038a8(uVar2,2);
```

F1.2 and F1.3 have the same pattern through their version-shifted SPI transfer helpers:

- F1.2: `FUN_0000b360(0xb7)`
- F1.3: `FUN_0000b420(0xb7)`
- F1.4: `FUN_0000b990(0xb7)`

## Opcode Usage Summary

Direct SPI-byte transfer constants found in exact dumps:

- F1.2 `FUN_0000b360`: `0`, `2`, `5`, `6`, `0x20`, `0xB7`, `0xD8`
- F1.3 `FUN_0000b420`: `0`, `2`, `5`, `6`, `0x20`, `0xB7`, `0xD8`
- F1.4 `FUN_0000b990`: `0`, `2`, `3`, `5`, `6`, `0x20`, `0xB7`, `0xD8`
- F1.4 low-level FIFO write `FUN_0000bb8e`: `0`, `0x3B`

| Purpose | F1.2 | F1.3 | F1.4 | Notes |
|---|---:|---:|---:|---|
| Enter 4-byte address mode | `0xB7` | `0xB7` | `0xB7` | Sent during upload/init for two selected SPI paths |
| Exit 4-byte address mode | not found | not found | not found | No `0xE9` evidence in the inspected dumps |
| Page program | `0x02` + 4 addr bytes | `0x02` + 4 addr bytes | `0x02` + 4 addr bytes | Relies on `0xB7` being active |
| Sector erase | `0x20` + 4 addr bytes | `0x20` + 4 addr bytes | `0x20` + 4 addr bytes | Relies on `0xB7` being active |
| Block erase | `0xD8` + 4 addr bytes | `0xD8` + 4 addr bytes | `0xD8` + 4 addr bytes | Relies on `0xB7` being active |
| Read | likely `0x03`/`0x3B` + 4 addr bytes | likely `0x03`/`0x3B` + 4 addr bytes | `0x03`/`0x3B` + 4 addr bytes | F1.4 read wrapper uses `0x3B` |
| Dedicated 4-byte page program | not found | not found | not found | No clear `0x12` command use |
| Dedicated 4-byte read | not found | not found | not found | No clear `0x13` command use |
| Dedicated 4-byte sector erase | not found | not found | not found | No clear `0x21` command use as SPI opcode |
| Dedicated 4-byte block erase | not found | not found | not found | No clear `0xDC` command use as SPI opcode |
| Status register read | `0x05` | `0x05` | `0x05` | Only WIP bit is checked |
| Write enable | `0x06` | `0x06` | `0x06` | No WEL verification found |
| JEDEC ID read | not found in exact dumps | not found in exact dumps | not found in exact dumps | No `0x9F` transfer hit |
| Electronic ID / release power-down | not found in exact dumps | not found in exact dumps | not found in exact dumps | No `0x90` / `0xAB` transfer hit |
| Status register 2 / config reads | not found in exact dumps | not found in exact dumps | not found in exact dumps | No `0x35` / `0x15` transfer hit |
| Flag status register | not found in exact dumps | not found in exact dumps | not found in exact dumps | No `0x70` transfer hit |
| Write status/config | not found in exact dumps | not found in exact dumps | not found in exact dumps | No `0x01` / `0x31` / `0x11` transfer hit |
| Volatile status write enable | not found in exact dumps | not found in exact dumps | not found in exact dumps | No `0x50` transfer hit |
| Bank register write/read | not found in exact dumps | not found in exact dumps | not found in exact dumps | No clear `0xC5` / `0x16` / `0x17` transfer hit |

Important nuance: `0x21` appears in upload-loop memory copy sizes/structure operations, but I did not find evidence that it is sent as a SPI opcode through the byte-transfer helper. The actual erase helper sends `0x20`.

## F1.4 Write Path Evidence

Page program:

```c
FUN_0000b990(2);
FUN_0000b990(param_2 >> 0x18);
FUN_0000b990((param_2 << 8) >> 0x18);
FUN_0000b990((param_2 << 0x10) >> 0x18);
FUN_0000b990(param_2 & 0xff);
while (param_3 != 0) {
  FUN_0000b990(*param_1);
  param_1 = param_1 + 1;
}
FUN_0000ba44();
return 1;
```

Sector erase:

```c
FUN_0000b990(0x20);
FUN_0000b990(param_1 >> 0x18);
FUN_0000b990((param_1 << 8) >> 0x18);
FUN_0000b990((param_1 << 0x10) >> 0x18);
FUN_0000b990(param_1 & 0xff);
iVar4 = FUN_0000ba44();
return iVar4 != 0;
```

Read wrapper:

```c
FUN_0000bb8e(iVar2,0x3b);
FUN_0000bb8e(iVar2,param_1 >> 0x18);
FUN_0000bb8e(iVar2,(param_1 << 8) >> 0x18);
FUN_0000bb8e(iVar2,(param_1 << 0x10) >> 0x18);
FUN_0000bb8e(iVar2,param_1 & 0xff);
```

## Status Poll Weakness

F1.4 status polling uses `0x05` and only checks WIP bit 0:

```c
FUN_0000b990(5);
do {
  FUN_0000c370();
  bVar4 = FUN_0000b990(0);
  if (*psVar1 < 1) {
    return 0;
  }
} while ((bVar4 & 1) != 0);
return 1;
```

Problems:

- It checks WIP only.
- It does not verify WEL after `0x06`.
- It does not read/validate protection bits.
- It does not read/validate program/erase error flags, if the actual flash chip exposes them.
- It does not read/validate whether 4-byte address mode is active.
- Page program ignores the poll return anyway and returns success if WREN returned success.

This means a page-program failure, timeout, protection refusal, or addressing-mode issue can be invisible to the host. That matches the observed host-side success with panel-side partial update.

## Relation To The 64 KB Boundary

The strongest visual/address clue remains:

- corruption boundary: around `0x01310000`
- distance from visible start: `0x01310000 - 0x0130000C = 65524` bytes
- this is almost exactly one 64 KB block from the visible payload start

So the current best model is not simply "4-byte mode absent globally." If 4-byte mode were completely absent, the whole `0x01300000` slot would likely be wrong or aliased. But the upper portion changes correctly.

More precise model:

1. The media slot requires >24-bit flash addressing.
2. AP enters global 4-byte address mode with `0xB7` at upload/init.
3. Actual writes then rely on that global state.
4. Erase/program status is weakly validated, and page-program failure is silently converted to success.
5. The lower half failure at about the 64 KB boundary suggests a block/sector/page program transition, protection window, bank/window/mapping issue, or flash status failure starting near `0x01310000`.

The address-reference scan found many explicit AP references to `0x01300000`, `0x0130000C`, `0x01320000`, and `0x0132000C`, but no explicit `0x01310000` constant in the inspected AP text dumps. That makes `0x01310000` look less like a deliberate logical slot boundary and more like an emergent flash/programming boundary inside the `0x01300000` media slot.

Known AP functions referencing media slot bases:

| Version | Parser / mapper functions | Upload loop function |
|---|---|---|
| F1.2 | `FUN_000068cc`, `FUN_00007538`, `FUN_00007968` | `FUN_0000c4dc` |
| F1.3 | `FUN_00006904`, `FUN_00007570`, `FUN_000079a0` | `FUN_0000c59c` |
| F1.4 | `FUN_00006ccc`, `FUN_00007988`, `FUN_00007db8` | `FUN_0000cb54` |

## Current Best Explanation

The AP firmware path is capable of reporting a successful upload even when lower media-slot flash pages are not actually erased/programmed.

The most likely failure class is now:

1. flash program/erase status failure hidden by AP firmware,
2. flash protection/window/bank/address-mode state affecting the media slot after roughly the first 64 KB,
3. stale panel-side media flash content being displayed because lower pages were never actually overwritten.

This fits all known facts:

- PC-side pData is correct.
- Host sends all chunks.
- F2 finalize returns success.
- Clean display/apply sequence does not fix it.
- Firmware reinstall does not erase custom media.
- Firmware updater can log `I2CIAPChangeToErase12ByteMode fail`.
- The upper portion of the image changes, while the lower portion remains black/stale.

## F1.4 Upload Loop Boundary Mechanics

F1.4 upload loop `FUN_0000cb54` has two relevant stages:

1. Erase stage:

```c
if (*DAT_0000d178 == '\x02') {
  iVar10 = FUN_0000b4d0(*DAT_0000d174 + *piVar4); // 0x10000 block erase
  if (iVar10 == 0) return;
  iVar10 = *piVar15 + 0x10000;
}
else {
  iVar10 = FUN_0000b910(*DAT_0000d174 + *piVar4); // 0x1000 sector erase
  if (iVar10 == 0) return;
  iVar10 = *piVar15 + 0x1000;
}
*piVar15 = iVar10;
```

2. Page-program stage:

```c
iVar10 = FUN_0000b6cc(DAT_0000d180,*DAT_0000d174 + *piVar4,0x100);
if (iVar10 == 0) return;
*piVar15 = *piVar15 + 0x100;
*DAT_0000d184 = *DAT_0000d184 + -1;
```

The loop has explicit slot-relative limit checks:

```c
if (slot == 2) {
  uVar13 = base + offset + 0xfece0000; // subtracts 0x01320000
}
else if (slot == 3) {
  uVar13 = base + offset + 0xfed00000; // subtracts 0x01300000
}
if (limit <= uVar13) goto main_loop;
```

This confirms that AP is intentionally treating `0x01300000` and `0x01320000` as slot bases. It does not show a special logical split at `0x01310000`.

The observed corruption boundary therefore lines up with an erase/program granularity transition inside the slot:

- sector erase increments by `0x1000`
- block erase increments by `0x10000`
- page program increments by `0x100`
- visible corruption starts around the first `0x10000` span from `0x0130000C`

This makes a failed/unverified block/sector/page transition around `0x01310000` more plausible than a wrong host payload or wrong display mode.

## What This Does Not Prove Yet

This does not yet prove which exact SPI flash chip/register bit is blocking the lower area. It also does not prove whether the AP has one physical flash chip, two chip selects, or one memory-mapped controller with two logical modes. The two `0xB7` sends strongly suggest two selected SPI paths/controllers, but the exact hardware topology still needs confirmation.
