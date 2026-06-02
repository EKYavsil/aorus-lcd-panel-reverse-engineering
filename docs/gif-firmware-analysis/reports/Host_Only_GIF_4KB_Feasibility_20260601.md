# Host-Only GIF 4 KB Path Feasibility

Date: 2026-06-01

Scope: offline analysis only. No live DLL deploy, panel command, firmware updater execution, service action, or AP firmware patch was performed.

## Current Goal

Find a firmware-free route for custom GIF upload before returning to AP firmware repair.

The desired host-side fix would be:

```text
large GIF upload uses reliable 4 KB erase behavior
without losing the AP GIF finalization/timing behavior normally tied to F1[0x11] == 0x02
```

## Evidence Summary

Static image was fixed by changing only:

```text
F1[0x11] 0x02 -> 0x01
```

For static image, this avoided the AP 64 KB block erase helper and selected the reliable 4 KB sector erase helper.

For GIF, the same byte is not only an erase selector. AP parser evidence shows:

```c
bVar30 = F1[0x11];

if (bVar30 == 2) {
    erase_unit = 0x10000;
}
else {
    erase_unit = 0x1000;
}

if (bVar30 == 2) {
    DAT_0000D168 = erase_count * 3000 + 3;
}
```

So for large GIF:

```text
F1[0x11] == 0x02
  -> selects 64 KB erase
  -> also sets delayed upload finalization threshold at 0x20000308
```

Directly forcing GIF to `0x01` removes both effects. This explains why the static one-byte fix did not safely transfer to GIF.

## Host Upload Rule

Public protocol reconstruction and GCC IL agree on the same rule:

```text
payload_size < 20480  -> F1[0x11] = 0x01, prep unit 4 KB
payload_size >= 20480 -> F1[0x11] = 0x02, prep unit 64 KB
```

GIF target is special:

```text
kind = GIF
F1 destination = 0x00000000
storage/type = 0x02
```

Static target is not the same:

```text
kind = IMAGE
F1 destination = 0x01300000 on the 50-series LCD path
storage/type = 0x01
```

This means "large GIF over 4 KB path" is not just a host chunking change. It changes AP upload state and GIF commit timing.

## Why More Host Delay Did Not Solve 64 KB

Old static tests already tried waiting longer after F1 before payload chunks:

| Test | Result |
|---|---|
| Existing wait expanded to about 10 seconds | still lower black |
| Extra 30-second post-F1 wait | still lower black |

This weakens the simple theory:

```text
GCC sends payload too early after F1
```

The stronger theory remains:

```text
AP's internal 64 KB erase path itself can fail/timeout/skip, and waiting on the host after F1 cannot make AP retry a missed internal erase.
```

## Host-Only Options

### Option A: Natural Small GIF 4 KB Path

Make the GIF payload genuinely smaller than 20480 bytes so GCC/AP naturally use:

```text
F1[0x11] = 0x01
```

Known result:

- tiny GIF uploads did not require 64 KB path;
- later frame-index diagnostics showed visible GIF playback is possible;
- the panel can show a white/red/green loop in a controlled tiny test.
- user later compressed an internet GIF source file to 19,244 bytes and uploaded it through GCC; upload completed, but the panel stayed on loading, and after switching modes away/back to GIF the display was white.
- follow-up check in `C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\Assets` proved the converted upload payload was not small:
  - source GIF: `giphy1-ezgif.com-optimize (1).gif`, 19,244 bytes
  - GCC `animation.gif`: 496,213 bytes
  - GCC `animation.bin`: 1,748,766 bytes
  - frame count: 21
  - computed upload page count: 6,832
  - natural chunk mode: `2 / 64 KB`

Limitations:

- This is not a general large-GIF fix.
- Real GIFs must be heavily constrained: frame count, color complexity, dimensions, and RLE compressibility.
- The separate confirmed GIF frame-index/startup bug still needs handling, likely by ensuring frame 1 is visible or using a dummy/priming frame strategy.
- The 19 KB source-GIF result confirms that source-file size is not a sufficient predictor. The exact upload still used the large `0x02` / 64 KB path because GCC's converted `animation.bin` was 1.75 MB.

Verdict:

```text
Still untested for real converted payloads below 20480 bytes.
The 19 KB source GIF was not a real natural-4KB upload after GCC conversion.
```

### Option B: Force Large GIF F1[0x11] = 0x01

This is the direct static-style patch copied to GIF.

Known result:

- live test failed/was unsafe;
- panel could enter a bad state requiring firmware recovery;
- AP timing/finalization field was not refreshed through the normal 0x02 branch.

Verdict:

```text
Rejected as-is.
The patch is too blunt because it removes the timing/finalization side effect required by large GIF.
```

### Option C: Dual-F1 Timing Prime

Concept:

```text
send a controlled F1[0x11] = 0x02 only to establish timing,
then send actual GIF with F1[0x11] = 0x01 for 4 KB erase
```

Problems:

- A real `0x02` F1 queues the dangerous 64 KB erase path.
- A second F1 likely resets upload state fields, including erase count, phase, storage type, and pending flags.
- No parser evidence proves a "timing only" F1 mode.
- If the second F1 clears or overwrites the state needed by the first F1, the trick fails.

Verdict:

```text
Interesting but not safe for live testing without AP state emulation or stronger parser proof.
```

### Option D: Manipulate F1 Size/Page Count

Concept:

```text
lie in F1 metadata so AP computes a desired timing/erase count,
while host sends actual large payload differently
```

Problems:

- AP uses the F1 page/count fields for erase count and upload remaining pages.
- Wrong size/count risks early finalization, stale data, or ignored trailing pages.
- GIF container offsets require one coherent contiguous payload.

Verdict:

```text
Not viable with current evidence.
Likely corrupts upload accounting.
```

### Option E: Hidden Host Command To Set Timing Field

Desired command:

```text
set 0x20000308 / DAT_0000D168 without selecting 64 KB erase
```

Search result:

- targeted xrefs found the F1 `0x02` parser branch as the only confirmed write to the timing field;
- parser commands `EA/EB/EC/ED/F3/F4/D6/D7/D8/DE/DF` expose mode/template/state fields, but no confirmed timing setter.

Verdict:

```text
No confirmed command yet.
Continue offline search only if new parser branches or hidden APIs are found.
```

### Option F: Host-Side GIF Compression/Normalization Tool

Instead of making arbitrary large GIFs work, generate panel-safe GIF payloads that stay under 20480 bytes.

Possible constraints:

- cap source frames aggressively;
- reduce visual complexity;
- use areas of solid color to improve vendor RLE compression;
- possibly use 150 px effective content height as public tooling recommends;
- keep frame 1 visibly valid because frame-0/playback startup behavior is suspect.

Verdict:

```text
Most practical firmware-free path for a usable GIF workaround.
This does not repair the 64 KB bug, but avoids it.
```

## Current Best Host-Only Conclusion

General large GIF over the reliable 4 KB erase path is not currently proven possible from host DLL changes alone.

The blocker is:

```text
F1[0x11] == 0x02 is both:
  1. the broken 64 KB erase selector
  2. the large-GIF finalization/timing selector
```

Static image did not need the second effect. GIF likely does.

Therefore:

```text
Firmware-free general large-GIF fix: not yet supported by evidence.
Firmware-free constrained GIF workaround: still uncertain; the apparent 19 KB test actually used 64 KB mode after conversion.
```

The latest evidence changes the practical priority:

```text
The target is no longer "make small GIFs work".
The target is "make the panel accept and play arbitrary GCC-converted GIFs reliably".
```

That likely requires either:

1. proving the converted payload can stay below the natural 20480-byte threshold and still play correctly; or
2. fixing/preserving the large-GIF state semantics while avoiding the defective 64 KB erase implementation.

## Recommended Next Offline Work

1. Build an AP F1-state emulator from the recovered parser logic.
   - Input: target, storage, page count, frame count, delay, F1[0x11], template flag.
   - Output: AP upload fields, erase count, timing threshold, phase flags.

2. Feed the emulator with:
   - original large GIF metadata;
   - forced `0x01` large GIF metadata;
   - tiny natural `0x01` GIF metadata;
   - hypothetical dual-F1 sequences.

3. Search host DLL for all places that create or consume `animation.bin`, `animation.ini`, `nCount`, `nDelay`, and payload size threshold.

4. Design a firmware-free GIF workaround path:
   - no AP firmware;
   - no raw I2C;
   - no live test until payloads are validated offline;
   - payload target under 20480 bytes;
   - first visible playback frame not black;
   - original static fix left untouched.

   This is now diagnostic only, not a final product direction, because a real 18 KB GIF still failed playback.

5. Keep AP firmware option as a later fallback:
   - preserve `F1[0x11] == 0x02` semantics;
   - internally replace AP 64 KB erase helper with repeated 4 KB sector erases;
   - only after firmware packaging/recovery is fully understood.

## Decision

Current decision:

```text
Stay away from firmware for now.
Pursue a host-only constrained GIF workaround first.
Do not repeat direct large-GIF 0x02 -> 0x01 forcing.
Do not run live tests until AP state emulation and payload validation are complete.
```


