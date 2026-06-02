# Host-Only GIF 4 KB Deep Feasibility

Date: 2026-06-01

Scope: offline only. No live DLL deploy, service action, panel command, raw I2C, firmware updater execution, or AP firmware patching.

## Objective

Find whether GIF uploads can be made to use the reliable 4 KB erase path without touching AP firmware.

Firmware-side repair is explicitly treated as a last resort.

## Current Evidence

Latest real GIF test source:

```text
<local-downloads-dir>\giphy1-ezgif.com-optimize (1).gif
source size = 19,244 bytes
```

GCC converted output:

```text
C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\Assets\animation.bin
payload size = 1,748,766 bytes
frame count = 21
delay = mostly 60 ms
page count = 6,832
```

Therefore this upload was not a natural 4 KB upload. It used the large mode:

```text
F1[0x11] = 0x02
```

## F1 State Emulation

Helper script:

```text
<local-gif-investigation-dir>\simulate_f1_upload_state.py
```

Output for the latest GIF:

| Scenario | Payload | Pages | Mode | AP erase unit | AP erase count | Timing threshold | Host prepare |
|---|---:|---:|---:|---:|---:|---:|---:|
| latest GIF normal | 1,748,766 | 6,832 | `2` | `0x10000` | 27 | 81,003 | 54.0 s |
| latest GIF forced 4 KB | 1,748,766 | 6,832 | `1` | `0x1000` | 428 | unchanged | 170.8 s |

This is the core problem:

```text
mode 1 gives us the desired 4 KB erase
but does not refresh the large-GIF timing/finalization threshold
```

## What `F1[0x11]` Controls

Host `GvLcdApi.SendImage` uses `uSize`:

```text
if uSize < 20480:
    F1[0x11] = 1
    host prepare unit = 4096
    host prepare delay = 400 ms
else:
    F1[0x11] = 2
    host prepare unit = 65536
    host prepare delay = 2000 ms
```

AP parser uses the same byte:

```c
bVar30 = F1[0x11];

if (bVar30 == 2) {
    erase_unit = 0x10000;
}
else {
    erase_unit = 0x1000;
}

erase_count = ((page_count << 8) / erase_unit) + 1;

if (bVar30 == 2) {
    timing_threshold = erase_count * 3000 + 3;
}
```

So `F1[0x11]` is not only a transfer speed field.

It is a combined:

```text
host pacing selector
AP erase granularity selector
AP large-upload finalization selector
```

## Why The Static Fix Worked

Static image:

```text
destination = 0x01300000
storage = 1
mode/display path = static image
```

Changing:

```text
F1[0x11] 0x02 -> 0x01
```

selected AP 4 KB sector erase and avoided the broken 64 KB helper.

Static apparently does not depend on the same GIF finalization timing path.

## Why GIF Is Different

GIF upload:

```text
destination = 0x00000000
storage = 2
AP custom animation base likely = 0x01000000
payload = frame table + per-frame RLE streams
```

The AP finalization gate does this after upload:

```c
if (upload_pending && elapsed >= timing_threshold) {
    upload_pending = 0;
    state+0x66 = state+0x6A;  // active frame count
    state+0x18 = state+0x68;  // active frame delay
    state+0x2E = 2;
    state+0x32 = 0;
    state+0x3D = 1;
    state+0x3F = 1;

    if (state+0x19 == 1) state+0x1B = 2;
    if (state+0x19 == 2) state+0x1A = 2;
}
```

The only confirmed write to `timing_threshold` is:

```c
if (F1[0x11] == 2) {
    timing_threshold = erase_count * 3000 + 3;
}
```

For mode `1`, AP does not compute a fresh threshold.

## Host-Only Paths Investigated

### Path 1: Change Threshold So All GIFs Use `F1[0x11]=1`

This is the simplest idea:

```text
ignore 20480 threshold
always use 4 KB mode for GIF
```

Pros:

- avoids AP `0xD8` 64 KB block erase;
- uses AP `0x20` 4 KB sector erase helper;
- preserves destination `0`, storage `2`, frame count, delay, and payload.

Cons:

- loses the only confirmed write to the large-GIF timing threshold;
- makes upload preparation much slower;
- direct GIF mode-byte forcing was already unsafe in live testing.

Verdict:

```text
Not safe as a complete fix.
```

### Path 2: Add More Host Waits Around Mode 1

Idea:

```text
force mode 1
wait long enough
then F2 finalize/apply
```

Problem:

- waiting does not write `0x20000308`;
- finalization gate compares AP elapsed counter against that field;
- if the field is stale, zero, or inappropriate for the current upload, extra host wait may not create the correct commit state.

Old static waits also showed that host-side waiting does not fix AP internal erase-state bugs.

Verdict:

```text
Weak. Not a principled general solution.
```

### Path 3: Reuse Old Timing Threshold

Idea:

```text
let a previous mode-2 upload set timing_threshold
then force mode-1 upload and rely on the old value
```

Problems:

- the value would be stale and size-dependent;
- for the latest GIF, correct mode-2 value is 81,003;
- different GIF sizes need different values;
- panel state after firmware recovery/restart may not preserve it;
- direct mode-1 GIF test already failed/was unsafe, which weakens this path.

