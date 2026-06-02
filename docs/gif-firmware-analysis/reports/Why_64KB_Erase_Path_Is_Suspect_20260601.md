# Why The 64 KB Erase Path Is Suspect - 2026-06-01

Scope: offline analysis only. No live DLL deploy, no service action, no firmware updater execution, no panel I2C command.

## Current Working Position

The next research focus should be the AP firmware upload/erase/program path selected by:

```text
F1[0x11] == 0x02
```

This path matters for both issues:

- static image was fixed by avoiding it;
- normal-sized GIFs usually use it.

However, the static fix must not be copied blindly to GIF because GIF uses additional animation timing and commit state.

## Evidence From Static Image

The successful static fix changed exactly one byte:

```text
F1[0x11]: 0x02 -> 0x01
```

Everything else stayed the same:

- destination `0x01300000`
- static `nType=1`
- chunk count `426`
- payload bytes
- F2 start/finalize
- display/apply flow
- no raw I2C
- no firmware change

Original static packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Patched static packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

Result:

```text
Full 320x170 static image wrote correctly.
The lower black/stale region disappeared.
```

This is strong evidence that the static payload/converter/display-state path was not the root problem. The root behavior changed only when the AP erase/program path changed.

## AP F1 Parser Meaning

The AP parser decodes `F1[0x11]` into an erase/program mode field:

```c
bVar30 = F1[0x11];

if (bVar30 == 3) {
    erase_granularity = 1;
}
else if (bVar30 == 2) {
    erase_granularity = 0x10000;
}
else {
    erase_granularity = 0x1000;
}
```

Meaning:

```text
0x02 -> 64 KB block erase
0x01 -> 4 KB sector erase
0x03 -> special byte/one-operation path, not normal media upload
```

For the original static upload:

```text
destination       = 0x01300000
chunk count       = 426
programmed bytes  = 426 * 256 = 0x1AA00
erase mode        = 0x02
erase unit        = 0x10000
erase count       = (0x1AA00 / 0x10000) + 1 = 2
erase addresses   = 0x01300000, 0x01310000
```

The visual corruption boundary aligns with:

```text
0x01310000 - 0x0130000C = 65524 bytes
```

That is almost exactly the first 64 KB boundary after the static container header.

## AP Flash Helpers

Relevant AP helper mapping:

| Operation | Function | SPI opcode |
|---|---:|---:|
| 64 KB block erase | `FUN_0000b4d0(address)` | `0xD8` |
| 4 KB sector erase | `FUN_0000b910(address)` | `0x20` |
| 256-byte page program | `FUN_0000b6cc(staging,address,0x100)` | `0x02` |
| SPI status poll | `FUN_0000ba44()` | `0x05` |

The upload loop selects erase helper based on `F1[0x11]`:

```c
if (*DAT_0000d178 == 0x02) {
    iVar10 = FUN_0000b4d0(base + offset);  // 64 KB block erase
    if (iVar10 == 0) return;
    offset += 0x10000;
}
else {
    iVar10 = FUN_0000b910(base + offset);  // 4 KB sector erase
    if (iVar10 == 0) return;
    offset += 0x1000;
}
```

After erase, page programming is always 256-byte pages:

```c
iVar10 = FUN_0000b6cc(staging, base + offset, 0x100);
if (iVar10 == 0) return;
offset += 0x100;
remaining_pages -= 1;
```

## Critical Failure Propagation Difference

The AP status poll helper has a real timeout:

```c
*timeout = 300;
send 0x05 read-status;
loop until status bit0 clears;
return 0 on timeout;
return 1 on ready;
```

4 KB sector erase propagates that status:

```c
iVar4 = FUN_0000ba44();
return iVar4 != 0 ? 1 : 0;
```

64 KB block erase does not propagate it in the same way:

```c
send 0xD8 block erase;
FUN_0000ba44();
return 1;
```

Page program also ignores the poll result:

```c
send 0x02 page program;
FUN_0000ba44();
return 1;
```

This is a major AP firmware blind spot:

```text
The AP can timeout/fail internally but still report success to the upload state machine.
```

This explains why the host can see:

```text
F1 success
all chunks success
F2 finalize success
SendImage True
```

while the actual flash contents after the 64 KB boundary remain stale or black.

## Why Static 4 KB Sector Mode Worked

With `F1[0x11]=0x01`:

```text
erase unit  = 0x1000
erase count = 27
addresses   = 0x01300000, 0x01301000, ..., 0x0131A000
```

This avoids the 64 KB block erase helper and uses the 4 KB sector helper, whose status propagation is stronger in the F1.4/modern AP analysis.

That does not prove the flash chip is physically bad. It proves the 64 KB path is unsafe for this custom static upload workflow.

## Plausible Root Causes For The 64 KB Path

