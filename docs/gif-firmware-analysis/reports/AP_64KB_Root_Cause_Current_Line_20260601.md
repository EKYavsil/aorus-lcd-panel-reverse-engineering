# AP 64KB Root Cause Current Line - 2026-06-01

## Scope

This note keeps the investigation focused on the real 64KB erase path. The goal is not to escape the path by forcing GIF/custom uploads into the 4KB route. The goal is to understand why the AP firmware's 64KB route corrupts or incompletely updates panel storage, and whether that route can be repaired directly.

No live firmware update, panel command, DLL deployment, service control, or I2C operation is part of this analysis. This is offline AP firmware/disassembly analysis only.

## Current Best Finding

The strongest confirmed bug is in AP firmware flash-operation return handling:

- The 64KB erase helper `FUN_0000B4D0` issues SPI block erase opcode `0xD8`.
- It calls the shared status poll helper `FUN_0000BA44`.
- It ignores the poll result.
- It returns success unconditionally after the poll call.

The equivalent 4KB sector erase helper `FUN_0000B910` does not do this. It issues SPI sector erase opcode `0x20`, calls the same status poll helper, checks the return value, and returns failure if polling failed.

That is a concrete firmware bug, independent of host DLL behavior.

## Direct Evidence

Source dump:

`<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_patch_site_dump_AP_F14_pkg_patchsites.txt`

### 64KB Erase Helper: `FUN_0000B4D0`

Function role:

- Sends WREN through `FUN_0000BAB4`.
- Sends SPI erase opcode `0xD8`.
- Sends four address bytes.
- Calls status poll `FUN_0000BA44`.
- Forces return value to `1`.

Critical assembly:

```asm
0000b530  bl    0x0000ba44
0000b534  movs  r0,#0x1
```

Critical decompile shape:

```c
FUN_0000ba44();
uVar3 = 1;
```

Meaning: if `FUN_0000BA44` returns `0` because the erase did not complete before timeout, `FUN_0000B4D0` still reports success.

### 4KB Erase Helper: `FUN_0000B910`

Function role:

- Sends WREN through `FUN_0000BAB4`.
- Sends SPI erase opcode `0x20`.
- Sends four address bytes.
- Calls status poll `FUN_0000BA44`.
- Propagates failure.

Critical assembly:

```asm
0000b970  bl    0x0000ba44
0000b974  cmp   r0,#0x0
0000b976  beq   0x0000b91e
0000b978  movs  r0,#0x1
```

Critical decompile shape:

```c
iVar4 = FUN_0000ba44();
uVar2 = 0;
if (iVar4 != 0) {
  uVar2 = 1;
}
```

Meaning: the same poll failure that is hidden by the 64KB route is visible to the caller on the 4KB route.

### Shared Status Poll: `FUN_0000BA44`

Function role:

- Sets timeout counter to `300`.
- Sends SPI status read opcode `0x05`.
- Polls WIP bit until it clears.
- Calls `FUN_0000C370` inside the loop, probably watchdog/timer service.
- Returns `0` on timeout.
- Returns `1` when WIP clears.

Critical assembly:

```asm
0000ba4a  mov.w r0,#0x12c
0000ba66  movs  r0,#0x5
...
0000ba7c  cmp   timeout,#0
0000ba80  movs  r0,#0
...
0000ba9e  movs  r0,#0x1
```

Meaning: the firmware already has a failure signal for incomplete erase/program operations. The 64KB helper discards it.

### Page Program Helper: `FUN_0000B6CC`

The page program helper has the same class of bug:

- Sends WREN through `FUN_0000BAB4`.
- Sends SPI page program opcode `0x02`.
- Sends four address bytes.
- Writes up to 256 bytes.
- Calls `FUN_0000BA44`.
- Forces return value to `1`.

Critical decompile shape:

```c
FUN_0000ba44();
uVar3 = 1;
```

This means AP can also hide page-program failure after an incomplete erase or busy flash condition.

### WREN Helper: `FUN_0000BAB4`

The WREN helper sends SPI opcode `0x06`, but current evidence shows no WEL verification afterward. That means the firmware does not prove Write Enable Latch was actually set before erase/program.

This is not yet the primary proven root cause, but it is a second firmware robustness defect that can amplify the 64KB failure.

## Upload Path Selection

The AP upload handler chooses erase size from the F1 metadata field that the host sends:

- `F1[0x11] == 0x02` selects `0x10000` erase unit and calls `FUN_0000B4D0`.
- Other normal image path values select `0x1000` erase unit and call `FUN_0000B910`.

Relevant caller shape:

```c
if (*DAT_0000d178 == 2) {
  iVar10 = FUN_0000b4d0(base + offset);
  if (iVar10 == 0) return;
  offset += 0x10000;
}
else {
  iVar10 = FUN_0000b910(base + offset);
  if (iVar10 == 0) return;
  offset += 0x1000;
}
```

The caller is prepared to abort on erase failure. The problem is that `FUN_0000B4D0` almost never exposes that failure.

## Static Symptom Alignment

The static image result aligns unusually well with a 64KB-boundary failure:

- Static slot base: `0x01300000`
- Static visible payload starts around: `0x0130000C`
- Static full frame size: `320 * 170 * 2 = 108800 bytes = 0x1A900`
- Host sends: `426 * 256 = 109056 bytes = 0x1AA00`
- First 64KB boundary: `0x01310000`
- Bytes from `0x0130000C` to `0x01310000`: `65524`
- `65524 / 640 bytes per row = about 102.38 rows`
- `102.38 / 170 = about 60.2%`

Observed static failure was upper roughly 60% updated and lower roughly 40% black/stale. That boundary match is too strong to treat as coincidence.

