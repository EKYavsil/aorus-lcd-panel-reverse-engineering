# AP GIF Frame Index / State Findings

Date: 2026-06-01

Scope: offline analysis only. No live DLL deploy, no service control, no panel command, no firmware updater execution.

## Why This Pass Was Done

Static image was fixed by changing one static-only upload metadata byte:

```text
nType == 1, destination 0x01300000:
F1[0x11] 0x02 -> 0x01
```

That strongly implicated the AP firmware's 64 KB erase/program path for static uploads. GIF is more complex: small GIF payloads naturally use `F1[0x11] == 0x01`, yet the tiny two-frame GIF test still stayed black with the original DLL. This means GIF cannot be explained only by "large payload selects 64 KB erase".

The next offline question was:

```text
Is the GIF data failing to upload, or is AP playback state selecting/validating the wrong frame?
```

## Input Artifacts Reviewed

- `exports/ap_exact_function_dump_AP_F14_gif_state_slot_helpers.txt`
- `exports/ap_custom_address_xrefs_AP_F14_gif_custom_state_fields.txt`
- `exports/ap_exact_function_dump_AP_F14_gif_mode5_targeted.txt`
- `exports/gif_bin_header_parse.jsonl`
- `exports/gif_bin_rle_validate.jsonl`
- public repo docs/code under:
  - `public_repos/gigabyte-gpu-lcd`
  - `public_repos/Gigabyte-Aorus-LCD-Driver`
  - `public_repos/aorus-lcd-imger`

## Confirmed GIF Container Layout

Public repos and captured GCC payloads agree on the multi-frame container:

```text
u16 frame_count
for each frame:
  u32 frame_end_offset_minus_one
  u16 width
  u16 height
  u16 image_format
RLE frame streams
```

Header offsets:

```text
frame 0 header starts at 2
frame 0 width/height starts at 2 + 4 = 6

frame 1 header starts at 12
frame 1 width/height starts at 12 + 4 = 16

frame N width/height starts at 2 + N*10 + 4 = N*10 + 6
```

This matches the AP validator below.

## AP Frame Metadata Validator

AP function:

```text
FUN_00001c78(frame_index)
```

Relevant decompile:

```c
iVar2 = *(int *)(state + 0x50);
FUN_0000b49c(lock, 0);
iVar2 = FUN_0000b54c(&local_10, iVar2 + frame_index * 10 + 6, 4);
FUN_0000b49c(lock, 1);

if (width == 0x0140 && height == 0x00aa) {
    return 1;
}
return 0;
```

Interpretation:

- AP reads 4 bytes at `base + frame_index * 10 + 6`.
- It expects `320 x 170`.
- `frame_index == 0` validates frame 0.
- `frame_index == 1` validates frame 1.

This is internally consistent with the public/GCC container layout.

## AP Custom Animation Display Helper

AP function:

```text
FUN_0000982c()
```

Relevant decompile:

```c
if (*(state + 0x47) != 1) {
    if (*(state + 0x19) == 1) {
        *display_base = 0;
    }
    else if (*(state + 0x19) == 2) {
        *display_base = 0x01000000;
    }

    *(state + 0x10) = 2;
    FUN_00009b90();
    FUN_00002cc0();

    ok = FUN_00001c78(*(state + 0x11));
    if (ok == 0) {
        *(state + 0x47) = 1;
        return;
    }

    FUN_00009bf6(*(state + 0x11), 0, 0x23);

    if ((ushort)*(byte *)(state + 0x18) <= *(ushort *)(state + 0x64)) {
        next = *(state + 0x11) + 1;
        *(state + 0x11) = next;

        if (*(ushort *)(state + 0x66) <= (ushort)next) {
            *(state + 0x11) = 1;
            *(state + 0x12) = 1;
        }

        *(ushort *)(state + 0x64) = 0;
    }
}
```

Field interpretation:

