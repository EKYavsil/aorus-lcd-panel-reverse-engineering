# AP F2 Finalize Success Criteria Analysis - 2026-05-31

Scope: offline analysis only. No live panel command, no updater execution, no service control, and no GCC file modification.

## Goal

Determine whether AP `F2 02` finalize validates actual custom static media flash contents, or only updates AP display/session state.

## Result

`F2 02` does not appear to verify flash content, page-program success, or remaining chunk integrity.

The observed AP parser branch for `F2 CB 55 AC 38 ... 02` updates mode/slot/display/session flags. It does not reference:

- `DAT_0000d184` / `0x20000318` remaining payload chunk count
- `DAT_0000d174` / `0x20000310` upload offset
- `FUN_0000b6cc` page program result history
- `FUN_0000ba44` SPI status poll result
- any media readback/verify routine

Therefore, `F2 finalize success` means the AP accepted and processed the finalize command. It does not prove that the static media slot contains the intended bytes.

## Parser Branch

The relevant parser branch in `FUN_00007db8`:

```c
else if (((*DAT_000081cc == 0xf2) && (DAT_000081cc[1] == 0xcb)) &&
        ((DAT_000081cc[2] == 0x55 &&
         (((DAT_000081cc[3] == 0xac && (DAT_000081cc[4] == 0x38)) &&
           (DAT_000081cc[5] == 2)))))) {
    in_r3[1] = 0;
    *puVar23 = 0;
    *DAT_00009068 = 0;
    *DAT_0000904c = 0;

    bVar30 = in_r3[2];
    *pbVar6 = bVar30;

    if ((bVar30 == 4) || (bVar30 == 5)) {
        *slot_flag = 1;
    }
    else if (bVar30 == 6) {
        ...
    }

    bVar30 = *in_r3;
    if ((bVar30 == 1) || (bVar30 == 3) || (bVar30 == 2)) {
        *target_flag = 1;
    }

    *DAT_000091d0 = 1;
}
```

This branch is a state/display commit path, not a storage integrity check.

## What It Does Not Check

I found no check in this branch equivalent to:

```c
if (remaining_chunks != 0) fail;
if (last_program_status_failed) fail;
if (page_program_timeout_seen) fail;
if (readback_crc != expected_crc) fail;
if (offset != expected_size) fail;
```

The branch does not call:

- `FUN_0000b6cc`
- `FUN_0000ba44`
- `FUN_0000b54c`
- `FUN_0000b760`

So it neither programs nor reads media storage during finalize.

## Relationship To Page Program Failure

This combines with the previous critical finding:

```c
FUN_0000b6cc(...) {
    ...
    FUN_0000ba44();  // status poll result ignored
    return 1;
}
```

The upload main loop decrements remaining chunks after `FUN_0000b6cc()` returns success:

```c
iVar10 = FUN_0000b6cc(staging, base + offset, 0x100);
if (iVar10 == 0) {
    return;
}
offset += 0x100;
remaining_chunks -= 1;
if (remaining_chunks == 0) {
    payload_pending = 0;
}
phase = 0;
```

Because `FUN_0000b6cc()` can return success even if its internal status poll timed out, the AP can internally advance:

```text
offset
remaining_chunks
phase
```

without proving the flash page was programmed.

Then `F2 02` can mark the slot/display state as finalized.

## Static Slot State Effect

Prior AP reports already identified this intended state flow:

```text
F1 destination 0x01300000:
    state+0x43 = 0
    state+0x4f = 1

F2 finish with type/target 3:
    state+0x43 = 1
    state+0x4f = 1
```

The current branch analysis is consistent with that: finalize restores/sets display validity gates, but those gates are not a flash verification result.

## Why Host Trace Can Look Perfect

A perfect-looking host trace can still end with corrupt panel output:

```text
F2_INIT=True
F1_METADATA=True
426/426 chunk writes=True
F2_FINALIZE=True
SendImage=True
```

That sequence proves:

1. Host sent all packets.
2. AP accepted the packet stream.
3. AP parser accepted the finalize command.

It does not prove:

1. every page program completed in SPI flash;
2. the lower 64 KB+ region contains new data;
3. display read path reads fresh bytes after `0x01310000`;
4. AP performed a readback/CRC/verify before accepting finalize.

## Current Strongest Diagnosis

The diagnosis is now more precise:

```text
F2 finalize is not a storage verifier.
Page-program status failure can be swallowed before finalize.
Therefore AP/GCC can report a clean upload even if the static media slot is partially stale.
```

This fits the user-observed symptom:

```text
upper portion changes
lower portion remains black/stale
```

especially because the visible failure boundary is near:

```text
0x01310000 = first 64 KB boundary inside static slot
```
