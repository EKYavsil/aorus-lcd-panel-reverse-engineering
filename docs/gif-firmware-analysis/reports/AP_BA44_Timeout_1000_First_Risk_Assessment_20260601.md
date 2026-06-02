# AP BA44 Timeout 1000-First Risk Assessment - 2026-06-01

Scope: offline reasoning only. No firmware updater was executed, no AP was flashed, no DLL was deployed, no service was touched, and no panel command was sent.

## Question

Is changing `FUN_0000BA44` timeout from `300` to `3000` too aggressive?

Could a longer AP-internal wait hit another global state/timing watchdog, similar to earlier host-side wait experiments that destabilized the panel?

## Short Answer

Yes, `300 -> 3000` is too aggressive as the first native 64KB repair candidate.

The safer first repair candidate should be:

```text
BA44 timeout 300 -> 1000
B4D0 forced-success removed
```

Rationale:

- The symptom boundary suggests `300` may be close, not wildly insufficient.
- A 1000 tick budget is already a 3.33x increase.
- 1000 is less likely than 3000/5000 to expose unrelated AP scheduler/state-machine assumptions.
- If 1000 fixes the native 64KB path, it is a cleaner vendor-quality patch.

## Important Distinction: Host Wait vs BA44 Internal Poll

Earlier failed wait tests changed host-side behavior:

```text
GCC sends F1
host waits longer
host sends payload chunks later
```

Those tests included:

```text
30-second extra post-F1 wait -> still lower black
5s original wait changed to about 10s -> negative / unstable history
```

Those tests do not directly equal a `BA44` timeout increase.

`BA44` is inside AP firmware:

```text
AP sends SPI erase/program command
AP polls SPI status WIP bit
AP feeds watchdog/service through FUN_0000C370
AP exits when WIP clears or timeout expires
```

So a `BA44` increase does not insert an arbitrary host-side sleep between protocol phases. It extends the AP's own verification window for a flash operation that is already in progress.

That makes `BA44 300 -> 1000` more natural than the old host wait tests.

## Why 1000 May Be Enough

Static lower-black behavior suggests the first 64KB region is often handled well enough and the failure appears at or after the next 64KB boundary:

```text
visible data start ~= 0x0130000C
first 64KB boundary = 0x01310000
upper ~60% changes
lower ~40% remains stale/black
```

This does not prove `300` erases exactly 60% of the panel. But it strongly suggests the operation is near a boundary/second-erase failure, not total failure from the first byte.

Common SPI NOR timings make this plausible:

```text
300 ms may be enough for many typical 64KB erases but too short for slower/worst-case 64KB erases.
1000 ms is a meaningful repair margin without jumping to multi-second waits.
```

## Other Timers / Watchdogs

Current offline evidence:

1. Direct `BA44` callers found in the focused dump are:

```text
FUN_0000B4D0  ; 64KB erase
FUN_0000B910  ; 4KB erase
FUN_0000B6CC  ; page program
```

2. `FUN_0000C370` inside `BA44` is a watchdog/service feed, not a sleep.

3. `BA44` timeout counter is interrupt-driven through `FUN_0000181C`.

4. The known GIF timing field:

```text
timing_threshold = erase_count * 3000 + 3
```

appears to be upload/finalization state coupled to `F1[0x11] == 0x02`, not a direct watchdog on the duration of each individual `BA44` poll.

5. No current offline evidence proves a separate supervisor that says:

```text
if one BA44 poll lasts more than 300 ticks, kill the panel state
```

But absence of evidence is not proof of absence.

## Shared-Scope Risk

`BA44` is shared, so timeout increase affects:

```text
64KB erase
4KB erase
page program
```

This matters.

However:

- 4KB erase and page program normally complete faster than 64KB erase.
- Increasing the timeout does not make successful fast operations slower; they still exit when WIP clears.
- It only increases worst-case wait when an operation remains busy or stuck.

So `300 -> 1000` is a bounded risk increase. `300 -> 3000` is a much larger worst-case change and should be second stage only.

## Revised Native 64KB Candidate Order

### N1: B4D0 return propagation only

```text
0xA534: 01 20 -> 00 BF
```

Purpose:

```text
Expose failure instead of corrupting silently.
```

### N2a: BA44 timeout 1000 + B4D0 propagation

Recommended first real native repair candidate.

```text
0xAA4A: 4F F4 96 70 -> 40 F2 E8 30
0xA534: 01 20       -> 00 BF
```

Purpose:

```text
Give native 0xD8 erase about 3.33x more AP-internal verification time,
while still returning failure if it does not complete.
```

### N2b: BA44 timeout 1500 or 2000 + B4D0 propagation

Only if 1000 still exposes timeout/failure but behavior is stable.

Need assembler-confirmed bytes before staging.

### N3: BA44 timeout 3000 + B4D0 propagation

Only after 1000/1500/2000 evidence says we need more.

```text
0xAA4A: 4F F4 96 70 -> 40 F6 B8 30
0xA534: 01 20       -> 00 BF
```

This should no longer be the first live candidate.

### N4: Investigate non-timeout 0xD8 preparation

If 1000/1500/2000 do not repair native 64KB cleanly:

```text
compare B4D0 vs B910 preparation:
write-enable,
address mode,
status/protection,
command ordering,
SPI transfer return handling.
```

## Interpretation If 1000 Fails

If `300 -> 1000` plus B4D0 propagation still fails:

```text
Timeout may not be the only problem.
```

Next likely causes:

- `0xD8` command rejected or not latched;
- write-enable latch not set for second block;
- protection bit / block protect state;
- address-mode issue at high media address;
- page program failure hidden by `B6CC`;
- AP parser/finalization state expects another transition after long erase.

Do not jump immediately to 3000 unless the failure evidence still looks like a clean timeout.

## Current Recommendation

For maximum reliability and native 64KB repair:

```text
First serious native repair candidate:
BA44 timeout 300 -> 1000
B4D0 forced success -> NOP
No B6CC patch yet
No 16x4KB fallback yet
```

This is the best balance between:

- not bypassing the native 64KB path;
- giving 0xD8 enough extra time;
- avoiding an aggressive multi-second global timeout change;
- preventing silent corruption if 0xD8 still fails.



