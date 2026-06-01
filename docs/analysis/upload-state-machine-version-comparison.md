# AP Upload State Machine Version Comparison - 2026-05-31

Scope: offline analysis only. No live panel command, no updater execution, no service control, and no GCC file modification.

## Goal

Compare available AP firmware analysis artifacts to see whether F1.2/F1.3/F1.4 differ in a way that could explain why firmware reinstall does not wipe or repair the stuck lower part of the custom static image.

## Artifacts Checked

| Artifact | SHA256 |
|---|---|
| `ap_function_dump_modern_F12_AP.txt` | `EAD41D1E116DAE55AD76C5DC22FAC410AC645324256C5D7124A43E9CF316361E` |
| `ap_function_dump_AP_F14_pkg.txt` | `EAD41D1E116DAE55AD76C5DC22FAC410AC645324256C5D7124A43E9CF316361E` |
| `ap_target_function_dump_AP_F12_pkg.txt` | `3B20E400D773390F23C8E984EC569CC6970B789E79B9848822DA8B075B49F41C` |
| `ap_target_function_dump_AP_F13_pkg.txt` | `E7B6B251A38852C8A1639A4759B1A3743DE90019B2111FF356F0C55ADFCF02E5` |
| `ap_target_function_dump_AP_F14_pkg.txt` | `49C63379C1891AEFFEE072AFCCBC0D7C0AFCDAD5B558F07D2AFBF5A728686781` |

Important: the full modern F1.2 AP function dump and the F1.4 AP package function dump are identical. That means the parser/upload code visible in those two dumps is not a likely differentiator.

The package target dumps for F1.2/F1.3/F1.4 differ, but some older-target dumps contain missing functions. Those are useful for hints, not for high-confidence whole-firmware equivalence.

## Upload Parser Is Same In Modern F1.2/F1.4

The full modern F1.2 and F1.4 dumps both show:

- `FUN_00007db8` as the AP command parser.
- `F1` decodes destination and upload metadata.
- destination `0x01300000` maps to target/static slot `3`.
- destination `0x01320000` maps to another custom slot/state `2`.
- static mode/type `1` uses `0x1000` erase granularity.
- mode/type `2` uses `0x10000` erase granularity.

Relevant parser behavior:

```c
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

For the observed static upload:

```text
destination = 0x01300000
type/mode   = 1
chunks      = 426
erase unit  = 0x1000
erase count = ((426 << 8) / 0x1000) + 1 = 27
```

So the AP intends to erase enough space for the static frame.

## Upload Erase/Program Path

The upload state machine calls:

| Operation | Function | SPI opcode |
|---|---|---|
| 4 KB sector erase | `FUN_0000b910(base + offset)` | `0x20` |
| 64 KB block erase | `FUN_0000b4d0(base + offset)` | `0xD8` |
| 256-byte page program | `FUN_0000b6cc(staging, base + offset, 0x100)` | `0x02` |

The AP does poll SPI status completion through `FUN_0000ba44`, but I did not find a read-after-write verify step for media slot data.

Practical meaning:

- AP can report/control completion at the SPI command/status level.
- It does not appear to verify that programmed bytes equal the host payload.
- Host `SendImage=True` therefore remains transport/protocol success, not storage-integrity proof.

## 0x01310000 Boundary

No explicit `0x01310000` cutoff or branch was found in the parser/upload code.

The known boundaries are slot-level:

- `0x01300000`
- `0x01320000`

The suspicious `0x01310000` boundary is a 64 KB boundary inside the static slot, not a named AP slot boundary in the visible code.

That makes the lower-black symptom look more like:

- external SPI flash erase/program/read issue at or after the 64 KB boundary
- AP scheduling/state issue during upload
- display read/cache issue after crossing the boundary

rather than an intentional AP address limit.

## Why Firmware Reinstall Did Not Clear The Stuck Image

The firmware reinstall path likely updates AP firmware/code/config. The custom static media slot is separate external storage:

```text
static slot base      = 0x01300000
visible frame start   = 0x0130000C
visible frame end     = 0x0131A90C
```

Current offline evidence does not show a firmware-update path that performs a full custom media slot wipe over `0x01300000..0x0131A90C`.

Therefore, it is plausible that:

1. firmware reinstall repairs/restarts the AP code;
2. config/default state returns;
3. but custom media flash remains intact or partially corrupt;
4. once the AP/Gigabyte service selects static/custom display again, the same broken stored image appears.

This matches the observed behavior.

## What Changed Since The Earlier Erase-Delay Theory

Earlier, the strongest model was:

```text
post-F1 erase wait too short -> payload interrupts remaining erase
```

That remains mechanically possible, but later tests weakened it:

- increasing only the pre-payload wait did not fix lower black;
- a clean 5-second baseline had `426/426` host chunk success and still lower black;
- therefore the problem can exist even when the host-visible path is clean.

The current stronger model is:

```text
AP/flash/display path after the 64 KB boundary is unreliable or not verified.
Host upload success does not prove storage integrity.
Firmware reinstall does not wipe the custom media slot.
```

## Current State Of Evidence

| Question | Current answer |
|---|---|
| Does modern F1.2 differ from F1.4 in visible parser/upload code? | No, the full dumps are identical. |
| Does AP intentionally stop static image at `0x01310000`? | No evidence. |
| Does AP intend to erase full static frame range? | Yes, calculated erase count covers the frame. |
| Does AP verify written media bytes by reading them back? | No evidence found. |
| Does host have a safe readback command for custom media flash? | No verified path found. |
| Does firmware reinstall prove custom media slot wipe? | No. Evidence suggests it does not wipe the stuck slot. |
