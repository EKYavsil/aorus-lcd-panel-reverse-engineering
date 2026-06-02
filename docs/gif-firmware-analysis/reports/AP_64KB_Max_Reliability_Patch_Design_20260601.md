# AP 64KB Max-Reliability Patch Design - 2026-06-01

Scope: design only. No firmware updater was executed, no AP was flashed, no live DLL was deployed, no service was touched, and no panel command was sent.

## Goal

Design the safest staged patch path for the remaining GIF/static-media storage bug.

The current target is not the host GIF converter and not the host GCC upload flow. The target is the AP firmware's 64KB media erase path.

## Current Root-Cause Position

The strongest evidence points to this failure class:

```text
AP receives a valid upload command.
AP selects the 64KB erase path when F1[0x11] == 0x02.
The 64KB erase helper can time out or fail internally.
The helper then overwrites the failure with success.
The upload state machine continues and programs data into an incompletely erased region.
```

This explains the static symptom:

```text
static slot base          = 0x01300000
visible payload start    ~= 0x0130000C
first 64KB boundary      = 0x01310000
bytes before boundary    = 65524
screen fraction affected ~= upper 60% works, lower 40% remains black/stale
```

It also explains why the known static host fix works:

```text
F1[0x11] 0x02 -> 0x01
AP avoids 64KB helper and uses 4KB sector erase helper.
```

For GIF, forcing the host to use `0x01` is not equivalent, because `0x02` is also coupled to AP GIF timing/finalization state:

```text
timing_threshold = erase_count * 3000 + 3
```

Therefore the most coherent long-term fix is to preserve host/GIF `0x02` semantics and repair the AP-side 64KB implementation.

## Patch Sites

Address mapping:

```text
AP virtual address 0x0000B4D0 -> AP file offset 0x0000A4D0
AP virtual address 0x0000B534 -> AP file offset 0x0000A534
AP virtual address 0x0000B6CC -> AP file offset 0x0000A6CC
AP virtual address 0x0000B74E -> AP file offset 0x0000A74E
AP virtual address 0x0000BA4A -> AP file offset 0x0000AA4A
```

Key helpers:

| Function | Meaning | SPI opcode | Current bug |
|---|---|---:|---|
| `FUN_0000B4D0` | 64KB block erase | `0xD8` | calls poll, then forces success |
| `FUN_0000B910` | 4KB sector erase | `0x20` | propagates poll result correctly |
| `FUN_0000B6CC` | 256-byte page program | `0x02` | calls poll, then forces success |
| `FUN_0000BA44` | SPI WIP poll | `0x05` | timeout initialized to `300` ticks |

## Existing Known-Good Input

Original F1.4 AP:

```text
path   = <local-firmware-package-dir>\AP
size   = 58328
sha256 = DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
crc16 over 0x28..EOF = 0x4560
```

Previously staged option3 AP:

```text
path   = <local-gif-investigation-dir>\offline_firmware_patch_candidates_20260601\AP_option3_16x4k.bin
size   = 58328
sha256 = 821F6F3A809A0D7E3050B7284714EDF603B474C194E9E8F84D481B6CF872C643
crc16 over 0x28..EOF = 0x4BDD
diff   = one range, 0xA4D0..0xA4ED
```

The native updater computes and submits CRC16 dynamically over `AP[0x28..EOF]` using poly `0x1021`, init `0`. No static AP header checksum/signature has been identified.

## Candidate Designs

### Design 0: No AP change, host-only GIF workaround

Status: rejected for max reliability.

Reason:

- Static can be fixed host-side because static does not require the GIF-specific `0x02` timing/finalization state.
- GIF host-side `0x01` experiments produced unstable or invalid behavior.
- This path avoids the broken AP function but does not repair the root cause.

### Design A: Diagnostic return propagation in `B4D0`

Patch:

```text
AP address  = 0x0000B534
file offset = 0x0000A534
old bytes   = 01 20        ; movs r0,#1
new bytes   = 00 BF        ; nop
```

Effect:

```text
return FUN_0000BA44();
```

instead of:

```text
FUN_0000BA44();
return true;
```

Expected behavior:

- If the 64KB erase poll times out, the AP upload path should see failure instead of silent success.
- This is best as proof, not as final fix.
- It may turn lower-black corruption into visible upload failure.

Pros:

- Minimal 2-byte patch.
- No control-flow rewrite.
- Very strong diagnostic value.

Cons:

- Does not make the 64KB erase wait longer.
- Does not replace `0xD8`.
- Does not fix page-program forced success in `B6CC`.

Verdict:

```text
Use only as a diagnostic AP firmware candidate.
Not the preferred user-facing repair.
```

### Design B: Increase shared WIP poll timeout

Patch site:

