# AP Timing Field Xref Result

Date: 2026-06-01

Scope: offline Ghidra analysis only. No live command or deployment.

## Purpose

The GIF path has a critical coupling:

```text
F1[0x11] == 0x02
```

selects both:

1. 64 KB block erase, which is the suspected broken path;
2. a delayed upload finalization threshold at `0x20000308`.

This report checks whether that timing/finalization field can be set independently through another AP command.

## Generated Evidence

Script:

```text
<local-gif-investigation-dir>\scratch\APFirmwareTimingFieldXrefDump.java
```

Output:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_timing_field_xrefs_AP_F14_pkg_timing.txt
```

Tracked fields:

| Field | Address | Meaning |
|---|---:|---|
| `DAT_0000D168` / `DAT_00008248` | `0x20000308` | upload finalization timing threshold |
| `DAT_0000D160` | `0x20000307` | upload phase |
| `DAT_0000D174` | `0x20000310` | upload offset |
| `DAT_0000D178` | `0x20000314` | erase selector |
| `DAT_0000D16C` | `0x20000316` | erase operation count |
| `DAT_0000D184` | `0x20000318` | remaining page count |
| `DAT_0000D164` | `0x20000321` | upload pending/finalize gate |

## Write Site

The F1 parser writes the timing threshold:

```c
if (bVar30 == 2) {
    *DAT_00008248 = (uint)uVar26 * 3000 + 3;
}
```

where:

```text
bVar30 = F1[0x11]
uVar26 = AP-computed erase operation count
DAT_00008248 points to 0x20000308
```

No other write to this field was found in the targeted xref dump.

## Read Site

The AP main loop reads the same field in the delayed finalization gate:

```c
if ((!upload_pending) || (*(uint *)(state + 0x98) < *DAT_0000D168)) {
    goto main_loop;
}
```

Once the gate passes, AP commits pending GIF/static media state:

```c
upload_pending = 0;
state+0x66 = state+0x6A;
state+0x18 = state+0x68;
state+0x2E = 2;
state+0x32 = 0;
state+0x3D = 1;
state+0x3F = 1;

if (state+0x19 == 1) state+0x1B = 2;
if (state+0x19 == 2) state+0x1A = 2;
```

## Interpretation

For large GIF:

```text
F1[0x11] == 0x02
```

is doing two necessary-looking things:

```text
select 64 KB erase
set delayed finalization threshold
```

For forced sector GIF:

```text
F1[0x11] == 0x01
```

does this:

```text
select 4 KB erase
does not refresh the delayed finalization threshold
```

That explains why the static fix cannot be copied directly to GIF. Static image does not depend on the animation finalization state in the same way.

## Result

Current answer:

```text
No confirmed independent host command can set 0x20000308 / DAT_0000D168.
```

Parser commands `EA/EB/EC/ED/F3/F4/D6/D7/D8/DE/DF` expose state/template/mode/loop fields, but none has been proven to write this timing threshold.

## Impact On Repair Strategy

A safe host-only GIF fix has to solve both sides:

```text
avoid broken 64 KB erase
preserve or recreate delayed finalization timing
```

Known host protocol does not yet expose that as one clean operation.

Therefore the next offline question is:

```text
Can AP finalization still become correct with F1[0x11] == 0x01 if the old 0x20000308 value is already nonzero,
or does it require a fresh value computed for the current upload?
```

That must be answered from AP state-machine analysis before another live GIF experiment.


