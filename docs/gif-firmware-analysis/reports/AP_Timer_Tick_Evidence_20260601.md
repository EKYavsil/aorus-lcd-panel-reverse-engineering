# AP Timer Tick Evidence - 2026-06-01

## Scope

This note continues the timeout proof line. It does not design or recommend a patch.

Question:

```text
Is BA44's 300-count timeout likely a short real-time timeout?
```

No live panel, firmware updater, DLL deployment, service control, or I2C action was performed.

## Files Generated

Ghidra script:

```text
<local-gif-investigation-dir>\scratch\APTimerVectorDeepDump.java
```

Output:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_timer_vector_deep_dump_AP_F14_pkg_timer_vector.txt
```

The previous timeout/watchdog dump was also refreshed:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_timeout_watchdog_dump_AP_F14_pkg_timeout_watchdog.txt
```

## New Facts

### 0. Timer init path for the same interrupt was found

`FUN_000017D4` configures the same peripheral used by the timeout handler:

```asm
000017e4  ldr r4,[0x00001814]    ; 0x40076000
000017e6  ldr r1,[0x00001810]    ; 0x00017700
000017e8  mov r0,r4
000017ea  bl  FUN_000019B6       ; *(base + 0x0C) = 0x17700
000017ee  movs r1,#0
000017f0  mov r0,r4
000017f2  bl  FUN_000019BA       ; *(base + 0x08) = 0
000017f6  movs r1,#1
000017f8  mov r0,r4
000017fa  bl  FUN_000019A2       ; set bit 0 at base + 0
000017fe  movs r1,#1
00001800  mov r0,r4
00001802  bl  FUN_0000198E       ; set bit 2 at base + 0
00001806  ldr r1,[0x00001818]    ; 0xE000E004
00001808  asrs r0,r4,#0x15       ; 0x40076000 >> 21 = 0x200
0000180a  str.w r0,[r1,#0x100]   ; *(0xE000E104) = 0x200
```

The literal values are:

```text
0x00001810 -> 0x00017700
0x00001814 -> 0x40076000
0x00001818 -> 0xE000E004
```

The small helper functions are simple register writes:

```c
FUN_000019B6(base, value): *(base + 0x0C) = value
FUN_000019BA(base, value): *(base + 0x08) = value
FUN_000019A2(base, 1):     *(base + 0x00) |= 1
FUN_0000198E(base, 1):     *(base + 0x00) |= 4
```

This is a clean timer/peripheral initialization pattern:

```text
base = 0x40076000
load/reload = 0x17700
counter/current = 0
enable bit = set
interrupt-enable bit = set
NVIC external IRQ enable = set
```

The NVIC write is important:

```text
0xE000E104 = NVIC ISER1
0x200      = bit 9 in ISER1
IRQ number = 32 + 9 = 41
```

Vector table offset for external IRQ 41:

```text
(16 + 41) * 4 = 228 = 0xE4
```

That matches the AP vector table entry:

```text
offset 0xE4 -> 0x0000181D -> handler 0x0000181C
```

Therefore the chain is now internally consistent:

```text
FUN_000017D4 configures 0x40076000 and enables IRQ 41
vector 0xE4 points IRQ 41 to FUN_0000181C
FUN_0000181C acknowledges 0x40076000+4 and decrements BA44 timeout counter
```

### 1. The AP vector table points to `FUN_0000181C`

The raw AP image vector table contains:

```text
raw vector offset 0xE4 -> 0x0000181D
```

Because Cortex-M vectors store Thumb entry addresses with bit 0 set, this resolves to:

```text
handler address = 0x0000181C
```

That is the same function that decrements the `BA44` timeout counter at `0x200000FE`.

### 2. `FUN_0000181C` acknowledges a peripheral interrupt

At entry, `FUN_0000181C` calls `FUN_00001980` with literal `0x40076000`:

```asm
00001820  ldr r0,[0x00001934]   ; 0x40076000
00001822  bl  0x00001980
```

`FUN_00001980` does:

```asm
00001980  ldr r1,[r0,#0x4]
00001982  bic r1,r1,#0x1
00001986  str r1,[r0,#0x4]
00001988  dsb #0xf
0000198c  bx lr
```

Decompile:

```c
*(uint32_t *)(base + 4) &= 0xfffffffe;
DataSynchronizationBarrier();
```

Interpretation:

```text
FUN_0000181C is not just a software task.
It clears/acknowledges a hardware interrupt flag on peripheral base 0x40076000.
```

### 3. `FUN_0000181C` decrements the exact `BA44` timeout counter

The same handler then decrements `0x200000FE`:

```asm
00001830  ldr  r0,[0x0000193c]    ; 0x200000FE
00001832  ldrh r1,[r0,#0x0]
00001834  cbz  r1,0x0000183c
00001836  ldrh r1,[r0,#0x0]
00001838  subs r1,r1,#0x1
0000183a  strh r1,[r0,#0x0]
```

This proves:

```text
BA44's timeout counter is driven by a hardware interrupt/tick, not by the BA44 polling loop itself.
```

### 4. The handler maintains several timebase counters

The handler also increments several counters:

```c
*0x20000070 += 1;
*0x200000A0 += 1;
*0x20000074 += 1;
if (*0x20000074 > 1999) *0x20000074 = 2000;
```

