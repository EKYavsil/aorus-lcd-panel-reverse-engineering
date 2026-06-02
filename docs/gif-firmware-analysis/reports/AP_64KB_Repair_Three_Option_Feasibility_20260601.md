# AP 64 KB Repair Feasibility: Three Options

Date: 2026-06-01

Scope: offline feasibility only. No AP firmware was modified, no firmware updater was run, no live DLL/service/panel action was taken.

## Objective

Evaluate whether we can repair the AP firmware's broken 64 KB media erase path instead of avoiding it from the host DLL.

The three candidate repairs:

1. `FUN_0000B4D0` propagates the status-poll result.
2. `FUN_0000BA44` timeout is increased so 64 KB erase can finish.
3. `FUN_0000B4D0` keeps `F1[0x11] == 0x02` semantics but internally performs 16 reliable 4 KB sector erases.

## Evidence Inputs

Ghidra patch-site dump:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_patch_site_dump_AP_F14_pkg_patchsites.txt
```

Ghidra in-memory assembly feasibility output:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_assembly_feasibility_AP_F14_pkg_asm.txt
```

Firmware carve where the AP code bytes were found:

```text
<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_firmware_package_extracts\F14_AP_carve_26340.bin
<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_firmware_package_extracts\F14_AP_carve_84673.bin
```

Both carves are identical:

```text
length = 58328 bytes
SHA256 = DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
```

Observed address mapping in the carve:

```text
Ghidra/AP address 0x0000B4D0 -> file offset 0x0000A4D0
Ghidra/AP address 0x0000B910 -> file offset 0x0000A910
Ghidra/AP address 0x0000BA44 -> file offset 0x0000AA44
```

This is consistent with the IAP updater range previously identified as roughly `0x1000..0xEFFF`.

## Existing Broken Path

### `FUN_0000B4D0`: 64 KB block erase

Relevant instruction tail:

```text
0000B52C  F8 F7 BC F9  bl 0x000038A8
0000B530  00 F0 88 FA  bl 0x0000BA44
0000B534  01 20        movs r0,#0x1
0000B536  D2 E7        b 0x0000B4DE
```

Meaning:

```text
call status poll
overwrite poll result with success
return success
```

File offset for the critical overwrite:

```text
AP address 0x0000B534 -> carve offset 0x0000A534
current bytes: 01 20
```

### `FUN_0000B910`: 4 KB sector erase

Relevant instruction tail:

```text
0000B96C  F7 F7 9C FF  bl 0x000038A8
0000B970  00 F0 68 F8  bl 0x0000BA44
0000B974  00 28        cmp r0,#0x0
0000B976  D2 D0        beq 0x0000B91E
0000B978  01 20        movs r0,#0x1
0000B97A  D0 E7        b 0x0000B91E
```

Meaning:

```text
call status poll
if poll failed, return 0
if poll succeeded, return 1
```

This is the behavior the 64 KB helper lacks.

### `FUN_0000BA44`: status poll

Relevant start:

```text
0000BA44  2D E9 F0 41  push {r4,r5,r6,r7,r8,lr}
0000BA48  17 4D        ldr r5,[0x0000BAA8]
0000BA4A  4F F4 96 70  mov.w r0,#0x12C
0000BA4E  28 80        strh r0,[r5,#0x0]
```

`0x12C == 300`. The poll function initializes a 300-tick timeout, reads SPI status `0x05`, waits until WIP clears, and returns:

```text
0 on timeout/failure
1 on ready
```

## Option 1: Propagate 64 KB Poll Result

### Concept

Change:

```text
FUN_0000BA44();
return 1;
```

to:

```text
return FUN_0000BA44();
```

### Minimal Patch Shape

At AP address:

```text
0x0000B534
```

current instruction:

```text
01 20    movs r0,#0x1
```

candidate conceptual replacement:

```text
00 BF    nop
```

Reason:

- `bl 0x0000BA44` returns its result in `r0`.
- `movs r0,#1` is the instruction that destroys the result.
- NOP preserves `r0`, then the existing branch returns.

### Feasibility

| Factor | Assessment |
|---|---|
| Patch size | 2 bytes |
| Requires trampoline | No |
| Requires new code cave | No |
| Preserves function layout | Yes |
| Changes only 64 KB erase helper | Yes |
| Fixes silent success | Yes |
| Ensures 64 KB erase completes | No |
| Expected live behavior if 64 KB still times out | Upload should fail instead of silently corrupting storage |

Assembler confirmation:

```text
address=0x0000B534
nop -> 00 BF
```

### Risk

Low technical patch risk, high product-behavior uncertainty.

This option is excellent for diagnosis and vendor proof:

```text
If the 64 KB erase poll fails, AP should stop lying.
```

But it may not make GIF/static uploads succeed. It may turn silent corruption into visible upload failure.

### Verdict

```text
Feasible: HIGH
Usefulness as final fix: MEDIUM/LOW
Usefulness as proof: HIGH
```

## Option 2: Increase Status Poll Timeout

### Concept

Change the timeout in `FUN_0000BA44`:

```text
300 ticks -> larger value
```

Critical instruction:

```text
0000BA4A  4F F4 96 70  mov.w r0,#0x12C
```

Current:

```text
0x12C = 300
```

Potential targets:

| Candidate | Decimal | Rationale |
|---:|---:|---|
| `0x03E8` | 1000 | conservative, about 3.3x current |
| `0x0BB8` | 3000 | matches AP's upload timing multiplier and is 10x current |
| `0x1388` | 5000 | more tolerant for slow 64 KB erase |

Assembler-confirmed candidate encodings:

| Target timeout | Instruction | Bytes |
|---:|---|---|
| 1000 | `movw r0,#0x03e8` | `40 F2 E8 30` |
| 3000 | `movw r0,#0x0bb8` | `40 F6 B8 30` |
| 5000 | `movw r0,#0x1388` | `41 F2 88 30` |

These are all 4-byte replacements for the current 4-byte instruction:

```text
0000BA4A  4F F4 96 70  mov.w r0,#0x12C
```

### Feasibility

| Factor | Assessment |
|---|---|
| Patch size | likely 4 bytes if immediate can be encoded as one Thumb-2 `mov.w`/`movw` |
| Requires trampoline | Probably no for many values |
| Affects only 64 KB erase | No |
| Affects page program poll | Yes |
| Affects sector erase poll | Yes |
| Can make 64 KB erase actually finish | Possibly |
| Can mask other failures longer | Yes |

### Important Limitation

This option assumes the root issue is:

```text
0xD8 erase is accepted but needs longer than the current poll window
```

It will not fix:

- second `0xD8` command lost or ignored;
- flash/controller rejects `0xD8` for this region/state;
- address-mode or protection issue;
- page program failure after erase.

Old host wait tests do not disprove this option, because the AP poll happens inside AP firmware. Host-side waiting after F1 cannot extend `FUN_0000BA44`'s internal timeout.

### Risk

Medium technical risk.

Increasing a shared poll timeout may make failures hang longer. It is less invasive than rewriting `B4D0`, but less targeted than option 1 and less deterministic than option 3.

### Verdict

```text
Feasible: MEDIUM/HIGH
Usefulness as final fix: MEDIUM
Best if root trigger is timeout-too-short
```

## Option 3: Preserve 0x02 Semantics But Internally Use 16x 4 KB Sector Erase

### Concept

Keep the host and AP parser contract unchanged:

```text
F1[0x11] == 0x02
```

So GIF still gets:

```text
DAT_0000D168 = erase_count * 3000 + 3
```

But replace the implementation of `FUN_0000B4D0(address)`:

```text
old: send one 0xD8 64 KB erase
new: call FUN_0000B910(address + i*0x1000) for i=0..15
```

Conceptual pseudocode:

```c
int FUN_0000B4D0(uint32_t address) {
    for (int i = 0; i < 16; i++) {
        if (FUN_0000B910(address + i * 0x1000) == 0) {
            return 0;
        }
    }
    return 1;
}
```

### Why Not Patch The Main Loop Instead?

The main loop currently does:

```c
if (erase_selector == 2) {
    ok = FUN_0000B4D0(base + offset);
    if (!ok) return;
    offset += 0x10000;
}
else {
    ok = FUN_0000B910(base + offset);
    if (!ok) return;
    offset += 0x1000;
}
```

Changing only the call from `B4D0` to `B910` would be wrong:

```text
erase count is still computed in 64 KB units
offset still advances by 0x10000
only one 4 KB sector per 64 KB block would be erased
```

So the correct internal-sector design must either:

1. rewrite `B4D0` to do 16 sector erases; or
2. rewrite both parser erase-count logic and main-loop offset logic.

Option 1 is cleaner.

### Feasibility

The original `FUN_0000B4D0` function body occupies:

```text
0x0000B4D0..0x0000B543
```

That is about `0x74` bytes including literal pool area.

A compact Thumb implementation of a 16-iteration loop may fit in that space:

```asm
push {r4,r5,lr}
mov  r4,r0
movs r5,#16
loop:
mov  r0,r4
bl   FUN_0000B910
cbz  r0,fail
add.w r4,r4,#0x1000
subs r5,#1
bne  loop
movs r0,#1
pop  {r4,r5,pc}
fail:
movs r0,#0
pop  {r4,r5,pc}
```