The strongest candidates are:

1. `0xD8` block erase at `0x01310000` times out or fails, but `FUN_0000b4d0()` returns success anyway.
2. Page program after `0x01310000` times out or fails, but `FUN_0000b6cc()` returns success anyway.
3. The flash chip has protection/status-register state that affects the second 64 KB block or that specific region.
4. AP scheduling/timing around the second 64 KB block is wrong.
5. Display/readback path after the 64 KB boundary is stale/cached, but this is weaker because 4 KB erase fixed the static symptom without changing display flow.

Current strongest explanation:

```text
64 KB block erase/page-program status is not reliably propagated, and the custom media upload state machine treats a failed or timed-out flash operation as successful.
```

## Why Firmware Reinstall Did Not Prove A Wipe

Firmware reinstall/update does not appear to guarantee a custom media storage wipe.

The static custom slot is:

```text
0x01300000 .. approximately 0x0131A90C
```

The firmware updater/IAP path updates AP firmware/code/config. Existing offline evidence does not show a guaranteed erase over the custom media slot range.

That matches the observed behavior:

```text
firmware update can revive/reinitialize the panel,
but stale or corrupted custom media content can persist.
```

## Relationship To GIF

Normal GIF upload differs from static:

```text
target      = 0x00000000
storage     = 2
AP slot     = 1
display     = mode 5 animation path
payload     = multi-frame RLE container
```

For normal-sized GIF payloads above 20480 bytes, GCC naturally emits:

```text
F1[0x11] = 0x02
```

So GIF likely uses the same 64 KB erase/program machinery. That can explain corruption risk.

But `F1[0x11]=0x02` has a second meaning on GIF:

```c
if (F1[0x11] == 0x02) {
    timing_threshold = erase_count * 3000 + 3;
}
```

This timing threshold participates in the AP animation finalization/commit path.

Therefore:

```text
Static: changing 0x02 -> 0x01 avoids the bad 64 KB path and is safe for static.
GIF: changing 0x02 -> 0x01 also removes/changes animation timing/finalization state and can corrupt the panel.
```

This matches the failed GIF test.

## Why Natural Small GIF With Chunk Mode 1 Still Stayed Black

The tiny GIF test used a valid small `animation.bin`:

```text
length = 1390
frame_count = 2
natural chunk mode = 1
frame 0 = light/white
frame 1 = black
```

Original DLL/no-script upload did not lock the panel, and GCC did not log a send failure. The panel stayed black.

This means GIF is not only a 64 KB erase problem. At least one additional GIF-specific issue remains:

- animation playback timer not starting;
- custom animation state not committed;
- panel stuck on last/black frame;
- mode 5 display path pointing to the wrong animation base;
- stale `state+0x19`, `state+0x47`, `state+0x66`, or `state+0x18`.

## Updated Research Direction

Priority 1: Understand the 64 KB path deeply enough to report the root bug to GIGABYTE.

Offline tasks:

1. Inspect `FUN_0000b4d0()` 64 KB block erase and prove whether `FUN_0000ba44()` return is ignored.
2. Inspect `FUN_0000b6cc()` page program and prove whether `FUN_0000ba44()` return is ignored.
3. Compare the 4 KB sector helper `FUN_0000b910()` against 64 KB helper `FUN_0000b4d0()`.
4. Search for status-register write/protection logic:
   - `0x01` write status register
   - `0x06` write enable
   - `0x04` write disable
   - block protect bits
5. Search for any read-after-write verification path. Current evidence says none exists for media uploads.
6. Map whether GIF slot 1 eventually uses the same low-level `0xD8`/`0x02` helpers, and whether it has a different base/window limit.

Priority 2: Understand why GIF chunk mode 1 is insufficient.

Offline tasks:

1. Compare GCC-generated `animation.bin` against public-driver generated containers for the same frames.
2. Avoid black-only final frames in the next synthetic GIF, because a stuck final black frame is visually ambiguous.
3. Trace or statically map AP custom animation state:
   - `state+0x19`
   - `state+0x1a`
   - `state+0x1b`
   - `state+0x47`
   - `state+0x66`
   - `state+0x18`
4. Determine what host command or AP transition selects custom GIF base `0x01000000` instead of built-in mode-5 range.

## Current Decision

Do not run more live GIF tests yet.

The next useful work is offline AP firmware analysis focused on:

```text
64 KB block erase helper
page-program helper
status-poll propagation
GIF slot 1 animation commit state
```

The strongest reportable GIGABYTE bug for static is already:

```text
GCC requests 64 KB erase mode for static custom media uploads, but the AP firmware/block erase or page-program path can silently fail around the second 64 KB block. The host still reports success because AP status-poll failures are not reliably propagated. Forcing 4 KB sector erase for the static slot fixes the visible corruption.
```