Verdict:

```text
Not reliable enough for a general fix.
```

### Path 4: Dual-F1 Sequence

Idea:

```text
F1 mode 2 only to set timing
F1 mode 1 for actual 4 KB erase/upload
```

Problems:

- mode-2 F1 queues the dangerous 64 KB erase path;
- even a dummy mode-2 F1 still sets AP upload state;
- second F1 likely overwrites phase, erase selector, counts, storage state, and pending fields;
- no parser evidence shows "timing only" behavior.

Possible lower-risk offline variant:

```text
model dual-F1 parser state without live testing
```

Verdict:

```text
Not safe for live testing.
Only worth AP-state emulation, not execution.
```

### Path 5: Lie About F1 Page Count

Idea:

```text
use F1[0x11]=2 with fake page count to get desired timing,
but send actual payload differently
```

Problems:

- AP uses page count for both erase/program accounting and remaining pages;
- too small a page count will finalize before all payload pages;
- too large a page count will wait for pages the host does not send;
- GIF frame offsets require a contiguous valid payload.

Verdict:

```text
Not viable.
```

### Path 6: Split Large GIF Into Multiple Small Uploads

Idea:

```text
send each part below 20480 so every upload is natural 4 KB
```

Problem:

- AP upload starts at the GIF slot base each time;
- F1 resets upload offset/accounting;
- no append protocol was found;
- the GIF container has one frame table with absolute end offsets.

Verdict:

```text
Not viable without an append command, and no append command is known.
```

### Path 7: Use Static Slot For GIF Payload

Idea:

```text
send GIF-like data to a nonzero destination where static 4 KB fix works
```

Problem:

- AP playback helper for GIF selects custom animation base from state fields, not the static image slot;
- mode 5 built-in path and custom animation path are different;
- static slot display expects a single-frame/static container, not animation frame table playback.

Verdict:

```text
Not viable with current AP display model.
```

### Path 8: Hidden Timing Setter Command

Desired:

```text
F1[0x11]=1 for 4 KB erase
then a separate safe command sets timing_threshold as if mode 2 ran
```

Search performed:

- targeted xrefs for `0x20000308`;
- parser command catalog;
- known commands `D6/D7/D8/DE/DF/EA/EB/EC/ED/F3/F4/F9/FA/FB`;
- AP timing field report.

Current result:

```text
No confirmed host command writes 0x20000308.
```

The only confirmed write remains:

```text
F1 parser, only when F1[0x11] == 2
```

Verdict:

```text
This is the only clean host-only path.
Currently not found.
```

## What Would Make Host-Only Possible

Host-only general GIF 4 KB fix becomes plausible if one of these is proven:

1. A safe command can write or indirectly refresh `0x20000308`.
2. A safe command can force the same post-upload commit:
   - `state+0x66 = state+0x6A`
   - `state+0x18 = state+0x68`
   - `state+0x2E = 2`
   - `state+0x32 = 0`
   - `state+0x3D = 1`
   - `state+0x3F = 1`
   - slot ready marker set to `2`
3. AP naturally finalizes mode-1 large GIF correctly if a precise F2/order/wait sequence is used, despite the missing fresh threshold.

Current evidence does not prove any of these.

## What Looks Impossible From Host Only

These appear effectively blocked by current protocol:

| Goal | Why blocked |
|---|---|
| Large GIF entirely in natural 4 KB mode | possible to select, but missing large-GIF timing state |
| Arbitrary large GIF split into small chunks/uploads | no append protocol |
| Read back flash and retry bad sectors | no arbitrary media readback command found |
| Set AP timing threshold directly | no command found |
| Use static slot for GIF playback | different AP display path |

## Best Remaining Firmware-Free Research Direction

Before declaring host-only impossible, do one more offline pass:

1. Full parser write-map for:
   - `0x20000308`
   - upload pending flag
   - upload phase
   - `state+0x18`, `state+0x66`, `state+0x1A`, `state+0x1B`, `state+0x2E`, `state+0x32`

2. Exact F2 start/finish semantics:
   - what `F2 01` sets;
   - what `F2 02` sets;
   - whether F2 can be repeated safely;
   - whether F2 depends on `F1[0x11]`.

3. Host DLL call graph:
   - any hidden use of `D7/D8/DE/EB/EC/ED/F4` after upload;
   - any method equivalent to "commit upload state" beyond `Save`.

4. Design a purely trace-only test, not a mutation:
   - log F1 mode, D7 upload phase, D8/DE state reads if accessible through existing APIs;
   - do not alter payload or mode.

## Current Decision

Firmware-free general GIF 4 KB support is not proven impossible yet, but the naive path is ruled out:

```text
Do not just force F1[0x11]=1 for all GIFs.
```

The host-only solution requires a way to preserve or recreate the `F1[0x11]=2` finalization semantics while avoiding its 64 KB erase.

Current status:

```text
Host-only clean solution: not found yet.
Host-only direct threshold patch: unsafe/incomplete.
Firmware-side semantic split: technically clean, but deferred.
```

The next offline target is narrow:

```text
Find an existing AP command path that can trigger or emulate the post-upload finalization commit without using F1[0x11]=2.
```


