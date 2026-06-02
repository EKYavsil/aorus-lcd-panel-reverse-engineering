# AP GIF Target 0 / Storage 2 Notes - 2026-05-31

Scope: offline AP firmware text-dump analysis only. No live device action.

## Files Read

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F14_slot_parser.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_function_dump_AP_F14_pkg.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_function_dump_modern_F12_AP.txt
```

## Parser Findings

The F1 parser decodes target/destination from bytes `5..8`:

```c
uVar24 = DAT_000081cc[8] |
         pbVar7[5] << 0x18 |
         pbVar7[6] << 0x10 |
         pbVar7[7] << 8;
*DAT_00008204 = uVar24;
```

It decodes `F1[0x11]` as the erase mode:

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
```

Target mapping:

```c
if (uVar24 == 0) {
    *DAT_00008214 = 0;
    *pbVar4 = 1;
}
else if (uVar24 == 0x01320000) {
    *DAT_00008218 = 0;
    *pbVar4 = 2;
}
else if (uVar24 == 0x01300000) {
    *DAT_0000821c = 0;
    *pbVar4 = 3;
}
```

So GIF target zero is intentional AP behavior:

```text
target 0x00000000 -> internal slot 1
```

Storage type mapping:

```c
if (DAT_000081cc[9] == 2) {
    *DAT_00008224 = 2;
    old/new frame count and delay fields are updated
}
else {
    *DAT_00008224 = 1;
}
```

This confirms storage type `2` is an explicit animation/GIF path.

## Timing Difference Around F1[0x11]

The parser initializes upload state:

```c
*DAT_00008240 = 1;   // erase state flag
*DAT_00008244 = 2;

if (bVar30 == 0) {
    *DAT_00008244 = 1;
    *DAT_00008240 = 0;
}
else if (bVar30 == 2) {
    *DAT_00008248 = (uint)uVar26 * 3000 + 3;
}
```

Important consequence:

```text
F1[0x11] == 0x02 sets DAT_00008248 / DAT_0000d168 timing threshold.
F1[0x11] == 0x01 does not set it in this parser block.
```

This is the strongest offline reason why the static byte change should not be copied directly to GIF. On GIF, `F1[0x11]=0x02` may be both an erase selector and a pacing/state input for long animation writes.

## Target 0 Remap

The parser contains:

```c
if ((cVar25 == 1) && (uVar24 == 0)) {
    *DAT_00008204 = 0x01000000;
}
```

Inference:

```text
GIF target 0 may be remapped internally to 0x01000000 under a parser state condition.
```

This needs deeper mapping. It strongly suggests the visible host target `0` is not the physical flash write base by itself.

## Upload Loop Slot Checks

The upload loop has explicit relative-limit checks for slots 2 and 3:

```c
if (slot == 2) {
    relative = base + offset - 0x01320000;
}
else if (slot == 3) {
    relative = base + offset - 0x01300000;
}
```

For slot 1, the code path bypasses these specific `0x01320000` / `0x01300000` relative checks.

Inference:

```text
Slot 1/GIF has different boundary semantics than static slot 3.
```

This does not prove slot 1 has no limits. It means the static-slot reasoning around `0x01300000..0x01320000` cannot be reused directly for GIF.

## Mode 5 Display Handler

The AP dump has a display-state switch where mode `5` has its own case:

```c
case 5:
    state->active = 1;
    *puVar8 = DAT_00002364;
    *DAT_00002370 = DAT_0000236c;
    FUN_00009bb8();
    FUN_00001e14();
    return;
```

Other modes call different helper paths:

- mode `1` calls `FUN_00002cc0()`
- mode `2` / `3` call `FUN_00002e9c()` plus different output helpers
- mode `5` calls `FUN_00009bb8()`

Inference:

```text
GIF display mode is not just static display with another slot.
It has a dedicated animation/display routine.
```

## Current Conclusions

1. GIF target `0` is real AP protocol behavior, not a simple bad destination.
2. GIF uses internal slot `1`, while static image uses slot `3`.
3. GIF storage type `2` activates frame count / delay handling.
4. `F1[0x11]=0x02` also sets an AP timing threshold; changing it to `0x01` likely changes more than erase granularity for long GIF uploads.
5. Static slot boundary checks do not directly apply to GIF slot 1.
6. Mode `5` has a separate display routine, so post-upload display behavior also needs separate analysis.

## Next Offline Targets

1. Find `FUN_00009bb8()` body and callers. This is currently the best candidate for GIF/animation display handling.
2. Resolve `DAT_00002364` and `DAT_0000236c` if possible; they may point to animation buffer/table state.
3. Track `DAT_00008204` / upload base after the `0x01000000` remap.
4. Find all references to slot id `1` state around `DAT_00008214`.
5. Find max animation payload/page/frame limits for slot 1.

## Working Diagnosis

```text
GIF failure should now be investigated as target0 -> slot1 -> storage2 -> mode5 animation state.
The static sector-erase fix solved slot3/static, but it is not a safe model for GIF.
```

## Immediate Search Result

A direct text search in the available F1.4 and modern F1.2 AP dumps found the mode-5 call site, but not a decompiled function body for `FUN_00009bb8()` in the current dump text:

```text
caller from=00009bba type=UNCONDITIONAL_CALL fn=FUN_00009bb8@00009bb8
mode 5 call site invokes FUN_00009bb8()
```

This means the next pass likely needs a targeted Ghidra export for `FUN_00009bb8()` and nearby functions/data, rather than relying on the existing broad dump.


