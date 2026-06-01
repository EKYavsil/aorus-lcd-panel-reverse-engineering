# AP F1 Header To Erase Mode Analysis

Date: 2026-05-31

Scope: offline AP firmware analysis only. No live device access, no service actions, no DLL/firmware changes.

## Static F1 Header Observed

Known static upload F1 metadata:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Decoded by host trace:

- destination: `0x01300000`
- nType/static path: `1`
- chunk count: `0x01AA = 426`
- packet/page size: `0x100`
- payload bytes implied by chunks: `426 * 256 = 109056`

This matches the static upload path that visibly updates the upper portion of the panel.

## AP Parser Decode

F1.4 AP parser branch:

```c
if (*DAT_000081cc == 0xf1) {
  uVar24 = DAT_000081cc[8] |
           pbVar7[5] << 0x18 |
           pbVar7[6] << 0x10 |
           pbVar7[7] << 8;
  *DAT_00008204 = uVar24;

  bVar30 = pbVar7[0x11];
  *DAT_00008208 = bVar30;

  if (bVar30 == 3) {
    uVar27 = 1;
  }
  else if (bVar30 == 2) {
    uVar27 = 0x10000;
  }
  else {
    uVar27 = 0x1000;
  }

  uVar31 = DAT_000081cc[0xd] |
           pbVar7[10] << 0x18 |
           pbVar7[0xb] << 0x10 |
           pbVar7[0xc] << 8;
  *DAT_0000820c = uVar31;

  if (bVar30 == 3) {
    uVar26 = 1;
  }
  else {
    uVar26 = (short)((uVar31 << 8) / uVar27) + 1;
  }
  *DAT_00008210 = uVar26;
}
```

For the static header:

| F1 field | Bytes | Decoded value | Meaning |
|---|---:|---:|---|
| opcode/magic | `F1 CB 55 AC 38` | valid F1 header | upload metadata |
| destination | `01 30 00 00` | `0x01300000` | static custom media slot |
| byte `0x09` | `01` | `1` | normal transfer mode path |
| chunk count | `00 00 01 AA` | `426` | 256-byte pages/chunks |
| byte `0x11` | `02` | `2` | erase granularity mode |

Critical result:

```text
F1 byte[0x11] = 0x02 -> AP erase granularity = 0x10000
```

So static uploads are not using 4 KB sector erase. They intentionally request 64 KB block erase.

## RAM State Mapping

Ghidra DAT pointer dump confirms that the parser writes the same RAM locations consumed by the upload loop.

| Parser label | RAM address | Upload-loop label | Meaning |
|---|---:|---|---|
| `DAT_00008204` | `0x2000030C` | base/destination input | F1 destination, e.g. `0x01300000` |
| `DAT_00008208` | `0x20000314` | `DAT_0000d178` | erase mode / granularity selector |
| `DAT_0000820C` | `0x20000318` | `DAT_0000d184` | chunk/page remaining count |
| `DAT_00008210` | `0x20000316` | `DAT_0000d16C` | erase operation count |
| `DAT_0000823C` | `0x20000310` | `DAT_0000d174` | current erase/program offset |
| `DAT_00008240` | `0x20000307` | `DAT_0000d160` | erase state flag |
| `DAT_00008248` | `0x20000308` | `DAT_0000d168` | timing/threshold value |

This ties the host F1 metadata directly to AP-side erase/program behavior.

## Static Header Math

Static header chunk count:

```text
chunks = 0x01AA = 426
bytes  = 426 * 0x100 = 0x1AA00 = 109056
```

Erase mode from F1 byte `0x11`:

```text
erase_mode = 2
erase_granularity = 0x10000
```

AP erase count calculation:

```text
erase_count = ((chunks << 8) / 0x10000) + 1
            = (0x1AA00 / 0x10000) + 1
            = 1 + 1
            = 2
```

Expected erase addresses:

```text
0x01300000
0x01310000
```

Expected page-program addresses:

```text
0x01300000 .. 0x0131A9FF  (426 pages of 0x100 bytes)
```

The observed corruption boundary is exactly the second erase block:

```text
0x01310000 - 0x0130000C = 65524 bytes
```

That is why the symptom appears as upper roughly 60% updated and lower roughly 40% black/stale.

## Upload Loop Behavior

F1.4 upload loop erase stage:

```c
if (*DAT_0000d178 == '\x02') {
  iVar10 = FUN_0000b4d0(*DAT_0000d174 + *piVar4); // 64 KB block erase
  if (iVar10 == 0) {
    return;
  }
  *DAT_0000d174 += 0x10000;
}
else {
  iVar10 = FUN_0000b910(*DAT_0000d174 + *piVar4); // 4 KB sector erase
  if (iVar10 == 0) {
    return;
  }
  *DAT_0000d174 += 0x1000;
}
```

F1.4 page-program stage:

```c
iVar10 = FUN_0000b6cc(DAT_0000d180,*DAT_0000d174 + *piVar4,0x100);
if (iVar10 == 0) {
  return;
}
*DAT_0000d174 += 0x100;
*DAT_0000d184 -= 1;
```

Important version difference from earlier analysis:

- F1.2/F1.3 erase/program helpers have weak or absent return propagation.
- F1.4 improves erase return propagation.
- F1.4 page-program still ignores the internal status-poll return and can return success after a failed/timed-out program.

So a failure at or after `0x01310000` can still plausibly be reported as a successful upload, especially on older AP firmware or if the failing part is page program rather than block erase.

## What This Explains

This is now the cleanest explanation for the exact visual shape:

1. Host sends valid static payload.
2. Host asks AP to write `426` pages to `0x01300000`.
3. F1 byte `0x11 = 0x02` tells AP to use 64 KB erase blocks.
4. AP should erase two blocks: `0x01300000` and `0x01310000`.
5. The upper portion changes, proving the first block path works.
6. The lower portion stays black/stale, matching failure around the second 64 KB block.

This makes `0x01310000` the primary suspect boundary.

## Current Best Root-Cause Hypothesis

The problem is likely not F1 destination, not pData, not chunk count, and not display apply.

The strongest current hypothesis is:

```text
AP static upload selects 64 KB block erase mode.
The second block at 0x01310000 is not being erased and/or programmed correctly.
The AP/host success path does not reliably detect that failure.
```

Possible lower-level reasons:

- SPI block erase at `0x01310000` fails but failure is hidden or not propagated.
- SPI page program begins failing after the first 64 KB block but returns success.
- flash protection/window/address-mode state affects the second block.
- the block is worn/stuck/protected at panel-side flash level.

## Practical Implication

The most useful next test direction is not more delay or display apply logic.

The next meaningful direction is to compare 64 KB block erase mode against 4 KB sector erase mode, but this must be treated as a patch-plan only until explicitly approved.

The relevant host-side field appears to be F1 metadata byte `0x11`:

```text
0x02 -> 64 KB block erase
other non-3 value -> 4 KB sector erase
0x03 -> special/no-normal-size mode
```

If a future controlled static-only test changes only this metadata field from `0x02` to a sector-erase-compatible value, and the lower half starts updating, that would strongly confirm the second 64 KB block erase path as the real fault.

No live test was performed in this analysis.

