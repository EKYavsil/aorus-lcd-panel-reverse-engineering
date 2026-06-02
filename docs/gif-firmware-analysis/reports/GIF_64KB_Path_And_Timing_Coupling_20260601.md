# GIF 64 KB Path and Timing Coupling

Date: 2026-06-01

Scope: offline analysis only. No live tests or file deployment.

## Why This Matters

Static image was fixed by changing one F1 header byte:

```text
F1[0x11] 0x02 -> 0x01
```

For static, this cleanly meant:

```text
64 KB block erase -> 4 KB sector erase
```

For GIF, that same byte is coupled to another AP state path:

```text
64 KB block erase selection + upload finalization timing threshold
```

This report captures why the GIF fix cannot simply copy the static byte change.

## Host Protocol Evidence

Public protocol reconstruction in the local offline clone:

```text
<local-gif-investigation-dir>\public_repos\gigabyte-gpu-lcd\src\gigabyte_lcd\protocol.py
```

reconstructs GCC's upload mode selection:

```python
def upload_chunk_mode(byte_count):
    if byte_count < 20480:
        return 1, 4096, 0.4
    return 2, 65536, 2.0
```

and GIF target selection:

```python
if kind == ImageKind.GIF:
    return (0, 2)
```

This matches our AP parser findings:

| Payload | F1 destination | storage | F1[0x11] |
|---|---:|---:|---:|
| small GIF `< 20480` | `0x00000000` | `2` | `0x01` |
| large GIF `>= 20480` | `0x00000000` | `2` | `0x02` |
| static full image | `0x01300000` | `1` | `0x02` in original GCC |

## AP Parser Evidence

The AP parser does this:

```c
bVar30 = F1[0x11];

if (bVar30 == 3) {
    erase_unit = 1;
}
else if (bVar30 == 2) {
    erase_unit = 0x10000;
}
else {
    erase_unit = 0x1000;
}
```

and separately:

```c
if (bVar30 == 2) {
    DAT_0000D168 = erase_count * 3000 + 3;
}
```

So `0x02` means two things:

1. Use AP 64 KB block erase.
2. Set the delayed upload finalization threshold.

`0x01` means:

1. Use AP 4 KB sector erase.
2. Do not set that same delayed finalization threshold.

## Current Test Interpretation

Known observations:

| Observation | Interpretation |
|---|---|
| Static full image fixed with `0x02 -> 0x01` | Static needed reliable sector erase; GIF timing side effect was irrelevant. |
| Small diagnostic GIF with natural 4 KB path displayed a white/red/green loop | The GIF renderer and RLE format can work when payload/state is small and coherent. |
| Direct large-GIF sector-forcing was unstable/failed | Large GIF likely lost the timing/finalization side effect tied to `0x02`, or created a state combination the AP does not expect. |
| Original DLL large GIF shows loading then bad/black output rather than always locking | The normal GIF path reaches AP upload/playback state, but storage integrity or frame metadata can still be corrupt. |

This is consistent with a two-part GIF problem:

```text
large GIF needs the 0x02 timing/finalization behavior
but 0x02 also uses the broken 64 KB erase path
```

## Why A Simple Host Delay Is Not Enough

For static, increasing host wait before payload did not fix the lower black region.

Reason:

```text
The 64 KB erases happen inside AP firmware after F1.
If AP already skipped/timed out/mis-completed one erase and advanced its internal offset,
waiting later on the host does not make AP retry the missed erase.
```

For GIF, a host delay has the same limitation. The broken operation is internal to the AP erase worker, not just host pacing.

## Can We Preserve GIF Timing While Forcing 4 KB Erase?

Current answer:

```text
Not yet proven through a known safe host command.
```

What we looked for:

- parser commands that set `DAT_0000D168` independently;
- state/template commands `EA/EB/EC/ED/F3/F4`;
- readback/write commands with GIF timing fields;
- media readback commands that could verify flash contents.

Current result:

```text
Only the F1 parser branch for F1[0x11] == 0x02 has been identified writing the timing threshold.
```

No command has been confirmed that says:

```text
use 4 KB sector erase, but also set DAT_0000D168 like 0x02
```

## Candidate Directions

### Candidate A: Host-only hybrid F1 sequence

Idea:

```text
somehow prime the timing threshold with 0x02, then perform actual upload with 0x01
```

Current risk:

- A real `0x02` F1 queues the dangerous 64 KB erase path.
- A dummy `0x02` F1 may still erase or disturb the GIF slot.
- The parser does not show a clean "set timing only" path.

Status:

```text
Interesting, but not safe enough for live testing without more proof.
```

### Candidate B: Host DLL sends large GIF as smaller natural 4 KB units

Idea:

```text
keep each GIF payload/upload below 20480 bytes so GCC/AP naturally uses 0x01
```

Problem:

- A real custom GIF is one structured container with shared frame table and stream offsets.
- Splitting it into multiple uploads would not produce one coherent GIF unless AP has a multi-part append protocol.
- No append protocol has been found.

Status:

```text
Not viable with known protocol.
```

### Candidate C: AP firmware repair

Idea:

```text
preserve GIF 0x02 semantics, but repair the AP 64 KB erase helper.
```

Possible AP-side changes:

- make `FUN_0000B4D0` propagate `FUN_0000BA44()` failure;
- increase the 64 KB erase wait/poll window;
- internally replace 64 KB media erases with repeated 4 KB sector erases while leaving `F1[0x11] == 0x02` timing intact.

Status:

```text
Technically coherent but high risk, because it implies AP firmware patching/flashing.
```

### Candidate D: Find hidden media readback/verify command

Idea:

```text
verify 0x01000000 / 0x01310000 contents after upload, then design retries.
```

Current blocker:

```text
No host-exposed arbitrary media flash readback command has been found in the parser.
```

Status:

```text
Continue offline search, but current parser evidence is negative.
```

## Current Best Conclusion

The 64 KB path is probably the shared root for both bugs:

```text
static: corrupt/stale second 64 KB block appears as lower black region
GIF: corrupt/stale 64 KB blocks corrupt frame table or RLE streams
```

The static fix worked because static does not appear to need the GIF timing side effect of `F1[0x11] == 0x02`.

The GIF fix needs a more precise approach:

```text
avoid broken 64 KB erase
without losing large-GIF finalization timing/state
```

That combination is not yet exposed by a known normal host command.

## Next Offline Step

Before any live test:

1. Produce a full xref map for `DAT_0000D168 / 0x20000308`.
2. Confirm whether it is only written in the F1 `0x02` branch.
3. Inspect AP finalization gate for whether a missing/zero threshold can be safely compensated by host-side sequencing.
4. If no host route exists, keep GIF repair planning in two separate tracks:
   - safer host-only workaround for constrained GIFs;
   - AP firmware patch feasibility for the real 64 KB bug.


