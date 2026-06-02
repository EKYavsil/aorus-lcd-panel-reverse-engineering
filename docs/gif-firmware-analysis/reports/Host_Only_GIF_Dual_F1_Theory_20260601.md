# Host-Only GIF Dual-F1 Theory

Date: 2026-06-01

Scope: offline reasoning only. No live command, DLL deploy, service action, firmware updater execution, or panel test.

## Problem

For GIF:

```text
F1[0x11] = 2
```

does two useful/dangerous things at once:

1. selects AP 64 KB erase path;
2. sets GIF upload/finalization timing threshold:

```c
timing_threshold = erase_count * 3000 + 3;
```

For static image, changing:

```text
F1[0x11] 2 -> 1
```

works because it selects 4 KB erase and static does not appear to need the same GIF timing commit behavior.

For GIF, direct `2 -> 1` is incomplete because the timing threshold is not refreshed.

## Theory

Use two F1 headers for the same GIF upload:

```text
F2 01 start
F1 same GIF metadata, mode byte 2   // set timing_threshold
F1 same GIF metadata, mode byte 1   // switch erase selector to 4 KB, keep old timing_threshold
payload
F2 02 finish
```

Why this could work:

- The F1 parser writes `timing_threshold` only when mode byte is `2`.
- The F1 parser does not visibly clear `timing_threshold` when mode byte is `1`.
- A second F1 with mode byte `1` would update:
  - destination/base state
  - erase selector
  - erase count
  - page count
  - pending GIF frame metadata
  - upload phase/offset
- If `timing_threshold` remains from the first F1, AP might:
  - erase/program using 4 KB;
  - still pass the GIF finalization gate later.

## State Effects From F1 Parser

Mode2 F1 sets:

```text
erase_selector = 2
erase_count = 64 KB count
remaining_pages = page count
upload_phase = 1
upload_display_state = 2
timing_threshold = erase_count * 3000 + 3
upload_offset = 0
```

Mode1 F1 sets:

```text
erase_selector = 1
erase_count = 4 KB count
remaining_pages = page count
upload_phase = 1
upload_display_state = 2
upload_offset = 0
```

Mode1 F1 does not show:

```text
timing_threshold = ...
```

Therefore the second F1 may preserve the first F1's timing threshold.

## Major Risk

The AP main loop may begin processing upload erase as soon as the first F1 is parsed.

If sequence is:

```text
F1 mode2
small delay before F1 mode1
```

then AP might start a 64 KB erase before the corrective mode1 F1 arrives.

That would hit the broken path we are trying to avoid.

So the feasibility depends on whether the host can send two F1 packets closely enough that AP state is overwritten before the main upload worker performs the first erase step.

This is not guaranteed.

## Timing Concern

For a large GIF example:

```text
normal mode2 erase_count = 27
mode2 timing_threshold = 81003
forced mode1 erase_count = 428
```

If we preserve the mode2 timing threshold while doing 4 KB erase, the threshold is shorter than a true 4 KB erase-count based threshold would be.

But the finalization gate checks:

```c
if (upload_pending && upload_elapsed >= timing_threshold) commit;
```

If the upload has already taken longer than `81003` ticks by the time programming finishes, the shorter preserved threshold may still be enough.

So this can be acceptable only if:

```text
threshold is a lower-bound wait, not a timeout or exact expected-duration value.
```

Current decompile shape suggests lower-bound wait, but not proven.

## Why This Is More Plausible Than 0x66

The dual-F1 idea uses only known production packet types:

```text
F1/F2
```

It does not rely on undocumented:

```text
66 CB 55 AC 38 AB CD EF
```

It also does not require AP firmware patching.

## Why This Is Still Not Safe Yet

The risk is not protocol legitimacy. The risk is race/state timing.

Possible failure modes:

| Failure | Cause |
|---|---|
| AP starts one 64 KB erase before second F1 | first F1 arms mode2 immediately |
| AP rejects or mishandles repeated F1 | parser not intended for two headers in one upload session |
| finalization commits too early | preserved mode2 threshold too short for 4 KB erase/program |
| upload state desynchronizes | host payload schedule no longer matches AP state |

## Offline Verdict

This is currently the best firmware-free theoretical path found:

```text
Dual-F1: mode2 header to set GIF timing, immediately followed by mode1 header to select 4 KB erase.
```

It is more defensible than:

- direct GIF `2 -> 1`;
- extra waits;
- repeating F2/AA/E5/F3;
- using undocumented `0x66`.

But it is not ready for live testing without a guarded test design, because the first F1 could trigger the bad 64 KB erase before the second F1 lands.

## Next Offline Work

Before any live design:

1. Inspect host `SendImage` ordering and confirm whether an extra F1 can be inserted before any payload and before host-side prepare sleeps.
2. Inspect AP main-loop cadence around `FUN_0000CB54` to estimate whether two immediate F1 packets can overwrite state before erase begins.
3. Confirm whether receiving a second F1 resets `upload_offset = 0` and does not preserve any bad mode2 erase progress.
4. Look for existing traces where multiple F1 packets occur in one session.
5. If a live test is ever designed, it must be:
   - GIF-only;
   - tiny controlled payload first;
   - full logging;
   - automatic restore;
   - no unknown opcodes;
   - firmware recovery plan ready.

## Host SendImage Ordering Check

Current `ucVga.Api.GvLcdApi.SendImage` order from IL:

```text
Sleep(2000)
F2 01 start upload
Sleep(500)
build F1 metadata
SendData(F1)
if F1 fails -> return false
host prepare sleep loop
Sleep(1000)
payload chunks
Sleep(500)
F2 02 finish
```

The host already has a clean insertion point:

```text
immediately after the original F1 SendData succeeds
and before the host prepare sleep loop starts
```

For dual-F1, the lowest-disturbance host-side placement would be:

```text
SendData(F1 mode2)
SendData(F1 mode1 clone)
prepare sleep loop
payload chunks
F2 02
```

No payload bytes would be modified.

No GIF metadata except `F1[0x11]` in the second header would be modified.

## AP Main-Loop Race Check

The AP main loop calls the parser before the erase worker:

```c
FUN_00007db8();   // parser
...
if (upload_phase == 1) {
    erase one sector/block;
}
```

This is good for dual-F1 only if both F1 packets are received and parsed before the next erase-worker turn runs.

But this is still not guaranteed from host side, because each `SendData` is a separate I2C transaction and AP may iterate between them.

The race risk is:

```text
F1 mode2 parsed
AP main loop runs erase worker once
64 KB erase begins
F1 mode1 arrives too late
```

This is the main reason dual-F1 remains a theory, not a live recommendation yet.

## Offline Feasibility Update

Dual-F1 is technically more plausible after IL review because:

- host has a natural insertion point before payload;
- the second F1 would run before host prepare sleeps;
- the second F1 should reset `upload_offset = 0` and set 4 KB erase selector;
- payload flow can remain unchanged.

But the AP race cannot be ruled out offline.

The cleanest possible version would require proving or enforcing:

```text
no erase worker step happens between F1 mode2 and F1 mode1
```

Without that proof, a live test could still touch the broken 64 KB erase path.

## Safety Note

No live action was taken.


