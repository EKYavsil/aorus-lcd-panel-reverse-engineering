# AP 64KB Timeout Final Offline Proof - 2026-06-01

## Scope

This report completes the offline-only proof pass requested before patch design.

Goal:

```text
Prove whether the AP 64KB erase corruption line is timeout-related before designing a patch.
```

No live panel action was performed. No firmware updater, DLL deploy, service control, I2C transaction, or panel command was run.

## Generated Evidence

Ghidra script:

```text
<local-gif-investigation-dir>\scratch\AP64KBTimeoutFinalProofDump.java
```

Ghidra output:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_64kb_timeout_final_proof_dump_AP_F14_pkg_finalproof.txt
```

Related earlier outputs:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_timer_vector_deep_dump_AP_F14_pkg_timer_vector.txt
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_timeout_watchdog_dump_AP_F14_pkg_timeout_watchdog.txt
```

## Timer Chain Proof

### 1. Timer/peripheral init function

`FUN_000017D4` configures peripheral base `0x40076000`:

```asm
000017e4  ldr r4,[0x00001814]    ; 0x40076000
000017e6  ldr r1,[0x00001810]    ; 0x00017700
000017ea  bl  FUN_000019B6       ; *(base + 0x0C) = 0x17700
000017f2  bl  FUN_000019BA       ; *(base + 0x08) = 0
000017fa  bl  FUN_000019A2       ; *(base + 0x00) |= 1
00001802  bl  FUN_0000198E       ; *(base + 0x00) |= 4
00001806  ldr r1,[0x00001818]    ; 0xE000E004
00001808  asrs r0,r4,#0x15       ; 0x40076000 >> 21 = 0x200
0000180a  str.w r0,[r1,#0x100]   ; *(0xE000E104) = 0x200
```

Literal values:

```text
0x00001810 -> 0x00017700
0x00001814 -> 0x40076000
0x00001818 -> 0xE000E004
```

Helper roles:

```text
FUN_000019B6(base, value): *(base + 0x0C) = value
FUN_000019BA(base, value): *(base + 0x08) = value
FUN_000019A2(base, 1):     *(base + 0x00) |= 1
FUN_0000198E(base, 1):     *(base + 0x00) |= 4
```

This is a clean timer-style initialization sequence:

```text
load/reload = 0x17700
counter/current = 0
enable bit = set
interrupt-enable bit = set
NVIC enable = set
```

### 2. NVIC IRQ enable matches vector table

The code writes:

```text
*(0xE000E104) = 0x200
```

`0xE000E104` is NVIC `ISER1`, and `0x200` is bit 9 in that register.

Therefore:

```text
IRQ number = 32 + 9 = 41
vector table offset = (16 + 41) * 4 = 0xE4
```

The AP vector table entry at offset `0xE4` points to:

```text
0x0000181D -> Thumb handler 0x0000181C
```

So the init function enables the same IRQ whose vector is `FUN_0000181C`.

### 3. Interrupt handler decrements BA44 timeout counter

`FUN_0000181C` starts by acknowledging the same peripheral:

```asm
00001820  ldr r0,[0x00001934]    ; 0x40076000
00001822  bl  FUN_00001980
```

`FUN_00001980`:

```asm
00001980  ldr r1,[r0,#0x4]
00001982  bic r1,r1,#0x1
00001986  str r1,[r0,#0x4]
00001988  dsb #0xf
```

Meaning:

```text
*(0x40076000 + 0x04) &= ~1
```

Then the handler decrements the exact `BA44` timeout counter:

```asm
00001830  ldr  r0,[0x0000193c]   ; 0x200000FE
00001832  ldrh r1,[r0,#0x0]
00001834  cbz  r1,0x0000183c
00001836  ldrh r1,[r0,#0x0]
00001838  subs r1,r1,#0x1
0000183a  strh r1,[r0,#0x0]
```

This proves the `BA44` timeout counter is hardware-interrupt driven.

## Tick Duration Evidence

The timer load/reload value is:

```text
0x17700 = 96,000 decimal
```

If the timer clock is 96 MHz:

```text
96,000 cycles / 96,000,000 cycles per second = 0.001 s = 1 ms
```

The firmware also uses the same handler to maintain software counters with human-scale values:

```text
0x7D0 = 2000
0xFA0 = 4000
```

These strongly fit:

```text
2000 ticks ~= 2 seconds
4000 ticks ~= 4 seconds
```

Therefore, the strongest interpretation is:

```text
1 tick ~= 1 ms
BA44 timeout = 300 ticks ~= 300 ms
```

Strictly speaking, the exact AP MCU clock has not been named from a public datasheet yet. But the internal firmware evidence is now highly consistent with a millisecond tick.

## BA44 User Table

The final dump found exactly these direct callers of `FUN_0000BA44`:

```text
from=0000b74a fn=FUN_0000B6CC
from=0000b970 fn=FUN_0000B910
from=0000b530 fn=FUN_0000B4D0
```

| Function | Role | SPI opcode | BA44 result handling | Status |
|---|---:|---:|---|---|
| `FUN_0000B4D0` | 64KB erase | `0xD8` | ignored, forced success | defective |
| `FUN_0000B910` | 4KB erase | `0x20` | checked, failure propagates | correct |
| `FUN_0000B6CC` | page program | `0x02` | ignored, forced success | defective |

### 64KB erase: `FUN_0000B4D0`

Critical sequence:

```asm
0000b500  movs r0,#0xd8
...
0000b530  bl   FUN_0000BA44
0000b534  movs r0,#0x1
```

Meaning:

```text
0xD8 block erase is issued.
BA44 polls status.
BA44 may return 0 on timeout.
B4D0 overwrites the result with success.
```