The successful static workaround changed the host's F1 selector from `0x02` to `0x01`, causing the AP to use 4KB erase instead of 64KB erase. That avoided the defective `FUN_0000B4D0` path.

## What Is Proven

1. There are two real AP erase paths: 64KB and 4KB.
2. The 64KB path uses SPI opcode `0xD8`.
3. The 4KB path uses SPI opcode `0x20`.
4. Both use the same status poll helper.
5. The poll helper can return failure.
6. The 4KB helper propagates poll failure.
7. The 64KB helper ignores poll failure and returns success.
8. Page program also ignores poll failure.
9. The static image failure boundary matches the first 64KB block boundary.
10. Switching static image to the 4KB path fixes the static image corruption.

## What Is Not Yet Proven

The exact physical trigger inside the flash transaction is not fully proven yet. The strongest candidates are:

1. `0xD8` 64KB erase exceeds the AP's `300`-tick status-poll timeout.
2. The second 64KB block erase is started while the previous operation is still busy or did not actually complete.
3. WREN sometimes does not latch, and the helper does not verify WEL.
4. Flash protection/status-register state affects `0xD8` differently from `0x20`.
5. Page program fails after the bad boundary because the target block is not erased, and the page-program helper also hides that failure.

The root-cause chain most consistent with all evidence:

```text
F1[0x11] == 0x02
  -> AP chooses 64KB erase path
  -> 0xD8 erase issued for 0x01300000, then 0x01310000...
  -> poll times out or flash op is not accepted/completed
  -> 64KB helper ignores poll failure and returns success
  -> AP advances offset and begins programming anyway
  -> lower region cannot be rewritten correctly because erase/program precondition failed
  -> page-program helper also hides failures
  -> host sees successful upload while panel storage is partially stale/corrupt
```

## Why This Matters For GIF

GIF uses the same `F1[0x11] == 0x02` 64KB route, but GIF is larger and uses different target/storage/display semantics. That makes it more sensitive to the broken 64KB path.

The previous attempt to force GIF toward a 4KB path is not equivalent to the static fix because `F1[0x11] == 0x02` appears coupled to more than erase size in the GIF path. It also affects AP-side state/timing/finalization semantics. Therefore the primary fix should target the broken 64KB erase/program implementation, not simply route GIF away from it.

## Direct Repair Candidates For The 64KB Path

These are firmware-side repair candidates, listed from most diagnostic to most corrective. None should be applied live without separate staging and risk review.

### Candidate A: Propagate `FUN_0000BA44` result in `FUN_0000B4D0`

Change 64KB helper behavior to match 4KB helper behavior:

```text
after BL FUN_0000BA44:
  if r0 == 0 return 0
  else return 1
```

Expected effect:

- Confirms whether 64KB erase is actually timing out/failing.
- Prevents silent corruption.
- May cause uploads to abort instead of corrupting display storage.

Limit:

- This may expose the failure rather than fixing it.

### Candidate B: Increase poll timeout for 64KB erase

If the 64KB erase is simply slower than the hardcoded `300`-tick timeout, the correct fix is to let the 64KB operation complete before returning.

Expected effect:

- Could repair 64KB erase if `0xD8` works but needs longer.

Risk:

- Global timeout changes may affect other SPI operations if patched in `FUN_0000BA44` directly.
- Safer design is a 64KB-specific longer poll path, but that is more invasive.

### Candidate C: Propagate page-program poll result in `FUN_0000B6CC`

Page program should not report success if the status poll fails.

Expected effect:

- Prevents hidden program corruption after failed erase.
- Improves diagnostics.

Limit:

- If erase remains broken, this still aborts rather than repairs.

### Candidate D: Verify WEL after WREN

After opcode `0x06`, read status `0x05` and verify WEL bit before erase/program.

Expected effect:

- Detects write-enable failures cleanly.

Risk:

- More invasive and requires careful integration with existing chip-select/status-read behavior.

## Current Conclusion

The current root-cause line should be:

```text
The AP firmware 64KB flash path is defective because it hides erase completion failure.
The most likely physical failure is an incomplete/timed-out 0xD8 64KB erase at or after the first 64KB boundary.
The page-program path then hides subsequent write failures, so the host receives success while flash contents are partially stale.
```

This explains why:

- Static image failed at about the 64KB boundary.
- Static image was fixed by changing only the F1 erase selector to the 4KB route.
- GIF remains difficult because it depends on the 64KB route and larger storage semantics.
- Firmware updater/reset flows can appear inconsistent if the same AP flash primitives or status assumptions are involved.

## Next Offline Steps

1. Build a compact AP SPI-command map:
   - every caller of the SPI byte-transfer helper,
   - constants `0x06`, `0x05`, `0x20`, `0xD8`, `0x02`,
   - whether each caller checks `FUN_0000BA44`.

2. Search AP firmware for status-register/protection behavior:
   - `0x9F` JEDEC ID read,
   - `0x35` / `0x15` status register reads,
   - `0x01` / `0x31` / `0x11` status register writes,
   - `0x50` volatile status register write enable,
   - WEL bit checks.

3. Determine whether `300` ticks is plausibly shorter than the 64KB erase time:
   - identify timer decrement source for `DAT_0000BAA8`,
   - estimate tick period,
   - compare with common SPI flash 64KB block erase timings.

4. Design offline-only patch candidates:
   - `B4D0` return propagation only,
   - `B4D0` longer timeout plus propagation,
   - `B6CC` return propagation,
   - optional WEL verification.

5. Do not proceed to live firmware testing until the patch target, binary diff, checksum/CRC impact, recovery path, and expected failure mode are documented.



