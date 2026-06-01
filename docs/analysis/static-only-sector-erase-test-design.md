# Historical Static-Only Sector Erase Test Design

Date: 2026-05-31

Scope: historical diagnostic design. No live deploy, no DLL swap, no panel command, no service action, and no installer script is included in this public repository.

## Purpose

This document records the diagnostic design that led to the final root-level patch builder.

The tested hypothesis was:

```text
Static upload requests 64 KB block erase via F1[0x11] = 0x02.
The second 64 KB block around 0x01310000 fails or is not programmed correctly.
Changing static-only upload to 4 KB sector erase may avoid the broken 64 KB block erase path.
```

The later public implementation of this design is:

```text
build_final_static_sector_patch.cs
StaticSectorPatcher.csproj
PATCHER.md
```

## Static F1 Header

Observed static F1 metadata:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Relevant fields:

| Offset | Byte(s) | Meaning |
|---:|---|---|
| `0x00` | `F1` | metadata opcode |
| `0x01..0x04` | `CB 55 AC 38` | magic |
| `0x05..0x08` | `01 30 00 00` | destination `0x01300000` |
| `0x09` | `01` | transfer/static mode field |
| `0x0A..0x0D` | `00 00 01 AA` | chunk count `426` |
| `0x11` | `02` | AP erase mode selector |
| `0x12` | `00` | template/extra field |

AP decode:

```text
F1[0x11] == 0x02 -> erase granularity 0x10000
F1[0x11] == 0x03 -> special path; not used for this test
otherwise             -> erase granularity 0x1000
```

## Proposed Test Change

For static image only:

```diff
- F1 ... 00 02 00
+ F1 ... 00 01 00
```

Specifically:

```text
F1[0x11]: 0x02 -> 0x01
```

Expected AP behavior:

```text
erase_granularity = 0x1000
erase_count = ((426 * 0x100) / 0x1000) + 1
            = (0x1AA00 / 0x1000) + 1
            = 26 + 1
            = 27 sector erases
```

Expected erase addresses:

```text
0x01300000
0x01301000
0x01302000
...
0x0131A000
```

Expected program addresses remain unchanged:

```text
0x01300000 .. 0x0131A9FF
```

## Strict Patch Boundary

The diagnostic design changed only the host-side construction of the F1 metadata byte that becomes offset `0x11`.

It did not change:

- destination `0x01300000`
- nType
- chunk count
- payload bytes
- pData
- F2 start/finalize
- AA/Save
- display/apply sequence
- GIF branch
- AP firmware
- raw I2C commands

## Static-Only Guard

The test patch had to apply only when all of these were true:

```text
imgData.nType == 1
SendImage local destination == 0x01300000
F1 metadata length >= 0x13
F1[0] == 0xF1
F1[1..4] == CB 55 AC 38
F1[5..8] == 01 30 00 00
F1[0x11] == 0x02
```

If any condition was false, the packet had to remain untouched.

This prevents accidental GIF/custom-video/template changes.

## Implementation Target

Preferred target:

```text
ucVga.dll
GvLcdApi.SendImage
F1 metadata construction just before SendData(F1...)
```

Patch style:

```csharp
if (staticOnlyGuard) {
    f1[0x11] = 0x01;
}
```

In IL terms, the safest target was after F1 byte array construction and before the `I2CApi.SendData` call that transmits the F1 metadata. This avoided changing upstream image conversion and avoided touching AP-side packet parsing.

## Expected Diagnostic Outcomes

### Outcome A: Image Fully Correct

Interpretation:

```text
64 KB block erase path is the primary problem.
4 KB sector erase path can refresh the lower region.
```

This was the observed result and became the basis for the public root-level patch builder.

### Outcome B: Same Upper-Only / Lower-Black Result

Interpretation:

```text
Problem is not only 64 KB block erase.
Page program, flash protection, or display readback after 0x01310000 remains suspect.
```

The next direction would have been readback or page-program status instrumentation, not more timing changes.

### Outcome C: Upload Fails / GCC Crashes / Panel Worse

Interpretation:

```text
AP parser may require erase mode 0x02 for this static path, or host-side assumptions are tighter than expected.
```

The test would have required immediate restore and redesign.

### Outcome D: Panel Shows Different Corruption Boundary

Interpretation:

```text
Erase granularity affects flash behavior, confirming storage-level involvement even if not fully fixing it.
```

## Why This Test Was Lower Risk Than Raw Erase

This test still used the official GCC/ucVga `SendImage` path:

- no raw I2C
- no unknown opcode
- no AP firmware patch
- no direct erase command invented by us
- no destination change
- no payload change

Only the erase granularity selector in an already-valid static F1 metadata packet changed from 64 KB block mode to 4 KB sector mode.

The risk was still real because it changed how AP erased the static media slot, so the final public builder keeps the change static-only and guarded.