If exact encoding does not fit, a trampoline/code-cave would be required. I have not yet selected a code cave or generated final bytes.

Assembler feasibility now confirms that the compact loop does fit. The shorter `cbz` variant is 30 bytes:

```text
address=0x0000B4D0
length=30
bytes=
30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1 04 F5
80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD
```

Assembly:

```asm
push {r4,r5,lr}
mov r4,r0
movs r5,#0x10
loop:
mov r0,r4
bl 0x0000b910
cbz r0,fail
add.w r4,r4,#0x1000
subs r5,#0x1
bne loop
movs r0,#0x1
pop {r4,r5,pc}
fail:
movs r0,#0x0
pop {r4,r5,pc}
```

The non-`cbz` variant is also compact at 32 bytes:

```text
30 B5 04 46 10 25 20 46 00 F0 1A FA 00 28 05 D0
04 F5 80 54 01 3D F6 D1 01 20 30 BD 00 20 30 BD
```

This means option 3 likely does not need a trampoline. It can overwrite the start of `FUN_0000B4D0` and pad the unused remainder of the original function body with NOPs, subject to final binary-layout and literal-pool checks.

### Benefits

This is the most coherent repair for GIF:

| Requirement | Satisfied? |
|---|---|
| Keep `F1[0x11] == 0x02` | Yes |
| Preserve GIF timing/finalization side effect | Yes |
| Avoid `0xD8` 64 KB erase | Yes |
| Use known-good `0x20` sector erase helper | Yes |
| Propagate sector erase failures | Yes |
| Avoid changing host DLL/GCC behavior | Yes |

### Risks

| Risk | Notes |
|---|---|
| Firmware patch complexity | Higher than options 1/2. |
| Timing | 16 sector erases may be slower than one block erase, but each sector erase waits internally and services AP loop via `FUN_0000C370` through the poll helper. |
| Code size | Assembler check says the compact loop fits in 30-32 bytes. |
| Firmware update safety | Requires knowing package checksum/signature and recovery path before any live flash. |
| Side effects | Any AP feature that intentionally wanted a real `0xD8` block erase would now get sector erases, but only through this helper. In current evidence this helper is the 64 KB erase helper used by upload. |

### Verdict

```text
Feasible: MEDIUM
Usefulness as final fix: HIGH if AP firmware patching is safe
Best technical design for GIF because it preserves 0x02 semantics
```

## Firmware Package / Flashing Feasibility

This is the gating problem for all AP firmware options.

Known:

- The AP firmware bytes are present in carved package images.
- The AP code address to file-offset mapping is coherent.
- The updater/IAP path writes AP firmware chunks in the `0x1000..0xEFFF` range.
- Existing updater logs include `I2CIAPChangeToErase12ByteMode fail`, proving this path can fail before erase mode.

Unknown:

- Whether the firmware package has an outer checksum/signature.
- Whether the updater validates AP payload checksum before flashing.
- Whether the AP bootloader validates checksum/signature after flashing.
- Whether a bad AP flash can be recovered without vendor tools.
- Whether the two carved F1.4 AP payloads are the exact input that updater flashes or extracted copies after transformation.

Until those are answered, AP firmware patching remains lab-only/high-risk.

## Overall Ranking

| Option | Patch complexity | Safety if flashed | Chance of fixing static | Chance of fixing GIF | Diagnostic value |
|---|---:|---:|---:|---:|---:|
| 1. Propagate `B4D0` poll result | Low | Medium | Low/Medium | Low/Medium | High |
| 2. Increase `BA44` timeout | Low/Medium | Medium | Medium | Medium | Medium |
| 3. `B4D0` = 16x `B910` | Medium/High | Medium/Low until flashing path known | High | High | High |

## Current Recommendation

Do not attempt live AP firmware flashing yet.

Best next offline steps:

1. Generate exact Thumb bytes for option 1 and option 2 candidate patches.
2. Prototype option 3 assembly offline and verify whether it fits inside `FUN_0000B4D0`.
3. Analyze the firmware package/updater checksum path before any modified AP image is considered.
4. If a live test is ever considered, start with option 1 only as a diagnostic patch because it is the smallest reversible AP-code mutation conceptually. But this still requires a safe AP flashing/recovery plan first.

## Bottom Line

Yes, the 64 KB path is theoretically repairable.

The cleanest technical repair is not "avoid 64"; it is:

```text
keep the 0x02 upload semantics,
but make AP's 64 KB erase helper either wait correctly or internally use verified sector erases.
```

The blocker is not understanding anymore. The blocker is safe AP firmware patch delivery and recovery.


