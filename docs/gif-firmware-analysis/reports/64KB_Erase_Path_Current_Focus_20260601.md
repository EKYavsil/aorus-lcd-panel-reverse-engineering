# 64 KB Erase Path Current Focus

Date: 2026-06-01

Status: active research direction.

The GIF frame-index bug is confirmed and archived separately, but the investigation is now focused on the deeper storage problem: why the AP 64 KB erase/program path corrupts or fails to fully update custom media.

## Primary Question

Why is the 64 KB upload path broken?

Secondary question:

Can a large GIF be safely sent through the 4 KB path instead of the 64 KB path?

## Proven Static-Image Facts

Static image corruption was fixed with one host-side metadata change:

```text
nType == 1
destination = 0x01300000
F1[0x11]: 0x02 -> 0x01
```

No payload bytes were changed.
No destination was changed.
No image type was changed.
No display/apply/mode sequence was required for the final working static fix.

Observed effect:

```text
Original static path: upper part changed, lower part stayed black/stale.
Patched static path: full image wrote correctly.
```

This strongly points away from converter/pData/display-state as the static root cause, and toward the AP flash erase/program path selected by `F1[0x11]`.

## AP Meaning Of F1[0x11]

AP F1 parser evidence from the F1.4 image:

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

Current interpretation:

| F1[0x11] | AP erase selection |
|---|---|
| 0x02 | 64 KB block erase |
| 0x01 | 4 KB sector erase |
| other/non-2/non-3 | 4 KB sector erase fallback |
| 0x03 | special 1-byte/other path, not suitable as a general fix |

The same byte also affects a second AP state/timing field:

```c
if (bVar30 == 0) {
  *puVar23 = 1;
  *pcVar11 = 0;
}
else if (bVar30 == 2) {
  *DAT_00008248 = (uint)uVar26 * 3000 + 3;
}
```

This matters for GIF because forcing a large GIF from `0x02` to `0x01` does not only change erase granularity. It also changes AP timing/state behavior.

## Static Boundary Evidence

Static image:

```text
destination = 0x01300000
visible media data begins around 0x0130000C
first 64 KB boundary = 0x01310000
bytes before boundary = 0x01310000 - 0x0130000C = 65524
```

That corresponds closely to the observed "upper part changed, lower part black/stale" behavior.

This makes the first boundary crossing at or near `0x01310000` the most important failure point.

## AP Flash Helper Asymmetry

Known AP SPI helper behavior:

| Helper | Operation | Opcode | Poll behavior | Return reliability |
|---|---|---|---|---|
| `FUN_0000bab4` | Write enable | `0x06` | no WEL verification | weak |
| `FUN_0000ba44` | Read status / wait WIP clear | `0x05` | timeout 300 | returns success/failure |
| `FUN_0000b4d0` | 64 KB block erase | `0xD8` | calls poll | ignores poll result, returns success |
| `FUN_0000b910` | 4 KB sector erase | `0x20` | calls poll | propagates poll result |
| `FUN_0000b6cc` | Page program | `0x02` | calls poll | ignores poll result, returns success |
| `FUN_0000b54c` | Read | `0x03` | read path | no write status |
| `FUN_0000b760` | fast/DMA-like read | `0x3B` | timeout-capable | can fail |

Critical point:

```text
4 KB sector erase has real failure propagation.
64 KB block erase and page program can fail internally while the higher-level upload state machine still advances.
```

This explains why host/GCC can see a successful SendImage result even when the panel content is partially stale or black.

## Current Root-Cause Model

Most likely:

```text
The 64 KB block erase at the second block, or page programming after the first 64 KB boundary, fails/times out/is rejected.
The AP ignores the relevant failure result and advances the upload state anyway.
The host receives success.
Only the first region is correctly refreshed.
The lower region remains stale, black, or partially corrupted.
```

Possible underlying reasons still under investigation:

| Candidate | Why it fits | Status |
|---|---|---|
| 64 KB block erase timeout too short or ignored | block erase can take longer than sector erase; AP timeout is fixed/opaque | strong candidate |
| write-enable latch/protection not verified | AP does not confirm WEL or block-protect status | strong candidate |
| flash block-protection or status register state | no status-register management found in media write path | plausible |
| page program failure after boundary | program helper also ignores poll result | plausible |
| 4-byte address mode fragility | addresses are above 16 MB, AP uses global `0xB7` + normal opcodes | plausible but less direct |
| wrong host pData/converter | static pData rendered correct offline | mostly eliminated for static |
| display/apply/mode sequence | clean apply sequence did not fix lower black region | mostly eliminated for static |

## Can A Big GIF Use The 4 KB Path?

Maybe, but not by a naive one-byte patch.

Known constraint:

```text
F1[0x11] == 0x02 selects 64 KB erase and also sets an AP timing/state field.
F1[0x11] == 0x01 selects 4 KB erase but does not take the same timing/state branch.
```

This coupling likely explains why previous GIF attempts that forced the 4 KB path caused instability or panel lockups. The change was not equivalent to the successful static patch because GIF uses a different target/storage path and larger payload/state lifetime.

A safer future design would need one of these:

1. Preserve the GIF-specific AP timing/finalization behavior while using 4 KB erase.
2. Keep payload small enough that GCC naturally selects `F1[0x11] == 0x01`.
3. Find an AP-supported multi-part/append flow that avoids a single large 64 KB erase path.
4. Patch AP firmware itself, which is much higher risk and not a current live-test target.

Current evidence says option 1 is not directly exposed by the known host protocol because the same F1 byte appears to control both erase granularity and timing/state behavior.

## Immediate Offline Research Tasks

1. Reconstruct the AP upload state machine around `F1[0x11]`, `DAT_00008248`, erase count, chunk count, and finalize behavior.
2. Trace every caller of:
   - `FUN_0000b4d0` 64 KB erase
   - `FUN_0000b910` 4 KB erase
   - `FUN_0000b6cc` page program
   - `FUN_0000ba44` status poll
3. Determine whether 64 KB erase failure can be inferred from stale readback or AP state fields.
4. Search for any alternate host command or AP command that performs sector erase while preserving GIF timing/state.
5. Compare F1.2/F1.3/F1.4 AP implementations to see if any version handles 64 KB erase status more safely.
6. Map whether large GIF target/storage path uses a different base address, state flag, or finalize loop than static image.

## Current Decision

The active path is not frame-index patching.

The active path is:

```text
Understand and document why the AP 64 KB erase/program path is unreliable, then decide whether large GIF can be routed through a safe 4 KB-equivalent flow without breaking AP GIF state.
```



