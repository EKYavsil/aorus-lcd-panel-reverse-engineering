# AP GIF State Machine Field Map

Date: 2026-06-01

Scope: offline AP firmware analysis only. No live DLL deploy, no service control, no panel command, no firmware updater execution.

## Purpose

After static image was fixed by avoiding the AP 64 KB erase path, GIF remained unresolved. The latest evidence shows that GIF is not only an erase problem: AP custom animation playback has its own state machine, and the previous tiny GIF black result can be explained by frame index handling.

This report maps the AP GIF state fields that matter most before any further live experiment.

## F1 Parser Inputs

The AP `F1` parser decodes the upload header:

```text
F1 CB 55 AC 38
target_address: u32be
storage_type: u8
page_count: u32be
frame_count: u16be
delay_ms: u8
chunk_mode / erase selector: u8
reserved: u8
```

Relevant parser behavior:

```c
bVar30 = F1[0x11];

if (bVar30 == 3) {
    erase_unit = 1;
}
else if (bVar30 == 2) {
    erase_unit = 0x10000;
}
else {
    erase_unit = 0x1000;
}

if (storage_type == 2) {
    saved_frame_count = F1[0x0f..0x10];
    saved_delay       = F1[0x10];
}

if (bVar30 == 2) {
    timing_threshold = erase_count * 3000 + 3;
}
```

Key interpretation:

- `F1[0x11] == 0x02` selects 64 KB block erase.
- `F1[0x11] == 0x02` also populates a timing/finalization threshold.
- This is why copying the static `0x02 -> 0x01` fix directly to GIF is unsafe.

## Important State Fields

| Field | Current interpretation | Evidence |
|---|---|---|
| `state+0x11` | current animation frame index | used by `FUN_0000982c`, passed to validator and draw helper |
| `state+0x12` | wrap/loop marker | set to `1` when frame index wraps |
| `state+0x18` | frame delay / timing threshold | copied from upload delay source after finalize gate |
| `state+0x19` | custom media slot selector | `1` selects base `0`, `2` selects base `0x01000000` |
| `state+0x1a` | custom slot 2 state/ready marker | set to `2` after finalize when `state+0x19 == 2` |
| `state+0x1b` | custom slot 1 state/ready marker | set to `2` after finalize when `state+0x19 == 1` |
| `state+0x2e` | upload/finalize state | set to `2` after delayed finalize gate |
| `state+0x32` | upload loop/run flag | cleared after delayed finalize gate |
| `state+0x3d` | refresh/apply dirty flag | set after finalize |
| `state+0x3f` | display/config dirty flag | set after finalize |
| `state+0x47` | GIF fail/disable latch | if `1`, custom animation helper exits immediately |
| `state+0x64` | frame timer/counter | compared against `state+0x18` |
| `state+0x66` | frame count / terminal index | copied from upload frame-count source after finalize gate |
| `state+0x68` | pending/upload delay source | copied into `state+0x18` |
| `state+0x6a` | pending/upload frame-count source | copied into `state+0x66` |

## Upload Finalize Gate

After upload chunks and finalization, AP does not immediately make every GIF state field active. It waits for a timing gate:

```c
if (upload_active && elapsed >= timing_threshold) {
    state+0x66 = state+0x6a;  // active frame count
    state+0x18 = state+0x68;  // active delay
    state+0x2e = 2;
    state+0x32 = 0;
    state+0x3d = 1;
    state+0x3f = 1;

    if (state+0x19 == 1) {
        state+0x1b = 2;
    }
    else if (state+0x19 == 2) {
        state+0x1a = 2;
    }
}
```

This is a strong reason the GIF path is fragile:

- Small GIF with `F1[0x11] == 0x01` may not use the same timing threshold as large GIF.
- Large GIF with `F1[0x11] == 0x02` uses risky 64 KB erase but also gets the long finalize threshold.
- Manual state preparation can create impossible combinations and corrupt panel state.

## Fail Latch

Startup/config path can set:

```c
if (FUN_0000b410(2) != 0 && state+0x45 == 0) {
    state+0x47 = 1;
}
```

Custom animation display helper starts with:

```c
if (state+0x47 == 1) {
    return;
}
```

Frame validation failure also sets:

```c
if (FUN_00001c78(state+0x11) == 0) {
    state+0x47 = 1;
    return;
}
```

Meaning:

```text
Once state+0x47 is latched, GIF playback can silently stop even if upload succeeded.
```

No safe host-side readback for this latch has been found.

## Frame Index Behavior

Custom animation helper:

```c
ok = FUN_00001c78(state+0x11);
if (!ok) {
    state+0x47 = 1;
    return;
}

FUN_00009bf6(state+0x11, 0, 0x23);

if (state+0x18 <= state+0x64) {
    next = state+0x11 + 1;
    state+0x11 = next;
    if (state+0x66 <= next) {
        state+0x11 = 1;
        state+0x12 = 1;
    }
    state+0x64 = 0;
}
```

Important:

```text
wrap target = 1, not 0
```

Frame stream addressing confirms this:

```c
if (frame_index == 0) {
    read_address = base + 2;
}
else {
    read_address = base + frame_index * 10 - 8;
}
```

For `frame_index == 1`, AP reads frame 0 and frame 1 headers, then uses:

```text
stream start = frame0.end + 1
stream end   = frame1.end + 1
```

So frame index `1` draws the second frame stream.

## Explanation Of The Tiny GIF Black Result

Previous tiny test:

```text
frame 0 = white/light
frame 1 = black
payload < 20 KB, so natural F1[0x11] should be 0x01
panel did not lock
panel stayed black
```

New interpretation:

```text
This can be a successful upload followed by AP displaying frame index 1 only.
```

That means the tiny black result does not prove transport failure, RLE failure, or erase failure.

## Why The Unsafe State-Prepare Patch Failed

The previous GIF state-prepare patch tried to create a favorable state by touching mode/template before upload. Based on this state map, that was risky because AP GIF readiness depends on multiple internal fields being consistent:

- `state+0x19`
- `state+0x1a` / `state+0x1b`
- `state+0x2e`
- `state+0x32`
- `state+0x47`
- `state+0x66`
- `state+0x18`

Forcing mode/template externally can select the GIF display helper before AP has copied pending upload fields into active state fields, or while `state+0x47` is latched. That matches the observed panel corruption during state-prepare tests.

## Current Conclusions

1. Static image root cause remains AP 64 KB erase/program path.
2. GIF has at least one additional AP playback-state issue.
3. The tiny GIF black result is consistent with frame index `1` playback, not necessarily failed upload.
4. GIF patches should not touch mode/template state before upload.
5. GIF patches should not copy the static F1 byte change blindly.

## Next Recommended Work

Offline first:

1. Find whether GCC's own GIF converter intentionally places a dummy frame at index 0.
2. Compare captured GCC `animation.bin` frame 0 vs frame 1 content and decide whether frame 0 being skipped matches known behavior.
3. If live testing is approved later, use only original DLL and diagnostic source GIFs:
   - `diag_2frame_frame1_white.gif`
   - `diag_4frame_frame0_dummy_visible_1_2_3.gif`

No live action follows automatically from this report.