### 4KB erase: `FUN_0000B910`

Critical sequence:

```asm
0000b940  movs r0,#0x20
...
0000b970  bl   FUN_0000BA44
0000b974  cmp  r0,#0x0
0000b976  beq  fail
0000b978  movs r0,#0x1
```

Meaning:

```text
0x20 sector erase is issued.
BA44 polls status.
If BA44 returns 0, B910 returns failure.
```

This is the correct behavior.

### Page program: `FUN_0000B6CC`

Critical sequence:

```asm
0000b704  movs r0,#0x2
...
0000b74a  bl   FUN_0000BA44
0000b74e  movs r0,#0x1
```

Meaning:

```text
0x02 page program is issued.
BA44 polls status.
BA44 may return 0 on timeout.
B6CC overwrites the result with success.
```

This can hide write failures after an incomplete erase.

## BA44 Behavior

`FUN_0000BA44` initializes the timeout to `300`:

```asm
0000ba48  ldr   r5,[0x0000baa8]   ; 0x200000FE
0000ba4a  mov.w r0,#0x12c         ; 300
0000ba4e  strh  r0,[r5,#0x0]
```

It sends SPI status-read opcode `0x05`:

```asm
0000ba66  movs r0,#0x5
0000ba68  bl   FUN_0000B990
```

It repeatedly reads status and checks WIP bit:

```asm
0000ba6c  bl    FUN_0000C370      ; watchdog/feed
0000ba72  bl    FUN_0000B990      ; dummy status read
0000ba76  ldrsh.w r1,[r5,#0x0]    ; timeout counter
0000ba7c  cmp   r1,#0x0
0000ba80  movs  r0,#0x0           ; timeout
...
0000ba8c  lsls  r0,r0,#0x1f       ; WIP bit test
0000ba8e  bne   poll_again
...
0000ba9e  movs  r0,#0x1           ; success
```

So `BA44` already has the right low-level idea:

```text
wait for WIP clear
return 0 if timeout expires
return 1 if WIP clears
```

The firmware bug is not inside the existence of `BA44`. The bug is that the 64KB erase and page-program helpers discard its result.

## Why This Explains The Static Boundary

Static image:

```text
slot base = 0x01300000
visible payload starts around = 0x0130000C
frame size = 320 * 170 * 2 = 108,800 bytes = 0x1A900
first 64KB boundary = 0x01310000
bytes before first boundary = about 65,524
rows before boundary = 65,524 / 640 = about 102.38
visible ratio = 102.38 / 170 = about 60.2%
```

Observed symptom:

```text
upper ~60% updates
lower ~40% remains black/stale
```

That aligns with failure after the first 64KB erase/program region.

Static was fixed by forcing the AP onto the 4KB erase route. That avoids the defective 64KB helper.

## Timeout Root Cause Confidence

### Proven

1. `BA44` has a real timeout path.
2. The timeout counter is decremented by a hardware interrupt handler.
3. The handler is enabled through NVIC and connected to vector `0xE4`.
4. The timer/peripheral load is `0x17700` (`96,000`).
5. The code strongly fits a 1 ms tick.
6. `BA44` timeout is therefore very likely about 300 ms.
7. 64KB erase calls `BA44`.
8. 64KB erase ignores `BA44` failure.
9. Page program calls `BA44`.
10. Page program ignores `BA44` failure.
11. 4KB erase calls `BA44` and correctly propagates failure.

### Highly likely

```text
64KB block erase sometimes exceeds the AP's ~300 ms timeout.
BA44 returns 0.
B4D0 discards that timeout and reports success.
The upload continues into an incompletely erased block.
B6CC may then also hide program failures.
```

### Still not absolutely proven

```text
The exact AP MCU model and documented timer clock.
A live trace showing BA44 returning 0 during the failed 64KB erase.
```

But the offline evidence is now strong enough to justify designing an offline patch candidate around:

```text
increase 64KB erase wait budget
propagate BA44 result in 64KB erase
propagate BA44 result in page program
```

## External Timing Context

Representative SPI NOR flash timings show why a ~300 ms timeout is risky for 64KB erase:

- Winbond W25Q128FV: 64KB block erase can be much longer than page program or 4KB sector erase.
- Macronix MX25L12835F / MX25L12873F: 64KB block erase commonly falls in hundreds of milliseconds and can exceed 300 ms.

Previously used references:

```text
https://www.buydisplay.com/download/manual/W25Q128FV_Datasheet.pdf
https://www.digikey.com/en/htmldatasheets/production/2014243/0/0/1/mx25l12835f
https://www.digikey.com/en/htmldatasheets/production/2702709/0/0/1/mx25l12873f
```

NVIC reference used for `ISER1` mapping:

```text
https://software-dl.ti.com/simplelink/esd/simplelink_cc13xx_cc26xx_sdk/8.32.00.07/exports/docs/driverlib/cc13x4_cc26x4/register_descriptions/CPU_MMAP/CPU_NVIC.html
```

## Current Conclusion

The best root-cause statement is now:

```text
The AP firmware's 64KB flash erase path is defective because it uses the same short, timer-driven BA44 completion timeout as smaller operations, then discards BA44 failure and reports success. The page-program helper has the same failure-suppression bug. This allows the host upload to continue after an incomplete erase/program operation, producing partial/stale display storage. Static image corruption aligns exactly with the first 64KB boundary, and switching static to the 4KB erase route avoids the defective helper.
```

The next phase can move from proof to offline patch design, but no live firmware action should be taken until patch diff, checksum/packaging, recovery path, and failure mode are separately documented.



