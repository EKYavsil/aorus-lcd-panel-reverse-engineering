# AP 64KB Timeout Proof Status - 2026-06-01

## Scope

This note answers one narrow question before any patch design:

```text
Are we sure the 64KB failure line is timeout-related?
```

No live panel access, firmware update, DLL deployment, service control, or I2C operation was performed for this pass. This is offline AP firmware analysis only.

## Current Answer

The evidence now strongly supports a real timeout mechanism in the AP firmware.

The exact live failing event is not proven with 100% certainty yet, because we still have not measured the AP timer tick duration or observed a live `BA44` timeout return during a failed 64KB erase. But the code-level structure is now clear:

```text
64KB erase starts
BA44 polls SPI status WIP bit with a real timer-backed counter
BA44 can return 0 on timeout
B4D0 discards that failure and reports success
lower flash region remains stale/corrupt
host sees successful upload
```

This is more than a guess. The timeout counter is real, it is decremented outside the polling loop by an interrupt handler, and the 64KB helper hides the timeout result.

## New Proof Added In This Pass

### 0. The timer init path now points to a likely 1 ms tick

Follow-up offline analysis found the init function for the same interrupt:

```asm
FUN_000017D4:
  base = 0x40076000
  *(base + 0x0C) = 0x00017700
  *(base + 0x08) = 0
  *(base + 0x00) |= 1
  *(base + 0x00) |= 4
  *(0xE000E104) = 0x200
```

The NVIC write enables IRQ 41:

```text
0xE000E104 = NVIC ISER1
0x200 = bit 9
IRQ = 32 + 9 = 41
vector offset = (16 + 41) * 4 = 0xE4
```

The vector table entry at `0xE4` points to `0x0000181C`, the handler that decrements `BA44`'s timeout counter.

The load value `0x17700` is decimal `96,000`. If the peripheral clock is 96 MHz, this is exactly a 1 ms tick. The exact MCU clock is not yet identified, but the firmware's counter usage (`2000`, `4000`) strongly fits a millisecond system tick.

### 1. `BA44` timeout counter is a real shared RAM counter

`FUN_0000BA44` initializes a 16-bit counter to `300`:

```asm
0000ba48  ldr   r5,[0x0000baa8]   ; 0x200000FE
0000ba4a  mov.w r0,#0x12c         ; 300
0000ba4e  strh  r0,[r5,#0x0]
```

It then polls SPI status opcode `0x05` and returns failure when the counter reaches zero:

```asm
0000ba66  movs  r0,#0x5           ; SPI read status
0000ba68  bl    FUN_0000B990
...
0000ba72  bl    FUN_0000B990      ; status read dummy byte
0000ba76  ldrsh.w r1,[r5,#0x0]    ; timeout counter
0000ba7c  cmp   r1,#0x0
0000ba80  movs  r0,#0x0           ; timeout failure
```

The decompiled structure is:

```c
*timeout = 300;
send_spi_byte(0x05);
do {
    feed_watchdog();
    status = send_spi_byte(0);
    if (*timeout < 1) return 0;
} while ((status & 1) != 0);
return 1;
```

So `BA44` is not simply waiting blindly. It has an explicit failure path.

### 2. The counter is decremented by `FUN_0000181C`

`FUN_0000181C` contains the exact decrement of the same `0x200000FE` counter:

```asm
00001830  ldr  r0,[0x0000193c]    ; 0x200000FE
00001832  ldrh r1,[r0,#0x0]
00001834  cbz  r1,0x0000183c
00001836  ldrh r1,[r0,#0x0]
00001838  subs r1,r1,#0x1
0000183a  strh r1,[r0,#0x0]
```

Decompile shape:

```c
if (*DAT_0000193c != 0) {
    *DAT_0000193c = *DAT_0000193c + -1;
}
```

The same function also increments other timebase counters and clamps one counter at `2000`, which is typical timer/tick housekeeping:

```c
*DAT_00001940 = *DAT_00001940 + 1;
*DAT_00001944 = *DAT_00001944 + 1;
*DAT_00001948 = *DAT_00001948 + 1;
if (*DAT_00001948 > 1999) *DAT_00001948 = 2000;
```

### 3. `FUN_0000181C` is present in the interrupt vector table

The AP image vector table contains:

```text
offset 0xE4 -> 0x0000181D
```

Because this is a Thumb firmware image, vector value `0x0000181D` resolves to handler address `0x0000181C`.

Vector index:

```text
0xE4 / 4 = 57
external IRQ number = 57 - 16 = 41
```

This proves the timeout counter decrement is not an ordinary inline busy-loop decrement. It is tied to an interrupt handler. That makes the `300` value a real time budget.