| Field | Meaning |
|---|---|
| `state+0x19` | custom slot selector; value `2` selects base `0x01000000` |
| `state+0x47` | fail/disable latch; when set to `1`, display helper exits immediately |
| `state+0x11` | current animation frame index |
| `state+0x18` | frame delay/timing threshold |
| `state+0x64` | frame timer/counter |
| `state+0x66` | frame count / terminal index |

## New Critical Finding: AP Starts/Resets Custom GIF Frame Index To 1

Two places in the AP state path explicitly set:

```c
*(state + 0x11) = 1;
```

The display helper also wraps back to `1`, not `0`:

```c
if (frame_count <= next) {
    state[0x11] = 1;
    state[0x12] = 1;
}
```

This means the AP custom animation path is very likely 1-based during playback:

```text
first displayed frame = frame index 1
frame index 0 is skipped in normal playback
wrap target = 1
```

For a two-frame container where `frame_count == 2`:

```text
display index 1
advance to 2
2 <= frame_count, so reset to 1
display index 1 again
```

So a two-frame animation may only ever display frame 1.

## Stronger Evidence From Frame Stream Address Calculation

AP function:

```text
FUN_000068c4(frame_index, frame_context)
```

This helper reads frame headers before drawing/decompressing a frame.

Relevant decompile:

```c
if (frame_index == 0) {
    read_address = base + 2;
}
else {
    read_address = base + frame_index * 10 - 8;
}

FUN_0000b54c(frame_context->header_buffer, read_address, 0x14);
```

For `frame_index == 1`:

```text
read_address = base + 1*10 - 8 = base + 2
```

That reads 20 bytes:

```text
base + 2  ... base + 11  = frame 0 header
base + 12 ... base + 21  = frame 1 header
```

Then AP function:

```text
FUN_00006990(frame_index, frame_context)
```

uses those headers differently depending on whether `frame_index` is zero.

Relevant decompile:

```c
if (frame_context->current_address == 0) {
    header = frame_context->header_buffer;

    if (frame_index == 0) {
        width  = header[4..5];
        height = header[6..7];
        current_address = base + 2;
        end_address = header0.end + 1;
    }
    else {
        width  = header[14..15];
        height = header[16..17];
        current_address = header0.end + 1;
        end_address = header1.end + 1;
    }
}
```

For `frame_index == 1`, the AP therefore does this:

```text
width/height = frame 1 width/height
stream start = frame 0 end + 1
stream end   = frame 1 end + 1
```

That is exactly the second frame's RLE stream.

This removes the main ambiguity: AP does not merely validate frame 1 while secretly drawing frame 0. It calculates the data span for frame 1.

Practical consequence:

```text
state+0x11 == 1 means "draw the second frame stream".
state+0x11 == 0 would mean "draw the first frame stream".
```

Since the custom animation helper initializes/wraps to `1`, normal GIF playback appears to skip frame 0.

One nuance: another initialization/display path (`FUN_00006888()` / `FUN_00006bf4()`) may preload or draw an initial frame before the custom animation loop takes over. That could explain why some animation paths briefly show something during loading or mode transition. The persistent playback helper still uses `state+0x11` and wraps to `1`, so the stable post-upload display can still skip frame 0 even if frame 0 was touched during startup.

## Why The Tiny GIF Stayed Black

The tiny test payload decoded offline as:

```text
frame 0: light/white image, valid RLE, 320x170
frame 1: black image, valid RLE, 320x170
```

Original DLL + no script produced:

```text
panel did not lock
upload/loading path appeared normal
final output stayed black
```

That result is now consistent with AP playback selecting only frame index `1`. It is no longer strong evidence that tiny GIF upload failed.

In other words:

```text
Tiny GIF black result may be a frame-index/playback-state result, not a transport or RLE failure.
```

The stronger frame-stream calculation confirms this is not just a visual coincidence:

```text
two-frame tiny GIF:
  AP current frame index starts at 1
  frame index 1 maps to second stream
  second stream is black
  wrap condition sends index back to 1
  result can remain black forever
```

