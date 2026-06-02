# AP Native 64KB Repair-First Patch Design - 2026-06-01

Scope: design only. No firmware updater was executed, no AP was flashed, no DLL was deployed, no service was touched, and no panel command was sent.

## Correction Of Direction

The primary goal is not to bypass the 64KB path with sixteen 4KB sector erases.

The primary goal is:

```text
repair the native AP 64KB erase path itself
```

The 16x4KB design remains valuable as a fallback and as proof that the failing component is the native 64KB helper, but it is not the preferred root-cause fix for this branch of the investigation.

## Current Native 64KB Failure Model

Observed AP firmware path:

```text
F1[0x11] == 0x02
  -> logical erase unit = 0x10000
  -> AP calls FUN_0000B4D0(address)
  -> B4D0 sends SPI NOR 64KB block erase opcode 0xD8
  -> B4D0 calls FUN_0000BA44() to poll WIP via status opcode 0x05
  -> B4D0 overwrites BA44 result with success
```

The suspicious tail:

```asm
0000B530  bl   0x0000BA44
0000B534  movs r0,#0x1      ; destroys poll result
0000B536  b    return
```

So the firmware currently cannot distinguish:

```text
64KB erase completed
64KB erase timed out
64KB erase command was rejected
64KB erase never started
```

All are reported upward as success.

## What "Real 64KB Repair" Means

A real native repair should keep:

```text
F1[0x11] == 0x02
SPI opcode == 0xD8
erase increment == 0x10000
GIF timing/finalization side effect
```

and fix only the defective implementation around:

```text
status polling
timeout budget
failure propagation
possibly write-enable / address-mode / protection preparation
```

## Native 64KB Hypotheses To Separate

### H1: Timeout Too Short

`FUN_0000BA44` initializes its timeout to `300`.

Timer evidence suggests this is likely about 300 ms. Public SPI NOR timings show 64KB block erase can exceed this, while page program and many 4KB erases can complete inside it.

If this is true, the repair is:

```text
increase BA44 timeout
make B4D0 propagate BA44 result
```

### H2: Poll Result Is Correct But Ignored

Even if the timeout value is not the only issue, `B4D0` definitely discards a real failure signal.

If this is true, the repair is at minimum:

```text
B4D0 must not force success
```

This may not make uploads succeed, but it prevents silent storage corruption.

### H3: 0xD8 Command Is Issued Too Early / State Not Ready

Possible failure:

```text
AP sends the next erase/program operation while the flash or controller is still busy.
```

Host-side waits after F1 did not fix this because the critical wait is inside AP firmware, between SPI operations.

If this is true, the repair is:

```text
BA44 must wait long enough and B4D0 must honor it
```

### H4: 0xD8 Requires Different Preparation

Potential preparation issues:

- write-enable not latched for block erase;
- wrong 3-byte / 4-byte address mode at high address like `0x01300000`;
- block-protection state affects the second 64KB region;
- controller requires a dummy wait or status clear before 0xD8.

If this is true, timeout alone may not fix the issue. We then need deeper AP disassembly around:

```text
FUN_0000B4D0  ; 64KB erase
FUN_0000B910  ; 4KB erase
FUN_0000B990  ; suspected address-mode / setup helper
FUN_000038A8  ; SPI transfer helper
write-enable helper before erase
status register read/write helpers
```

## Native 64KB Repair Candidate Order

### Candidate N1: B4D0 Return Propagation Only

Purpose: diagnostic integrity patch.

Patch:

```text
AP address  = 0x0000B534
file offset = 0x0000A534
old bytes   = 01 20        ; movs r0,#1
new bytes   = 00 BF        ; nop
```

Effect:

```text
return BA44_result;
```

Expected result:

- If 64KB erase times out, upload should fail instead of silently corrupting.
- If the panel still corrupts, then failure may be outside this exact return site.
- If upload succeeds, the original forced-success instruction was hiding a transient but recoverable status.

Value:

```text
highest proof value, low code-change size, not necessarily a user-facing fix
```

### Candidate N2a: BA44 Timeout 1000 + B4D0 Propagation

Purpose: first real native 64KB repair.

Patch A:

```text
AP address  = 0x0000BA4A
file offset = 0x0000AA4A
old bytes   = 4F F4 96 70  ; 300
new bytes   = 40 F2 E8 30  ; 1000
```

Patch B:

```text
AP address  = 0x0000B534
file offset = 0x0000A534
old bytes   = 01 20
new bytes   = 00 BF
```

Effect:

```text
64KB erase gets a moderately longer WIP poll window.
B4D0 no longer lies about failure.
```

Why 1000 first:

- It is 3.33x the original timeout, not 10x.
- The static symptom suggests the original 300 may be near the edge, not orders of magnitude too short.
- It reduces the chance that another AP scheduler/state-machine assumption is disturbed.
- It is the cleanest first vendor-quality native repair attempt.

Expected result if timeout is root cause:

```text
static original 0x02 path should no longer lower-black
GIF should improve without changing GIF metadata
```

Expected result if root cause is not timeout:

```text
upload should fail more honestly or still corrupt less often
```

Value:

```text
best first "true 64KB repair" candidate
```

### Candidate N3: BA44 Timeout 5000 + B4D0 Propagation

Purpose: prove whether 3000 was still too short.

Patch A:

```text
AP address  = 0x0000BA4A
file offset = 0x0000AA4A
old bytes   = 4F F4 96 70
new bytes   = 41 F2 88 30  ; 5000
```

Patch B:

```text
0xA534: 01 20 -> 00 BF
```

Use only if N2 fails in a way that still looks timeout-like.

Risk:

- Shared `BA44` waits longer for all flash operations.
- A real dead operation can stall longer before returning failure.

### Candidate N4: Native 64KB Preparation Repair

Purpose: fix 0xD8 preparation if timeout is not enough.

This is not ready for byte patching yet. It requires deeper disassembly comparison between `B4D0` and `B910`.

Questions:

1. Does `B4D0` call the same write-enable helper as `B910`?
2. Does `B4D0` enter 4-byte address mode before high-address erase?
3. Does `B4D0` send all address bytes correctly for `0x01300000` and `0x01310000`?
4. Does `B910` perform a pre-status check that `B4D0` omits?
5. Does `B4D0` use `0xD8` in a flash part/state that expects a different block-erase opcode?
6. Does the second 64KB boundary fail because of region protection or alignment?

Until these are answered, N4 is an investigation target, not a patch.

### Candidate Fallback: B4D0 = 16x B910

Status: fallback only.

Use only if:

```text
native 0xD8 is proven unreliable or semantically incompatible
```

It is not the main root-cause repair path because it avoids the 64KB operation rather than repairing it.

## Required Offline Work Before N2/N3

Before any live AP firmware test, we should generate a clean patch matrix offline:

| Candidate | Offset changes | Purpose |
|---|---|---|
| N1 | `0xA534` only | proof: expose failure |
| N2 | `0xAA4A`, `0xA534` | main native 64KB repair |
| N3 | `0xAA4A`, `0xA534` | longer native 64KB repair |
| N2P | `0xAA4A`, `0xA534`, optional `0xA74E` | native erase repair plus page-program integrity |

For each:

1. Verify original AP/AP1 SHA256.
2. Verify original bytes at all patch offsets.
3. Apply patch to AP and AP1.
4. Verify only intended ranges changed.
5. Compute SHA256.
6. Compute CRC16 over `0x28..EOF`.
7. Produce manifest.

No firmware flash in this stage.

## Test Interpretation Matrix

If N2a succeeds:

```text
Root cause is very likely BA44 timeout too short plus B4D0 forced-success masking.
```

If N2a fails but a larger timeout succeeds:

```text
Root cause is still timeout, but required erase duration is above 1000 ticks.
```

If N2/N3 return visible upload failure instead of corruption:

```text
Return propagation works; 0xD8 still fails for a reason other than timeout budget.
Next target is B4D0 preparation/address/protection.
```

If N2/N3 still silently corrupt:

```text
Either B4D0 is not the only failing forced-success site, or the corruption occurs after erase during page program/display finalization.
Then B6CC propagation and readback/verify path become important.
```

If N2/N3 hang or destabilize panel:

```text
Longer BA44 timeout may be interacting badly with AP scheduler/state machine.
Stop and recover with official firmware.
```

## Current Recommendation

The next design branch should be:

```text
Native 64KB repair first:
1. N1 for proof, or
2. N2a as first real repair candidate.
```

Given your goal is to actually repair the 64KB path, the best first serious candidate is:

```text
N2a = BA44 timeout 1000 + B4D0 return propagation
```

Do not use 16x4KB as the main patch unless native `0xD8` repair is proven impossible or unsafe.

## Bottom Line

The root-fix direction is:

```text
keep 0xD8
keep 0x10000 erase units
keep GIF 0x02 timing semantics
increase the AP internal WIP poll budget
stop B4D0 from forcing success
then investigate B4D0 preparation if timeout is not enough
```


