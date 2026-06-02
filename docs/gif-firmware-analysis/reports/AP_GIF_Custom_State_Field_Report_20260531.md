# AP GIF Custom State Field Report

Date: 2026-05-31

Scope: offline AP firmware / Ghidra analysis only. No live DLL deploy, no service action, no firmware updater execution, no panel I2C access.

## Context

The static image issue was fixed by changing only the static upload F1 metadata byte:

```text
nType == 1, destination 0x01300000:
F1[0x11] 0x02 -> 0x01
```

That same idea must not be blindly reused for GIF. The failed GIF test already showed that changing the GIF path to `0x01` can badly corrupt panel display state.

This report tracks why GIF behaves differently.

## New Exports

Generated under:

```text
<local-gif-investigation-dir>\
```

Files:

```text
exports\ap_all_dat_pointer_dump_AP_F14_pkg_gif_followup.csv
exports\ap_exact_function_dump_AP_F14_gif_state_slot_helpers.txt
exports\ap_custom_address_xrefs_AP_F14_gif_custom_state_fields.txt
scratch\APFirmwareCustomAddressXrefDump.java
```

The main project/repo was not edited.

## Key RAM / Global Mapping

From `ap_all_dat_pointer_dump_AP_F14_pkg_gif_followup.csv`:

| Ghidra label | Value | Meaning |
|---|---:|---|
| `DAT_00006b14` | `0x20000160` | GIF playback state block |
| `DAT_00006b18` | `0x20000260` | GIF/display streaming state block |
| `DAT_00002358` | `0x20000010` | main AP display/config state |
| `DAT_00002368` | `0x200001A8` | current GIF/display start address |
| `DAT_00002370` | `0x200001AC` | current GIF/display end address |
| `DAT_000098a0` | `0x20000010` | main state used by custom animation helper |
| `DAT_000098a4` | `0x200001B0` | another base/window field used by custom animation helper |

## Mode 5 Default Path

`FUN_00002034()` mode `5` does this:

```c
*(state + 0x10) = 1;
*0x200001A8 = 0x01F4B00C;
*0x200001AC = 0x01F6590C;
FUN_00009bb8();
FUN_00001e14();
```

Interpretation:

Mode 5 does not automatically mean "show the newest custom GIF upload." Its direct dispatcher path points playback at a fixed AP flash range:

```text
start = 0x01F4B00C
end   = 0x01F6590C
```

That looks like a built-in/default animation region, not the host F1 target `0`.

## Custom Animation Helper

`FUN_0000982c()` is the most relevant custom animation helper found so far:

```c
if (*(state + 0x47) != 1) {
    if (*(state + 0x19) == 1) {
        *0x200001B0 = 0;
    }
    else if (*(state + 0x19) == 2) {
        *0x200001B0 = 0x01000000;
    }

    *(state + 0x10) = 2;
    FUN_00009b90();
    FUN_00002cc0();

    if (FUN_00001c78(*(state + 0x11)) == 0) {
        *(state + 0x47) = 1;
        return;
    }

    FUN_00009bf6(*(state + 0x11), 0, 0x23);

    if ((ushort)*(byte *)(state + 0x18) <= *(ushort *)(state + 0x64)) {
        state[0x11]++;
        if (*(ushort *)(state + 0x66) <= state[0x11]) {
            state[0x11] = 1;
            state[0x12] = 1;
        }
        *(ushort *)(state + 0x64) = 0;
    }
}
```

Important interpretation:

- `state+0x19 == 2` selects base/window `0x01000000`.
- This matches the F1 parser special case where GIF target `0` can be remapped to `0x01000000`.
- `state+0x47` acts like a disable/done/fail flag. If set to `1`, this helper exits immediately.
- `state+0x66` behaves like frame-count/end-frame.
- `state+0x18` behaves like frame delay/timing threshold.
- `FUN_00001c78(frame)` checks frame metadata at `base + frame * 10 + 6` and expects `0x0140 x 0x00AA` dimensions.

## Upload Finalization / Timing Gate

The upload loop has an important gate after page programming:

```c
do {
    if ((!upload_pending) || (*(uint *)(state + 0x98) < *DAT_0000d168)) goto main_loop;
} while ((state+0x2e != 1) && (state+0x2e != 2));

upload_pending = 0;
*(ushort *)(state + 0x66) = *(ushort *)(state + 0x6a);
*(byte *)(state + 0x18) = *(byte *)(state + 0x68);
state+0x2e = 2;
state+0x32 = 0;
state+0x3d = 1;
state+0x3f = 1;

if (state+0x19 == 1) state+0x1b = 2;
if (state+0x19 == 2) state+0x1a = 2;
```

`DAT_0000d168` is set from F1 byte `0x11` only in the `0x02` case:

```c
if (F1[0x11] == 0x02) {
    *DAT_0000d168 = erase_count * 3000 + 3;
}
```

This is the critical GIF difference.

Static worked with `F1[0x11] = 0x01` because static needed reliable sector erase at `0x01300000/0x01310000`. GIF has a larger animation-state path that appears to depend on the timing/finalization gate populated by `F1[0x11] == 0x02`.

Changing GIF `F1[0x11]` to `0x01` therefore likely removes or weakens this post-upload commit delay/threshold, not just the erase size.

## Why Static Fix Does Not Directly Apply To GIF

Static:

```text
destination = 0x01300000
storage/type = 1
display slot = static slot
observed bug = 64 KB erase boundary at 0x01310000
fix = force 4 KB sector erase
```

GIF:

```text
destination = 0 / remapped internally to 0x01000000
storage/type = 2
display path = animation state helper
extra state = frame count, delay, frame metadata table, playback base/window
F1[0x11] == 2 also sets timing threshold
```

So GIF is not just "static image with a bigger payload." It has a separate AP animation commit/playback path.

## Current Best Hypothesis

The GIF issue is more likely in one of these areas:

1. AP upload/finalize does not correctly commit the custom animation state after writing the payload.
2. `state+0x19`, `state+0x1a`, `state+0x1b`, `state+0x47`, `state+0x66`, or `state+0x18` remain stale after upload.
3. The display path points to built-in mode-5 flash (`0x01F4B00C..0x01F6590C`) instead of custom base `0x01000000`.
4. The host sequence does not trigger the correct custom animation mode/helper after GIF upload.
5. GIF F1 `0x11 == 0x02` may be required for timing/finalization even if 64 KB erase has risks.

## What Not To Test Yet

Do not repeat the failed GIF patch:

```text
nType == 0, target 0, storage 2:
F1[0x11] 0x02 -> 0x01
```

That change is unsafe because it changes timing/commit behavior, not only erase granularity.

## Recommended Next Offline Step

Before any live GIF test, trace or statically prove the host-side mode transition after GIF upload:

1. Which GCC method calls `SetMode` after GIF upload?
2. What mode is selected for custom GIF?
3. Is `SetLoop` locking the animation/custom mode correctly?
4. Does host call anything equivalent to selecting AP custom animation helper `FUN_0000982c()`?
5. Does AP `state+0x19` become `2` after GIF upload and stay there after save/restart?

The lowest-risk next live trace, later and only with approval, would be state/mode logging around GIF upload and apply, not another payload/metadata mutation.



