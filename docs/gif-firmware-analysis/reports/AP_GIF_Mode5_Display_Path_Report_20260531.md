# AP GIF Mode 5 Display Path Report - 2026-05-31

Scope: offline AP firmware Ghidra analysis only. No live panel command, no DLL deploy, no service control, no firmware updater execution.

## New Targeted Exports

Generated under:

```text
<local-gif-investigation-dir>\exports
```

Files:

- `ap_exact_function_dump_AP_F14_gif_mode5_targeted.txt`
- `ap_exact_function_dump_AP_F14_gif_display_deep.txt`
- `ap_exact_function_dump_AP_F14_gif_frame_read_helpers.txt`
- `ap_exact_function_dump_AP_F14_gif_address_decode_helpers.txt`

These were generated from the existing AP F1.4 Ghidra project, with outputs written only to the isolated GIF investigation folder.

## Mode 5 Dispatcher

The AP mode dispatcher function `FUN_00002034()` contains the GIF/display mode case:

```c
case 5:
    *(undefined1 *)(state + 0x10) = 1;
    *DAT_00002368 = DAT_00002364;
    *DAT_00002370 = DAT_0000236c;
    FUN_00009bb8();
    FUN_00001e14();
    return;
```

This confirms mode `5` is a distinct display path. It is not static mode with a different slot.

## Mode 5 Wrapper

`FUN_00009bb8()` is a tiny wrapper:

```c
bool FUN_00009bb8(void)
{
    int ok = FUN_00003ce8();
    return ok != 0;
}
```

So the useful GIF display work is in `FUN_00003ce8()`.

## GIF Frame Display Loop

`FUN_00003ce8()`:

```c
undefined4 FUN_00003ce8(void)
{
    if (FUN_00006bf4() != 0) {
        *DAT_00003d18 = 1;
        *DAT_00003d1c = 0;
        do {
            if (*DAT_00003d18 <= *(byte *)(state + 1)) {
                *(byte *)(state + 1) = 0;
                return 1;
            }
        } while (FUN_00006ccc() != 0);
    }
    return 0;
}
```

Interpretation:

- `FUN_00006bf4()` prepares/loads an initial GIF/display page.
- `FUN_00006ccc()` repeatedly renders/streams additional blocks.
- `state+1` behaves like a frame/page counter.

## Address/Page Advancement

`FUN_00006aa0()` controls the read window:

```c
if (*(int *)(displayState + 0x78) == 0) {
    *(displayState + 0x78) = *(gifState + 0x48);
    *(displayState + 0x74) = *(gifState + 0x4c);
}
else {
    *(displayState + 0x78) += 0x500;
    if (*(displayState + 0x78) == *(gifState + 0x4c)) {
        gifState[0x18] = 1;
        gifState[1]++;
    }
    gifState[0] = 1 - gifState[0];
}
```

Key finding:

```text
GIF display reads advance in 0x500-byte units.
gifState+0x48 is the start address.
gifState+0x4c is the end address.
```

This is now one of the most important targets:

```text
Where are gifState+0x48 and gifState+0x4c initialized after GIF upload/finalize?
```

If those bounds are stale, truncated, or mismatched to the uploaded GIF payload, the mode-5 reader can display old/corrupt frames even when host upload transport succeeded.

## SPI Read Helper

`FUN_0000b760()` is the low-level flash read helper used by these display paths.

It checks:

```c
if (param_1 > 0x03ffffff) return 0;
if (param_3 > 0x3fff) return 0;
```

It then sends SPI command:

```text
0x3B
addr[31:24]
addr[23:16]
addr[15:8]
addr[7:0]
dummy 0x00
```

Interpretation:

```text
0x3B = fast read
4-byte address is used
max read length per call is 0x3fff
```

This aligns with earlier SPI 4-byte address findings and confirms display mode 5 reads from external flash, not from a Windows/GCC cache.

## Initial GIF Read

`FUN_00006bf4()` does:

```c
displayState+0x78 = 0;
FUN_00006aa0();
FUN_0000b6a0();
FUN_0000b760(displayState+0x78, buffer + pingpongIndex * 0x500, 0x500, 1);
FUN_0000b9c4();
```

Because `FUN_00006aa0()` sets `displayState+0x78` from `gifState+0x48` when it is zero, the first actual read address is:

```text
gifState+0x48
```

not necessarily host F1 target `0`.

This matters because the AP parser previously showed:

```c
if ((cVar25 == 1) && (F1 target == 0)) {
    upload_base = 0x01000000;
}
```

So the real animation flash base may be `0x01000000`, while host-visible GIF target remains `0x00000000`.

## RLE/Frame Decode Path

`FUN_0000ca28()` is an RLE decode helper. It matches the corrected public/GCC RLE model:

```text
u16 count
if count & 0x8000:
    repeat one RGB565 pixel count & 0x7fff times
else:
    copy literal pixels
```

This supports the corrected offline conclusion:

```text
The panel/AP expects the same high-bit RLE format we validated offline.
The old end-of-row interpretation was wrong.
```

## GIF State Counters

`FUN_00001e14()` updates per-mode counters for modes:

```text
0x05
0x07
0x0B
```

For mode `5`, it advances counters at offsets:

```text
state+0x6e / +0x70 / +0x72
state+0x74 / +0x76 / +0x78
state+0x7a / +0x7c / +0x7e
```

This strengthens the model that GIF playback depends on AP-side runtime counters and not just raw display mode.

## Updated Diagnosis

The GIF issue should now be treated as an AP animation read/write state mismatch:

```text
upload path:
    F1 target 0 -> slot 1 -> possibly upload base 0x01000000
    storage type 2 -> frame count/delay active

display path:
    mode 5 -> gifState+0x48 / +0x4c bounds
    reads 0x500-byte blocks from external flash via SPI 0x3B
    decodes RLE through AP-side decoder
    advances playback counters through FUN_00001e14
```

The most important unknown is now:

```text
Does GIF upload/finalize correctly set gifState+0x48 and gifState+0x4c to match the newly uploaded animation?
```

If those fields remain stale or refer to a partially old animation range, the panel can show mixed old/new/broken frames even if the host sends all chunks.

## Why The Static Fix Does Not Transfer

Static fix:

```text
slot 3, destination 0x01300000, storage 1, single frame
F1[0x11] 0x02 -> 0x01 avoids bad 64 KB block erase
```

GIF path:

```text
slot 1, host target 0, storage 2, mode 5, multi-frame RLE
F1[0x11] 0x02 also sets a timing threshold
display reads use gifState+0x48/+0x4c, not host target directly
```

So GIF cannot be fixed safely by copying the static byte patch.

## Next Offline Step

Find all writes to the GIF state fields:

```text
gifState+0x48
gifState+0x4c
displayState+0x78
displayState+0x74
storage type 2 frame count/delay fields
```

The next targeted Ghidra task should be a data-reference/write-reference dump for the DAT pointers behind `FUN_00006aa0()` and `FUN_00002034()` state structures.

No live experiment is justified yet.