## Relationship To 64 KB Erase Problem

This does not invalidate the 64 KB erase finding.

Static image:

- affected by `F1[0x11] == 0x02`
- fixed by static-only `0x02 -> 0x01`
- root cause remains AP 64 KB block erase path / ignored status propagation.

Large GIF:

- still likely affected by the same low-level 64 KB erase/program blind spot when payload is large enough.
- but GIF also depends on AP playback state fields:
  - `state+0x11`
  - `state+0x18`
  - `state+0x19`
  - `state+0x47`
  - `state+0x66`

Therefore the GIF bug may be a combination of:

```text
large payload corruption risk from 64 KB path
+ custom animation playback starts at frame 1
+ fail latch if selected frame metadata is stale/invalid
+ timing/finalization semantics tied to F1[0x11] == 0x02
```

## Status / Protection Search Result

In the visible AP F1.4 dump, the SPI helper search found:

```text
FUN_0000b990(5)  // read status
FUN_0000b990(6)  // write enable
```

No obvious visible `FUN_0000b990(0x01)` write-status-register path was found in this search pass. Also no obvious `0x04`, `0x35`, `0x50`, or block-protect clearing path was confirmed.

Interpretation:

```text
If flash block-protect/status-register state contributes to failures, this AP media upload path does not obviously clear or manage it in the reviewed dump.
```

This is weaker than the frame-index and ignored-status findings, but it remains relevant to explain why 64 KB erase can silently fail.

## What This Means For The Next Step

Do not repeat the unsafe GIF state-prepare patch. It touched mode/template state before upload and caused panel corruption.

The next safest investigation should be offline first:

1. Confirm all writes to `state+0x11` in AP F1.2/F1.3/F1.4.
2. Confirm whether `state+0x66` stores actual frame count or last valid index.
3. Confirm whether GCC sends frame count as actual count, while AP expects terminal index/count-plus-one in this playback branch.
4. Build offline candidate payloads only, without live deployment:
   - duplicate visible frame at index 1
   - add a dummy frame 0
   - set frame count to `actual_frames + 1` only if AP evidence supports it
5. If a live test is later approved, use original DLL and a very small GIF where:
   - frame 0 is black/dummy
   - frame 1 is unmistakably visible
   - payload remains below 20 KB so GCC naturally uses `F1[0x11] == 0x01`
   - no state patch, no mode preconditioning, no F1 mutation

## Current Best Explanation

Static and GIF now appear to have different dominant failure layers:

```text
Static custom image:
  primary confirmed issue = AP 64 KB erase/program path
  practical fix = static-only F1[0x11] 0x02 -> 0x01

GIF:
  not solved by copying static byte change
  tiny-GIF black result is consistent with AP displaying frame index 1 only
  large-GIF corruption may combine frame-index/playback state with 64 KB erase failures
```

The highest-value next offline target is AP custom animation indexing:

```text
state+0x11 current frame index
state+0x66 frame count / terminal index
FUN_00001c78 metadata validation
FUN_00009bf6 frame draw/read address calculation
```

## Revised Safest Future Test Design

If a future live test is approved, the safest diagnostic test is not another state patch. It should be a payload-only test through the original DLL:

```text
GIF A:
  frame 0 = black/dummy
  frame 1 = bright white or obvious color
  frame 2 = another obvious color
  payload < 20 KB so F1[0x11] naturally stays 0x01
  no DLL mutation except whatever is strictly needed to select the file
  no pre-SetMode/SetImageTpl state patch
```

Expected results:

| Result | Meaning |
|---|---|
| frame 1 visible / animation between frame 1..N-1 | AP 1-based playback hypothesis confirmed |
| still black | state latch/template/mode or frame validation still failing |
| panel locks/corrupts | payload shape still violates an AP playback assumption |

This should be treated as a diagnostic payload test, not a permanent fix.