```text
AP address  = 0x0000BA4A
file offset = 0x0000AA4A
old bytes   = 4F F4 96 70  ; mov.w r0,#0x12C = 300
```

Candidate replacements:

| Timeout | Bytes | Notes |
|---:|---|---|
| `1000` | `40 F2 E8 30` | conservative |
| `3000` | `40 F6 B8 30` | aligns with AP timing multiplier scale |
| `5000` | `41 F2 88 30` | highest tolerance among current simple candidates |

Recommended max-reliability subvariant:

```text
BA44 timeout = 3000
B4D0 forced-success NOP = yes
B6CC forced-success NOP = optional second stage, not first
```

Expected behavior:

- If the true defect is "64KB erase needs more than about 300 ms", this may repair the native `0xD8` path.
- If `0xD8` is semantically rejected, address-mode wrong, storage-protected, or controller-state dependent, timeout alone will not fix it.

Pros:

- Small patch.
- Keeps actual `0xD8` 64KB erase semantics.
- Useful to prove whether timeout is sufficient.

Cons:

- `BA44` is shared by 4KB erase, 64KB erase, and page program.
- Larger timeout may make real failures hang longer.
- It still leaves the 64KB operation dependent on the same fragile command path.

Verdict:

```text
Good controlled diagnostic.
Not the first user-facing final candidate unless we specifically want to prove native 0xD8 can be rescued.
```

### Design C: Replace `B4D0` with 16x 4KB sector erase loop

Patch site:

```text
AP address  = 0x0000B4D0
file offset = 0x0000A4D0
length      = 30 bytes
```

Patch bytes:

```text
30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1
04 F5 80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD
```

Assembly:

```asm
push {r4,r5,lr}
mov  r4,r0
movs r5,#0x10
loop:
mov  r0,r4
bl   0x0000b910
cbz  r0,fail
add.w r4,r4,#0x1000
subs r5,#0x1
bne  loop
movs r0,#0x1
pop  {r4,r5,pc}
fail:
movs r0,#0x0
pop  {r4,r5,pc}
```

Effect:

```text
Host/GIF still sends F1[0x11] == 0x02.
AP still takes the 64KB logical branch.
The internal implementation erases 16 reliable 4KB sectors.
AP timing/finalization side effects of 0x02 remain intact.
```

Pros:

- Best match to the successful static workaround while preserving GIF semantics.
- Reuses known-good `FUN_0000B910`.
- Propagates sector erase failure.
- Avoids native `0xD8` block erase entirely.
- Already staged as `AP_option3_16x4k.bin` with one clean diff range.

Cons:

- Firmware patch, not DLL patch.
- Slower than one block erase.
- Depends on safe updater delivery and recovery path.
- Does not by itself fix `B6CC` page-program forced success.

Verdict:

```text
Preferred functional repair candidate.
Highest chance of fixing GIF without host-side semantic damage.
```

### Design D: Design C plus page-program failure propagation

Additional patch candidate:

```text
AP address  = 0x0000B74E
file offset = 0x0000A74E
old bytes   = 01 20        ; movs r0,#1 after BA44
new bytes   = 00 BF        ; preserve BA44 result
```

Effect:

```text
Page program no longer lies about success.
```

Pros:

- Stronger storage-integrity behavior.
- If page program fails after an incomplete erase, AP should stop instead of advancing.

Cons:

- Changes another shared flash primitive.
- If current AP code accidentally depends on forced success for benign slow page-program cases, this can create failures.
- Should not be bundled into the first functional repair unless we intentionally choose strict integrity over minimality.

Verdict:

```text
Good second-stage integrity patch.
Do not combine with Design C in the first live AP test unless delivery/recovery confidence is very high.
```

## Maximum-Reliability Staging Recommendation

The safest engineering sequence is not "flash the final-looking patch first." It is a staged evidence path:

### Stage 1: Offline-only artifact hardening

Required before any live AP candidate:

1. Generate AP and AP1 for each candidate from the original AP/AP1.
2. Verify original bytes before patching.
3. Verify exact patch bytes after patching.
4. Verify AP and AP1 are byte-identical when expected.
5. Compute SHA256 and CRC16 over `0x28..EOF`.
6. Produce a manifest with offsets, old bytes, new bytes, hashes, and intended behavior.
7. Confirm no accidental diff outside intended ranges.

Do not flash anything in this stage.

### Stage 2: Updater delivery dry-run

Problem:

The original managed `FWUpgrade.exe` calls `DisposeResource()` and can rewrite embedded `AP` / `AP1` resources. Dropping loose AP files next to the updater is not reliable.

Required proof:

```text
The exact staged AP bytes are the bytes passed into SetAPFlashTable.
```

Acceptable offline/dry-run evidence:

- controlled harness path that logs selected AP/AP1 file hashes before any IAP call;
- modified package resource verification without execution;
- debugger/static proof that resource extraction will not overwrite the staged AP;
- native-call wrapper design that can run in no-panel/dry-run mode first.