It also compares another counter against `0xFA0` (`4000`).

These are strong signs of a periodic system tick used for millisecond-class housekeeping:

```text
2000 ticks -> likely 2 seconds if tick = 1 ms
4000 ticks -> likely 4 seconds if tick = 1 ms
```

This is still an inference, not final proof.

### 5. Timer/peripheral init contains a suspicious 108k-class constant

`FUN_0000C260` loads these literal values:

```text
0x0000C34C -> 0x40088000
0x0000C350 -> 0x40068000
0x0000C354 -> 0x0001A7D0
0x0000C358 -> 0x5FA00001
0x0000C35C -> 0x43100618
0x0000C360 -> 0x4006A000
0x0000C364 -> 0x43100000
0x0000C368 -> 0x40080000
0x0000C36C -> 0x43004000
```

Relevant init sequence:

```asm
0000c26c  ldr  r0,[0x0000c350]   ; 0x40068000
0000c26e  movw r2,#0x35ca
0000c272  str  r2,[r0,#0x10]
0000c276  str  r4,[r0,#0x4]
0000c278  ldr  r2,[r0,#0x8]
0000c27e  orr  r2,r2,#0x5000
0000c282  str  r2,[r0,#0x8]
0000c284  ldr  r2,[0x0000c354]   ; 0x0001A7D0
0000c286  str  r2,[r0,#0x4]
0000c288  ldr  r2,[0x0000c358]   ; 0x5FA00001
0000c28a  str  r2,[r0,#0x0]
```

`0x0001A7D0` decimal:

```text
108,496
```

This is close to a 108 MHz clock divided into a 1 ms interval:

```text
108,000 cycles ~= 1 ms at 108 MHz
```

This is not enough by itself to prove the AP clock is 108 MHz, but it is consistent with a 1 ms system tick.

### 6. Peripheral identification remains unresolved

The offline search did not identify the exact MCU/peripheral map with high confidence.

Useful but not final external hints:

- The Cortex-M IRQ/peripheral searchable list is useful for matching vector offsets and peripheral bases, but the exact AP address map did not line up cleanly with one known SVD entry.
- Some NXP/Freescale SVD entries show vector offset `0xE4` as `PMC`, while our handler behaves like a periodic tick handler and acknowledges `0x40076000`. This means the public SVD match is not yet reliable enough to name the MCU.

External references used only as hints:

```text
https://gist.github.com/raplin/0b806d2e9eb82b8ab7a42cd2990710e0
https://www.chipselect.org/NXP%20Semiconductors--MKL46Z4.svd
https://www.chipselect.org/NXP%20Semiconductors--MKM35Z7.svd
```

## Current Timing Confidence

### Proven

```text
BA44 timeout is hardware-tick driven.
The decrement source is an interrupt handler.
The handler is in the vector table.
The handler acknowledges a hardware interrupt flag.
The handler updates multiple timebase counters.
```

### Strongly Inferred

```text
The tick is very likely millisecond-class.
BA44's 300 timeout is likely about 300 ms, or at least a short sub-second-class timeout.
```

Why:

1. `FUN_000017D4` configures the tick peripheral with `0x17700` (`96,000`) as the load/reload value.
2. A 96,000-count reload is exactly 1 ms if the peripheral clock is 96 MHz.
3. The handler clamps a counter at `2000`.
4. The handler uses/compares another counter against `4000`.
5. `2000` and `4000` are natural 2-second and 4-second software counters if the interrupt tick is 1 ms.
6. `FUN_0000C260` also uses `0x1A7D0` (`108,496`), close to 108k cycles, another value consistent with millisecond-scale hardware timing in this firmware.
7. Common SPI NOR 64KB erase times are often hundreds of milliseconds and can exceed a 300 ms window.

### Not Yet Proven

```text
The exact tick period.
The exact MCU/peripheral model.
That a live failed 64KB erase returns BA44=0 specifically due to WIP timeout.
```

## Impact On The 64KB Root Cause

The timeout hypothesis is now stronger than before:

```text
64KB erase can legitimately exceed a short timeout.
BA44 can report that timeout.
B4D0 discards the report.
Static corruption starts exactly at the first 64KB boundary.
Forcing static to 4KB erase fixed static corruption.
```

The remaining uncertainty is not whether the firmware has a timeout bug. It does.

The remaining uncertainty is:

```text
Is timeout the only physical trigger, or is it one trigger among WREN/WEL/protection problems?
```

## Next Step Before Patch Design

Patch design should still wait for one more offline pass:

1. Find all initialization and references for peripheral base `0x40076000`.
2. Determine whether `0x40076000` is a timer block, low-power timer, watchdog tick, or vendor-specific system timer.
3. Decode the reload/prescaler source if possible.
4. If the tick resolves to 1 ms or nearby, state the physical root cause as:

```text
AP firmware gives 64KB block erase roughly 300 ms, but 64KB erase can require longer; the 64KB helper then suppresses the timeout failure.
```

If tick duration cannot be resolved from firmware alone, the conservative conclusion remains:

```text
The AP 64KB path is definitely defective because it hides completion failure.
Timeout is the strongest physical trigger, but WREN/WEL/protection remain secondary candidates.
```


