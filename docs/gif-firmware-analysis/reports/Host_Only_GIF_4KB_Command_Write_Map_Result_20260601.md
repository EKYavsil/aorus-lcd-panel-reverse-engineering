# Host-Only GIF 4 KB Command Write-Map Result

Date: 2026-06-01

Scope: offline analysis only. No live DLL deploy, no service action, no panel command, no firmware updater execution, no raw I2C, no AP firmware patch.

## Question

Can we avoid firmware work and make custom GIF uploads use the reliable 4 KB erase path, while preserving the GIF-specific AP finalization state normally created by the 64 KB path?

This report focuses on AP firmware state writes around:

- `F1` upload header parsing
- `F2` upload finalize handling
- GIF frame/delay commit state
- AP upload timing threshold
- AP upload pending/finalization flags

## Inputs

Ghidra script:

```text
<local-gif-investigation-dir>\scratch\APUploadFinalizeWriteMap.java
```

Ghidra output:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_upload_finalize_write_map_AP_F14_pkg_finalize_map.txt
```

Related feasibility report:

```text
<local-gif-investigation-dir>\reports\Host_Only_GIF_4KB_Deep_Feasibility_20260601.md
```

AP firmware program analyzed:

```text
resource_ap from AP F1.4 package
```

## Key State Fields

The AP state block is effectively rooted around RAM `0x20000010`.

| Field | RAM | Meaning in current analysis |
|---|---:|---|
| `state+0x18` | `0x20000028` | active GIF frame delay |
| `state+0x19` | `0x20000029` | custom slot selector |
| `state+0x1a` | `0x2000002a` | slot 2 ready/state |
| `state+0x1b` | `0x2000002b` | slot 1 ready/state |
| `state+0x2e` | `0x2000003e` | upload/display state |
| `state+0x32` | `0x20000042` | upload run flag |
| `state+0x3d` | `0x2000004d` | refresh dirty flag |
| `state+0x3f` | `0x2000004f` | display dirty flag |
| `state+0x47` | `0x20000057` | GIF fail/state latch candidate |
| `state+0x66` | `0x20000076` | active GIF frame count |
| `state+0x68` | `0x20000078` | pending GIF frame delay |
| `state+0x6a` | `0x2000007a` | pending GIF frame count |
| `state+0x98` | `0x200000a8` | AP upload elapsed counter |
| `upload_phase` | `0x20000307` | upload phase |
| `timing_threshold` | `0x20000308` | GIF/upload finalization threshold |
| `upload_offset` | `0x20000310` | current erase/program offset |
| `erase_selector` | `0x20000314` | erase mode selector from `F1[0x11]` |
| `erase_count` | `0x20000316` | computed erase count |
| `remaining_pages` | `0x20000318` | upload page count / remaining pages |
| `upload_pending` | `0x20000321` | pending finalization gate |

## Confirmed F1 Behavior

In `FUN_00007db8`, the `F1` parser reads `F1[0x11]`:

```c
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

uVar31 = page_count;
if (bVar30 == 3) {
    uVar26 = 1;
}
else {
    uVar26 = (short)((uVar31 << 8) / uVar27) + 1;
}
*DAT_00008210 = uVar26;
```

Then the critical split:

```c
*upload_phase = 1;
*upload_display_state = 2;