Do not run a live flash until this is proven.

### Stage 3: Firmware updater erase-window decision

Separate issue:

```text
F1.4 AP size       = 0xE3D8
AP target range    = 0x1000..0xF3D7
original erase end = 0xEFFF
```

This means the native updater may not erase the full F1.4 AP write range.

Candidate native DLL patches:

| Candidate | Offset | Bytes | Meaning |
|---|---:|---|---|
| exact AP end | `0x2A1C` | `C7 44 24 2C D7 F3 00 00` | erase to `0xF3D7` |
| sector end | `0x2A1C` | `C7 44 24 2C FF FF 00 00` | erase to `0xFFFF` |

Max-reliability recommendation:

```text
Prefer sector end 0xFFFF if a modified native updater is required.
```

Reason:

- flash erases are normally sector-aligned;
- exact `0xF3D7` may still rely on bootloader rounding semantics;
- `0xFFFF` covers the full containing sector for the F1.4 AP.

But this patch changes a signed DLL and produces Authenticode `HashMismatch`. A live plan must account for whether the updater or Windows policy rejects modified native DLLs.

### Stage 4: First functional AP candidate

Preferred first functional candidate:

```text
Design C only: B4D0 = 16x B910
Do not also patch B6CC yet.
Do not also increase BA44 timeout yet.
```

Why:

- It changes only the defective 64KB helper.
- It preserves host/GIF `0x02` semantics.
- It reuses the 4KB erase helper already proven by the static fix.
- It avoids the unknown native `0xD8` behavior completely.

Expected success signal:

```text
GIF upload completes.
GIF does not leave loading/white/black stale state.
Large static 0x02 path would also no longer lower-black if tested.
```

Expected failure signal:

```text
Upload aborts or panel remains unchanged if any 4KB erase fails.
This is safer than silent partial corruption.
```

### Stage 5: Second-stage integrity candidate

Only if Design C improves erase behavior but GIF still corrupts:

```text
Design C + B6CC return propagation
```

Purpose:

- Catch page-program failures instead of silently accepting them.
- Separate erase failure from program failure.

### Stage 6: Native 0xD8 salvage candidate

Only if we specifically want to prove whether `0xD8` can be repaired:

```text
Design B with BA44 timeout 3000
plus B4D0 return propagation
```

This is useful for vendor-level diagnosis:

```text
If timeout 3000 + propagation fixes 0xD8, then the original root cause is too-short WIP timeout.
If it still fails, the 0xD8 path is not only a timeout problem.
```

This is not the preferred user-facing path because Design C is more deterministic.

## Live-Test Preconditions

No live AP test should happen until all are true:

1. Known-good official firmware recovery has been tested and documented.
2. The panel is currently recoverable through official FW update if a candidate fails.
3. Original `AP`, `AP1`, `GvLcdFwUpdate.dll`, and full updater package are backed up with SHA256.
4. Patched AP/AP1 hashes are generated from clean originals.
5. Delivery path proves exact patched bytes are flashed, not overwritten extracted resources.
6. Updater erase window for F1.4 is handled or proven irrelevant.
7. One candidate is tested at a time.
8. A failure means immediate stop and recovery, not stacking more patches.

## Stop Conditions

Stop and recover if any of these occur:

- updater reports `I2CIAPChangeToErase12ByteMode fail`;
- updater reports CRC submit failure;
- updater reports AP flash failure;
- panel remains sideways/blank/unresponsive after restart;
- panel no longer changes display modes;
- official firmware recovery no longer works on first retry.

## Recommended Candidate Order

| Order | Candidate | Purpose | Expected risk | Expected value |
|---:|---|---|---|---|
| 1 | offline manifest/dry-run only | prove exact bytes and delivery | none/live | mandatory |
| 2 | Design C | functional fix candidate | medium | highest |
| 3 | Design C + B6CC propagation | stricter integrity | medium/high | high diagnostic |
| 4 | Design B + B4D0 propagation | native 0xD8 salvage proof | medium | vendor proof |
| 5 | Design A only | pure failure-exposure proof | medium | diagnostic only |

## Bottom Line

The maximum-reliability patch design is:

```text
Do not alter host GIF metadata.
Do not force GIF to F1[0x11] == 0x01.
Patch AP's logical 64KB helper so the 0x02 branch internally performs sixteen verified 4KB sector erases.
Flash it only after updater delivery and recovery are proven.
```

The exact preferred first functional AP-code mutation is:

```text
file offset 0xA4D0
replace 30 bytes with:
30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1
04 F5 80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD
```

This keeps GCC/GIF protocol behavior intact while removing the specific AP implementation path that matches the static lower-black boundary and the GIF storage corruption class.



