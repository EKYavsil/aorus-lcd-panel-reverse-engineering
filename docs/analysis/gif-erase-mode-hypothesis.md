# GIF Erase Mode Hypothesis

Date: 2026-05-31

Scope: offline analysis only. No live panel command, no DLL patch, no GIF upload test.

## Question

Could the unresolved custom GIF corruption be fixed by the same one-byte change that fixed static image uploads?

Static fix:

```text
F1[0x11]: 0x02 -> 0x01
```

## Short Answer

Possibly, but it is not proven yet.

The same byte is relevant to GIF uploads because it is part of the common `F1` upload header and AP firmware decodes it as the erase granularity selector:

```text
0x02 -> 64 KB block erase
0x01 -> 4 KB sector erase
```

However, GIF upload is not the same branch as static image upload. GIF uses a different storage type and a zero target address, so the static patch must not be blindly widened to GIF without runtime evidence.

## Known GIF Header Shape

The recovered protocol maps 50-series LCD upload targets as:

| Payload kind | Target address | Storage type | Display mode |
|---|---:|---:|---:|
| Static image | `0x01300000` | `1` | `3` |
| Vendor text slot | `0x01320000` | `1` | `4` |
| GIF/RLE animation | `0x00000000` | `2` | `5` |

The upload header builder uses:

```text
F1 CB 55 AC 38
target_address: u32be
storage_type: u8
page_count: u32be
frame_count: u16be
delay_ms: u8
chunk_mode: u8
reserved: u8
```

Therefore, for a typical large GIF payload the expected high-level header shape is:

```text
F1 CB 55 AC 38 00 00 00 00 02 ... ... ... ... FF 02 00
                                      ^ storage type       ^ F1[0x11]
```

The exact page count, frame count, and delay depend on the encoded GIF/RLE payload.

## Why The Same Byte Could Matter

The public protocol implementation uses this threshold:

```text
payload <  20480 bytes -> F1[0x11] = 0x01
payload >= 20480 bytes -> F1[0x11] = 0x02
```

Static full-frame payload is `108812` bytes, so GCC selected `0x02`. That selected the 64 KB block erase path and caused the confirmed static image corruption on this panel/card state.

GIF payloads are often larger than `20480` bytes, especially multi-frame animations. If GCC emits `F1[0x11] = 0x02` for those GIF uploads, the AP likely uses the same 64 KB block erase path for GIF storage preparation.

This makes the same one-byte change a plausible candidate:

```text
large GIF upload:
    F1[0x11] = 0x02  // likely block erase

candidate test:
    F1[0x11] = 0x01  // sector erase
```

## Why It Is Not Proven

Static and GIF differ in important ways:

- Static upload enters the explicit `IMG=1` branch.
- GIF upload is `nType=0` and falls through the default branch.
- Static destination is `0x01300000`.
- GIF target address is intentionally `0x00000000`.
- GIF storage type is `2`.
- GIF payload is a multi-frame RLE container, not a single RGB565 frame.
- A public RTX 5090 implementation reports GIF/RLE animation uploads working with the recovered protocol.

The zero destination is likely not itself a bug; it matches the recovered protocol and tests from the public 5090 project.

## Current Risk Model

There are three plausible GIF failure classes:

| Hypothesis | Evidence | Test needed |
|---|---|---|
| Same erase-mode problem as static | Large GIF likely uses `F1[0x11]=0x02`, same AP block erase path | Runtime GIF trace showing F1 byte, chunk count, failures, visual boundary |
| GIF RLE/container incompatibility | Existing GIF helper experiments mention palette/LCT/GCT/full-frame differences | Offline render/parse GCC GIF payload; compare with public RLE container |
| Display-state or mode problem | GIF needs display mode `5`, loop lock, and overlay clearing | Trace post-upload `SetMode(5)`, `F3`, `E1`, `AA` behavior |

The static result makes erase-mode the first candidate, but not the only candidate.

## Minimum Evidence Before A GIF Patch

Before changing GIF behavior, collect one passive GIF trace with:

- `nType`
- `uSize`
- `pDataLen`
- `nCount`
- `nDelay`
- full `F1` raw bytes
- decoded target address
- decoded storage type
- decoded page count
- decoded frame count
- decoded delay
- decoded `F1[0x11]`
- expected erase unit
- expected erase count
- actual chunk count
- failed chunk indexes, if any
- `F2` finalize result
- post-upload mode/apply commands

The key decision point:

```text
If GIF F1[0x11] is already 0x01:
    the static one-byte fix cannot explain GIF corruption.

If GIF F1[0x11] is 0x02 and corruption starts near a 64 KB boundary:
    a guarded GIF sector-erase test becomes plausible.
```

## Candidate Test Patch Shape

Do not implement this yet. This is only the safest shape if runtime evidence supports it.

```text
if nType == 0
and F1 packet magic == F1 CB 55 AC 38
and F1 target == 0x00000000
and F1 storage_type == 0x02
and F1[0x11] == 0x02
and page_count implies payload >= 20480 bytes:
    F1[0x11] = 0x01
```

This would preserve:

- GIF `nType`
- GIF target address
- GIF storage type
- frame count
- delay
- payload bytes
- display mode
- return behavior

It changes only the erase granularity selector, mirroring the proven static fix while keeping GIF-specific fields intact.
