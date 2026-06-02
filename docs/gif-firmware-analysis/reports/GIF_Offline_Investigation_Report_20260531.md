# GIF Offline Investigation Report - 2026-05-31

Scope: offline analysis only. No live DLL deploy, service control, firmware updater execution, panel command, raw I2C call, GIF upload, or repository write was performed.

Working folder:

```text
<local-gif-investigation-dir>
```

Main repository was used read-only.

## Current State

The previous static custom-image fault is solved by a static-only metadata change:

```text
F1[0x11]: 0x02 -> 0x01
```

That fix is proven for:

```text
nType       = 1
destination = 0x01300000
storage     = 1
mode        = static image
```

The failed GIF experiment showed that applying the same byte change to GIF is not equivalent and can destabilize the panel. The corrected GIF test DLL was positioned to apply the change when the GIF header matched:

```text
nType       = 0
target      = 0x00000000
storage     = 2
F1[0x11]    = 0x02
```

The panel entered a worse state and required firmware recovery. Therefore the static fix must not be widened to GIF without deeper proof.

## Inputs Reviewed

Read-only sources:

- `docs/analysis/gif-erase-mode-hypothesis.md`
- `docs/analysis/f1-header-to-erase-mode.md`
- `docs/analysis/upload-state-machine-version-comparison.md`
- `docs/analysis/ap-flash-failure-propagation.md`
- `docs/evidence/static-sector-erase-success.md`
- `local_artifacts_not_for_github/gif_sector_erase_test/GIF_Sector_Erase_Test_Failure_Analysis_20260531.md`
- legacy GIF experiment leftovers under `<local-backup-artifacts>\gif_research_leftovers_20260531_142821`
- old binary captures under `<local-backup-artifacts>\desktop_loose_related_20260531_060316`

New exports created in this isolated folder:

- `exports\legacy_gif_log_hits_compact.txt`
- `exports\legacy_gif_code_hits_compact.txt`
- `exports\imagemaker_gif_il_hits.txt`
- `exports\gif_bin_header_parse.jsonl`
- `exports\gif_bin_rle_validate.jsonl`

## ucVga GIF SendImage Path

`IMG_DATA` contains:

```text
pData
uSize
nType
nCount
nDelay
bTemplate
```

`GvLcdApi.SendImage` builds a 19-byte `F1` metadata packet:

```text
byte 0..4   = F1 CB 55 AC 38
byte 5..8   = target/destination
byte 9      = storage/type
byte 10..13 = chunk/page count = uSize / 256 + 1
byte 14..15 = frame count
byte 16     = min(255, nDelay)
byte 17     = erase/chunk mode selector
byte 18     = bTemplate
```

The payload loop sends fixed 256-byte chunks over the normal `0xC2` path. Host-side retry behavior is weak as a storage-integrity signal:

- a chunk send failure can retry;
- after several retries, `GvFreeDispLib()` / `GvInitDispLib()` can run;
- success means transport accepted the bytes, not that flash content was verified.

This is consistent with the static finding: `SendImage=True` is not proof that panel flash contains the intended bytes.

## GIF Payload And RLE Findings

Important correction: earlier manual scripts sometimes assumed an end-of-row RLE marker. The recovered/public protocol uses a simpler stream format:

```text
u16 count
if high bit clear:
    next count RGB565 pixels are literal
if high bit set:
    next one RGB565 pixel is repeated count & 0x7fff times
```

A valid frame expands to exactly:

```text
320 * 170 = 54400 pixels
```

The offline validator found that several real GCC/native GIF payloads are structurally valid:

| File | Size | Frames | RLE result | Notes |
|---|---:|---:|---|---|
| `animation.bin` | `9207552` | `86` | valid | large captured animation |
| `gcc_captured.bin` | `45858` | `4` | valid | GCC captured payload |
| `captured_pData_full.bin` | `10966` | `8` | valid | captured pData |
| `bypass_v18b.bin` | `16442` | `12` | valid | manual but structurally valid |
| `bypass_rainbow12.bin` | `16442` | `12` | valid | manual but structurally valid |
| `bypass_rle_48frame_65536.bin` | `65536` | `48` | valid | synthetic 64 KB-sized payload |

Several older manual experiments are invalid under the corrected parser:

- `bypass_fixedruns.bin`
- `bypass_panel_giphy1.bin`
- `bypass_v6.bin`
- `bypass_2run.bin`

Conclusion: the GIF/container path is not globally broken. Some manual test payloads were invalid, but GCC/native payload examples can be structurally correct. The remaining GIF failure is more likely AP animation storage/state behavior than a universal upstream converter defect.

## AP Firmware Behavior Relevant To GIF

AP F1 parser treats GIF differently from static image:

```text
target 0x00000000 -> internal slot 1
target 0x01320000 -> internal slot 2
target 0x01300000 -> internal slot 3
```

Storage byte behavior:

```text
byte 9 == 2 -> animation/GIF storage path
byte 9 != 2 -> normal/static storage path
```

Erase selector behavior:

```text
F1[0x11] == 0x02 -> 64 KB erase unit
F1[0x11] != 0x02 and != 0x03 -> 4 KB erase unit
F1[0x11] == 0x03 -> special one-operation path
```

Critical extra detail:

```text
if F1[0x11] == 0x02:
    AP sets a timing/threshold value derived from erase_count * 3000 + 3
```

The visible parser notes indicate this timing variable is not set in the same way for `F1[0x11] == 0x01`.

That explains why the static byte change can be safe for static but unsafe for GIF:

- static: explicit slot `0x01300000`, storage type `1`, single frame, known broken 64 KB boundary;
- GIF: target `0`, internal slot `1`, storage type `2`, multi-frame RLE, animation metadata, likely different timing/state assumptions.

## Why The GIF Sector Test Was A Bad Fit

Static and GIF share the same header byte, but they do not share the same AP state machine.

Static path:

```text
target      = 0x01300000
slot        = 3
storage     = 1
payload     = single RGB565 frame
known issue = second 64 KB block around 0x01310000
```

GIF path:

```text
target      = 0x00000000
slot        = 1
storage     = 2
payload     = multi-frame RLE container
metadata    = frame count + delay are active
```

The failed test likely changed not only erase granularity but also AP-side timing/state expectations for animation storage. The observed panel lock/corruption is consistent with an animation upload state machine desynchronizing, not with a simple unchanged GIF display result.

## Payload Size Risk

GIF payloads can be much larger than static:

```text
static fixed image      ~= 108812 bytes, 426 pages
captured 21-frame GIF   ~= 1624244 bytes
captured 86-frame GIF   ~= 9203346 bytes
```

At 256-byte pages:

```text
1624244 bytes -> about 6345 chunks
9203346 bytes -> about 35951 chunks
```

That makes GIF much more sensitive to:

- AP erase/program scheduling;
- status polling that does not propagate failures;
- storage window limits;
- animation slot capacity;
- watchdog/service/display interference during long uploads.

## Current Best Hypotheses

Ranked from strongest to weakest:

1. GIF uses a separate AP animation storage path, and the host-visible protocol success does not prove the animation slot was erased/programmed correctly.
2. The large GIF path needs the original `F1[0x11]=0x02` timing/state behavior even if static needed `0x01`; changing it to `0x01` can corrupt AP animation state.
3. GIF target `0x00000000` is probably intentional and maps to AP slot `1`; it is not automatically the same kind of wrong destination as a bad static address.
4. Some old manual GIF payloads were invalid, but GCC/native captured payloads validate, so converter/container corruption is not the primary global explanation.
5. Display/apply sequence may still matter after upload, but the panel corruption during the GIF mutation test points more strongly at upload/storage state.

## What Not To Do Next

Do not:

- reuse the static `F1[0x11] 0x02 -> 0x01` patch on arbitrary GIF uploads;
- change GIF target from `0`;
- change GIF `nType`;
- change storage type `2`;
- inject raw I2C erase/reset commands;
- run another live GIF mutation without first collecting passive runtime evidence.

## Recommended Next Offline Work

1. Map AP animation slot `1`.

   Find where target `0` / slot `1` resolves to a real flash base, likely around a remap such as `0x01000000`. Confirm its size/window and whether it has limits comparable to static slot checks for `0x01300000` and `0x01320000`.

2. Map AP display reader for animation mode.

   Find how mode `5` reads animation frames: header offsets, per-frame RLE reads, frame count handling, delay handling, and wrap/loop behavior.

3. Map animation upload finalization.

   Determine whether `F2 02` for storage type `2` does extra work beyond static finalization, such as committing frame count/delay tables or swapping active animation banks.

4. Check capacity and header limits.

   Look for constants around:

   ```text
   0x01000000
   0x01300000
   0x01320000
   0x01400000
   0x10000
   0x1000
   max frame count
   max payload pages
   ```

5. Design a passive GIF trace only after the offline map improves.

   The trace should log raw F1, page count, frame count, delay, target, storage byte, `F1[0x11]`, first failed chunk index, F2 result, and post-upload mode/apply commands. It should not mutate any byte.

## Decision

Current category:

```text
GIF bug is not proven to be the same one-byte erase-mode bug as static.
GIF is now best treated as an AP animation-slot storage/state problem.
```

The next meaningful work is AP firmware offline mapping of target `0` / storage type `2` / mode `5`, not another live patch.