### 4. `FUN_0000C370` is not a delay

Earlier it was unclear whether `FUN_0000C370` might be a sleep/delay helper. It is now resolved as a watchdog/service feed:

```asm
0000c370  ldr r1,[0x0000c37c]   ; 0x40068000
0000c372  ldr r0,[0x0000c378]   ; 0x5FA00001
0000c374  str r0,[r1,#0x0]
0000c376  bx  lr
```

Meaning:

```c
*(uint32_t *)0x40068000 = 0x5FA00001;
```

So the poll loop is not â€œsleeping 300 times.â€ It spins, feeds the watchdog, reads status, and waits for the interrupt-driven timeout counter to reach zero.

### 5. There is a separate busy-delay helper, and `BA44` does not use it

`FUN_0000C986` is the firmware's obvious busy-delay helper:

```c
void FUN_0000C986(int param_1) {
    for (uVar1 = 0; uVar1 < param_1 * 10000; uVar1++) {
    }
}
```

`BA44` does not use this helper. That reinforces that `BA44` is built around an interrupt-driven timeout counter, not a hand-coded delay loop.

## What This Proves

1. `BA44` has a real timeout path.
2. `BA44` timeout is controlled by RAM counter `0x200000FE`.
3. That counter is decremented by `FUN_0000181C`.
4. `FUN_0000181C` is attached to the AP vector table.
5. `C370` is watchdog/service feed, not delay.
6. 4KB erase, 64KB erase, and page program all rely on the same status-poll helper.
7. The 4KB helper checks the poll result.
8. The 64KB helper ignores the poll result.
9. Page program also ignores the poll result.
10. Therefore the AP firmware can silently continue after a timed-out 64KB erase or page program.

## What Is Still Not Fully Proven

The remaining missing piece is exact tick duration:

```text
How long is one decrement of 0x200000FE?
```

If the IRQ tick is 1 ms, `300` means about 300 ms. The newly found `0x17700` timer reload makes this 1 ms interpretation much stronger than before. A 300 ms timeout is shorter than many SPI NOR 64KB block erase maximum timings and even below some typical/worst-case ranges.

If the IRQ tick is much slower, for example 10 ms, `300` would mean about 3 seconds, and the timeout explanation becomes less complete. In that case the same hidden-failure bug remains real, but the physical trigger may shift toward WREN/WEL failure, protection state, or an erase command rejection.

So the current status is:

```text
Timeout mechanism: proven.
Timeout result ignored by 64KB path: proven.
Exact live trigger is timeout rather than WEL/protection: highly likely, not final.
```

## Why Timeout Is Still The Strongest Physical Trigger

The timeout explanation matches every major observation:

1. Static corruption boundary matches the first 64KB boundary.
2. Static was fixed by moving from 64KB erase route to 4KB erase route.
3. 64KB erase has far longer expected duration than 4KB erase and page program.
4. AP uses one fixed `300` timeout budget for all SPI operations.
5. 64KB helper hides failure, so the upload can appear successful while storage is incomplete.
6. GIF depends on the same 64KB route and is larger, so it is more exposed to this defect.

The alternative explanations are still possible but currently weaker:

- WREN/WEL not verified: possible contributing bug, but does not explain the exact 64KB boundary as cleanly.
- Flash protection state: possible, but no robust AP protection-register management has been proven yet.
- Host payload/converter issue: largely ruled out for static because offline render was correct and 4KB route fixed it.

## Next Offline Proof Step Before Patch Design

Do not design the patch yet. First finish the timing proof:

1. Identify what peripheral/IRQ vector index 41 corresponds to in this AP MCU.
2. Decode the timer initialization path around `FUN_0000C260`.
3. Resolve the clock source and reload/prescaler values.
4. Calculate the real tick period for `FUN_0000181C`.
5. Compare `300 * tick_period` with representative SPI NOR 64KB erase timing.

If this shows a roughly millisecond-class tick, then the root cause can be stated very strongly:

```text
AP firmware gives 64KB erase only about 300 ms, but 64KB erase can require hundreds of ms to seconds; the 64KB helper then suppresses the timeout failure.
```

Only after that should patch design begin.

## Current Conclusion

The problem should not yet be reported as â€œdefinitely timeout and nothing else.â€

The precise conclusion is:

```text
The AP firmware has a proven timeout-controlled SPI status poll.
The 64KB erase path definitely ignores that poll failure.
The observed static failure aligns with a failed/incomplete second 64KB erase region.
The timeout explanation is currently the strongest physical trigger, but final proof requires resolving the AP timer tick duration or capturing the 64KB path returning BA44=0.
```