if (bVar30 == 0) {
    *upload_display_state = 1;
    *upload_phase = 0;
}
else if (bVar30 == 2) {
    *timing_threshold = erase_count * 3000 + 3;
}
```

So `F1[0x11]` is not a simple chunk-size selector.

It controls at least:

- AP erase unit: 4 KB vs 64 KB
- AP erase count
- whether `timing_threshold` is refreshed
- host-side pacing in `ucVga.dll`

## Confirmed Main-Loop Finalization Gate

In `FUN_0000cb54`, after erase/program completes, AP finalizes only if the pending gate and timing gate pass:

```c
if (*upload_phase != 2) {
    if (upload_pending && state.upload_elapsed >= timing_threshold) {
        if (state.upload_display_state == 1 || state.upload_display_state == 2) {
            upload_pending = 0;
            state.active_frame_count = state.pending_frame_count;
            state.active_delay = state.pending_delay;
            state.upload_display_state = 2;
            state.some_counter = 0;
            state.upload_run_flag = 0;
            state.refresh_dirty = 1;
            state.display_dirty = 1;

            if (state.custom_slot_selector == 1) {
                state.slot1_ready = 2;
            }
            else if (state.custom_slot_selector == 2) {
                state.slot2_ready = 2;
            }
        }
    }
}
```

This is the GIF-critical commit:

- pending frame count becomes active frame count
- pending delay becomes active delay
- display/refresh dirty flags are raised
- the selected custom slot is marked ready

If this gate does not run correctly, the upload can finish at the transport level while the GIF remains blank, stuck on loading, or displays stale/broken state.

## F2 Finalize Findings

The `F2 ... 02` branch was found in `FUN_00007db8`.

It can modify several display and slot state fields:

```c
if (packet[0] == 0xf2 && packet[1..4] == CB 55 AC 38 && packet[5] == 2) {
    in_r3[1] = 0;
    *some_flag = 0;
    *state_upload_run_flag = 0;

    bVar30 = in_r3[2];
    *mode_or_state = bVar30;

    if (bVar30 == 4 || bVar30 == 5) {
        *some_slot_flag = 1;
    }
    else if (bVar30 == 6) {
        *gif_fail_latch = 0;
        *some_flag = 1;
        custom_slot_selector = 3 - custom_slot_selector;

        if (custom_slot_selector == 1) slot1_ready = 2;
        if (custom_slot_selector == 2) slot2_ready = 2;
    }

    display_dirty = 1;
}
```

Important negative finding:

```text
No evidence was found that F2 writes timing_threshold (0x20000308).
No evidence was found that F2 performs the pending->active frame count/delay copy.
```

F2 can mark some slot/display flags, but it does not appear to substitute for the main-loop GIF finalization gate.

## Write-Map Result

| Target | Evidence | Host command found to set it directly? | Conclusion |
|---|---|---:|---|
| `timing_threshold` `0x20000308` | Written in F1 parser only when `F1[0x11] == 2`; read in main loop | No | This is the main blocker for forced 4 KB GIF |
| `upload_pending` `0x20000321` | Read in timer/main-loop path | No clean direct host setter found in command parser output | Cannot force finalization purely by known command |
| `active_frame_count` `state+0x66` | Copied from pending count by main-loop finalization gate | No | F2 does not do this copy |
| `active_delay` `state+0x18` | Copied from pending delay by main-loop finalization gate | No | F2 does not do this copy |
| `pending_frame_count` `state+0x6a` | Set by F1 GIF metadata path | Yes, through normal F1 metadata | Good |
| `pending_delay` `state+0x68` | Set by F1 GIF metadata path | Yes, through normal F1 metadata | Good |
| `slot1_ready/slot2_ready` | Can be touched by F2 mode/state branch and finalization gate | Partially | Slot flag alone is not enough |
| `display_dirty` / `refresh_dirty` | Touched by several display commands | Partially | Dirty flags alone are not enough |

## Why Static 4 KB Worked But GIF 4 KB Did Not

Static image only needed the storage write to become reliable.

The static fix:

```text
F1[0x11] = 0x01 instead of 0x02
```

avoided the broken 64 KB erase helper and used the reliable 4 KB sector erase helper. Static image display did not depend on the same GIF pending-frame finalization state.

GIF is different:

```text
GIF requires both:
1. correct storage erase/program
2. correct AP animation state commit
```

For GIF, the mode2 path appears to do two things at once:

```text
64 KB erase selection
large-upload GIF timing/finalization setup
```

Forcing only the byte to `0x01` gives the good erase path, but removes the only confirmed write to the timing threshold required by the GIF commit gate.

## Why Source GIF Size Did Not Help

The small source GIF test was misleading at the input-file level.

Observed conversion:

```text
source GIF size ~= 19 KB
GCC animation.bin ~= 1.75 MB
frame count = 21
page count = 6832
```

So the GIF still became a large payload after GCC conversion and was routed to `F1[0x11] = 2`.

To naturally hit mode1, the converted `animation.bin`, not the original GIF file, would need to stay below the host threshold. For real full-panel animated GIFs, that is generally not realistic.

## Host-Only Feasibility

### Option A: Always force GIF to `F1[0x11]=1`

Status:

```text
Not safe as a complete fix.
```

Reason:

- selects 4 KB erase
- but skips `timing_threshold = erase_count * 3000 + 3`
- previous direct GIF mode-byte forcing caused unsafe panel behavior

### Option B: Add more host-side waiting

Status:

```text
Not principled.
```

Reason:

- waiting does not write `0x20000308`
- waiting does not directly copy pending GIF frame metadata into active metadata
- old wait experiments did not repair the underlying AP state issue

### Option C: Repeat F2 / Save / SetMode / SetLoop

Status:

```text
Unlikely to solve the core GIF issue.
```

Reason:

- F2 can touch slot/display flags
- but current write-map does not show F2 writing `timing_threshold`
- current write-map does not show F2 doing pending-frame to active-frame commit

### Option D: Find a hidden command that sets the threshold or commits pending GIF state

Status:

```text
Still theoretically possible, but not found in the current parser/write-map.
```

Reason:

- known parser xrefs around `0x20000308` show only F1 mode2 write and main-loop read
- no E1/E5/F3/AA/F2-style known display command has surfaced as a direct threshold setter

## Current Verdict

With the protocol commands found so far, a firmware-free general GIF 4 KB upload path is not proven safe.

The AP firmware couples a bad storage operation and a required GIF timing/finalization setup into the same selector:

```text
F1[0x11] == 2
```

That means:

```text
0x02 gives GIF timing/finalization but uses broken 64 KB erase.
0x01 gives reliable 4 KB erase but loses confirmed GIF timing/finalization setup.
```

This is why the static-image one-byte fix does not transfer cleanly to GIF.

## Most Likely Root Cause Model

The 64 KB path is not merely "too fast" or "host waited too little".

The stronger model is:

```text
AP 64 KB erase/program helper is unreliable or has a broken status/return propagation path.
GCC/host treats upload/finalize as successful even when lower flash regions are not actually erased/written.
Static image exposes this as lower-screen stale/black data.
GIF exposes it as loading, blank/white, stale, or broken animation state.
```

For GIF, escaping the bad 64 KB helper is harder because AP also uses the same selector to prepare the large animation commit timing.

## Recommended Next Offline Steps

1. Search for any non-F1 writer of `0x20000308` using a broader binary-level dataflow script, not only pointer-label xrefs.
2. Decompile and map all parser branches for opcodes not yet classified, especially `0x11`, `0x40`, `0xFA`, `0xFB`, and any command that writes near `0x20000300`.
3. Compare AP F1.2, F1.3, and F1.4 around:
   - F1 parser mode handling
   - `timing_threshold`
   - 64 KB erase helper
   - finalization gate
4. Continue firmware-free only if a safe host command is found that can do one of these:
   - set `timing_threshold` after mode1 F1
   - trigger pending GIF metadata commit
   - request 4 KB erase while preserving mode2 timing setup

## Recommended Practical Direction

If no hidden host command is found, the clean technical fix is probably not in `ucVga.dll` for GIF.

The likely durable fix would be AP-side:

```text
keep the GIF timing/finalization behavior of F1[0x11] == 2
but change the erase/program implementation to use reliable 4 KB sectors
or correctly propagate/handle 64 KB erase failure
```

This does not mean AP firmware patching should be attempted now. It means the offline evidence currently points there if a general GIF fix is required.

## Safety Note

No live file was modified for this report.

No panel command was sent.

No firmware updater was executed.

No service was stopped or started.


