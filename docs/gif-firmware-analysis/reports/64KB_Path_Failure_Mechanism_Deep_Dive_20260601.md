# 64 KB Path Failure Mechanism Deep Dive

Date: 2026-06-01

Scope: offline AP firmware analysis plus previously observed static test behavior. No live DLL, service, firmware updater, raw I2C, or panel command was used for this note.

## Question

The earlier finding was:

```text
64 KB erase and page-program helpers ignore the status-poll return.
```

That is real, but it is not enough by itself. The better question is:

```text
What actual failure is being hidden by the ignored status result?
```

## Current Answer

The strongest current model was initially:

```text
The 64 KB block erase operation can outlast the AP's 300-tick status timeout.
The AP ignores that timeout and continues the erase/program state machine.
As a result, the next erase or page-program command can be sent while the SPI flash is still busy.
Commands sent while the flash is busy are ignored or ineffective.
This can leave the second 64 KB region unerased, while the host still sees a successful upload.
```

Old wait experiments now narrow this model. The final problem is likely not merely "return value ignored", and it is also not likely to be a simple host-side "wait longer after F1 before chunks" issue.

The more precise problem is:

```text
unreliable AP 64 KB erase command acceptance/completion + ignored failure + no readback verification
```

## Post-F1 Wait Tests Revisited

Older static-only experiments directly tested whether the original 64 KB path simply needed more host-side time between F1 and the first payload chunk.

| Test | Exact behavior | F1 mode | Result |
|---|---|---:|---|
| 30-second extra post-F1 wait | Kept original F1/F2/payload, then waited 30000 ms after the existing wait and before chunk 0 | `F1[0x11] = 0x02` | negative: upper white / lower black |
| 10-second existing wait variant | Changed only the original wait constant from 2000 to 4500, making the static wait about 10000 ms instead of 5000 ms | `F1[0x11] = 0x02` | negative / did not fix the lower black region |

The 30-second log is especially important:

```text
F1_METADATA ... raw=F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00 ... decoded.packetMode=2
POST_F1_WAIT_START extraMs=30000 existingEstimatedMs=5000 totalEstimatedMs=35000
POST_F1_WAIT_END elapsedMs=30002
FIRST_CHUNK ... result=True
CHUNK_SUMMARY ... actualChunks=426
F2_FINALIZE ... result=True
SENDIMAGE_RETURN ... result=True
```

This weakens the simple theory:

```text
F1 starts erase, GCC sends chunks too early, and a longer host delay before chunks is enough.
```

If the only issue were "AP still erasing when chunk 0 arrives", an extra 30 seconds before chunk 0 should have made the original 64 KB path work. It did not.

The better narrowed model is:

```text
The AP's internal 64 KB erase sequence itself fails or skips/loses the second 64 KB erase.
Host waiting after F1 cannot force the AP to retry a failed/missed internal erase command.
The ignored status result hides that internal failure, and the host still receives success.
```

## Why This Fits Static Image Exactly

Static custom image geometry:

```text
slot base             = 0x01300000
visible data start    = 0x0130000C
host chunks           = 426
programmed bytes      = 426 * 256 = 0x1AA00
erase mode byte       = F1[0x11] = 0x02
AP erase unit         = 0x10000
AP erase count        = ((426 * 256) / 0x10000) + 1 = 2
erase addresses       = 0x01300000, 0x01310000
```

Visible boundary:

```text
0x01310000 - 0x0130000C = 65524 bytes
65524 / 640 bytes-per-row ~= 102.38 rows
102.38 / 170 ~= 60.2%
```

Observed symptom:

```text
upper roughly 60% changes
lower roughly 40% remains black/stale
```

This is exactly where the second 64 KB block begins.

## AP Code: 64 KB Path

64 KB block erase helper:

```c
FUN_0000b4d0(address) {
  if (address >= 0x04000000) return 0;

  ok = FUN_0000bab4();       // WREN
  if (ok == 0) return 0;

  send 0xD8 + 4 address bytes;
  FUN_0000ba44();            // wait WIP clear, return ignored
  return 1;
}
```

Status poll helper:

```c
FUN_0000ba44() {
  *0x200000FE = 300;
  send 0x05;                 // Read Status Register

  do {
    FUN_0000c370();          // watchdog/timer service, not a real delay
    status = spi_byte(0);
    if (*0x200000FE < 1) {
      return 0;
    }
  } while ((status & 1) != 0);

  return 1;
}
```

`FUN_0000c370()`:

```c
*0x40068000 = 0x5FA00001;
```

This looks like a watchdog/timer service write, not a normal blocking sleep. The timeout counter at `0x200000FE` is presumably decremented elsewhere by AP timer/interrupt logic.

## AP Code: 4 KB Path

4 KB sector erase helper:

```c
FUN_0000b910(address) {
  if (address >= 0x04000000) return 0;

  ok = FUN_0000bab4();       // WREN
  if (ok == 0) return 0;

  send 0x20 + 4 address bytes;
  ok = FUN_0000ba44();       // wait WIP clear
  return ok != 0;
}
```

So the 4 KB path differs in two ways:

1. It uses smaller erase operations.
2. It propagates timeout/failure instead of hiding it.

The static fix changed only:

```text
F1[0x11] 0x02 -> 0x01
```

That routes AP away from `0xD8` 64 KB block erase and into `0x20` 4 KB sector erase.

## Likely Failure Timeline

The most coherent failure chain for the original static upload, after accounting for the negative post-F1 wait tests, is:

1. AP receives F1 with `F1[0x11] = 0x02`.
2. AP computes two 64 KB erases:

```text
0x01300000
0x01310000
```

3. AP starts first `0xD8` erase at `0x01300000`.
4. Somewhere inside AP's own 64 KB erase loop, the erase operation for `0x01310000` is not completed, not latched, or not verified.
5. Possible sub-cases:

```text
first 0xD8 is still busy and second 0xD8 is ignored;
or second 0xD8 times out and the timeout is ignored;
or AP's 64 KB erase state machine advances without proving the second block is erased.
```

6. Host-side waiting after F1 cannot repair this if AP has already skipped or mis-recorded the internal erase result.
7. AP believes both blocks were erased.
8. AP resets offset and starts page programming from `0x01300000`.
9. First 64 KB block can now program correctly.
10. Second 64 KB block was never erased, so page-programming there cannot reliably change old `0` bits back to `1`.
11. Lower image region remains stale/black/corrupted.
12. Host sees success because AP never verifies flash contents.

This explains why the upper part changes but the lower part remains wrong.

## Why Ignore Is Not The Whole Root Cause

If the 64 KB erase always completed within the AP timeout, ignoring the return would be harmless most of the time.

The actual harmful combination is:

```text
64 KB erase can exceed AP timeout
+ timeout result ignored
+ AP sends the next command too early
+ no readback verify
```

So the ignored return is the bug that hides the failure, but the likely trigger is insufficient wait/timeout handling for `0xD8`.

## Datasheet Sanity Check

The exact LCD panel SPI flash chip is not yet identified, so this is not a chip-specific proof. It is still a useful sanity check because common 128 Mbit SPI NOR parts have 64 KB erase times that can exceed a 300 ms-style wait.

Examples from manufacturer datasheets:

| Part family | 4 KB sector erase | 64 KB block erase | Source |
|---|---:|---:|---|
| Winbond W25Q128JV | typical 45 ms, max 400 ms | typical 150 ms, max 2000 ms | Winbond datasheet |
| Macronix MX25L12835F | not used here as exact chip proof | typical 280 ms, max 650 ms for 64 KB block erase | Macronix datasheet |

This is consistent with the AP problem:

```text
300 ticks may be barely enough for a typical 64 KB erase on some chips and too short for worst-case erase.
4 KB sector erase is much more likely to finish within the timeout.
```

## Alternative Hypotheses Ranked

| Hypothesis | Fit | Current rank |
|---|---|---|
| AP 64 KB erase loop does not reliably complete/verify the second block | Explains exact 64 KB boundary, hidden success, static 4 KB fix, and negative host-wait tests | strongest |
| Second 64 KB block erase command is ignored/lost inside AP's own loop | Explains upper good / lower stale; host wait after F1 cannot fix once skipped | strongest sub-case |
| 64 KB erase exceeds AP timeout; AP proceeds too early | Still plausible, but old 30s post-F1 wait shows the problem is not solved by host-side waiting before chunks | plausible sub-case |
| 64 KB helper ignores status but erase still completed; page program later fails | Possible, but less specific than command-overlap model | plausible |
| 64 KB opcode `0xD8` unsupported | Unlikely; `0xD8` is a common standard 64 KB erase opcode | weak |
| 64 KB address misalignment | Unlikely; `0x01300000` and `0x01310000` are aligned | weak |
| 4-byte address mode missing globally | Unlikely; upper region writes and 4 KB path writes same >24-bit address range | weak |
| Flash protection blocks lower region | Weaker, because 4 KB sector path successfully wrote the same lower region | weak-to-plausible only if protection differs by erase opcode |
| Host payload/converter issue | Static pData rendered correct offline | mostly eliminated |
| Display/read path issue | Static fixed without changing display/read path | mostly eliminated |

## What Would Prove This

Without doing it live now, the clean proof would be one of these:

1. AP-side or logic-analyzer trace showing first `0xD8` erase WIP remains set past AP timeout.
2. Readback showing `0x01310000..` remains unerased after an original 64 KB upload but is erased after the 4 KB patched upload.
3. AP-side evidence showing whether the second 64 KB erase at `0x01310000` is actually issued and accepted.
4. A test patch that changes only `FUN_0000b4d0()` to propagate `FUN_0000ba44()` failure, proving original AP would have reported failure instead of success.

These are future proof paths, not instructions to run now.

## Implication For "Can We Fix 64 KB Path?"

There are at least three theoretical fix classes:

### Fix Class A: Avoid 64 KB Path

Use the 4 KB sector path where possible.

This is already proven for static custom image.

Risk for GIF:

```text
F1[0x11] also controls GIF timing/finalization state, so naive 0x02 -> 0x01 is not safe for large GIFs.
```

### Fix Class B: Make 64 KB Path Wait Correctly

Keep `F1[0x11] = 0x02`, but make AP/host wait long enough between erases and before page programming.

Problem:

```text
The wait/poll happens inside AP firmware. Host cannot directly delay between the two internal 64 KB erase commands unless the protocol exposes a split erase/program transaction, which has not been found.
```

Host-side delays after F1 are not equivalent if AP internally executes both 64 KB erases before returning to a host-observable point.

### Fix Class C: Patch AP Firmware

Change AP code so:

```text
FUN_0000b4d0 returns failure when FUN_0000ba44 fails;
or its timeout is increased;
or it waits for WIP clear without the too-short timeout;
or it uses 4 KB sector erases internally for media slots.
```

This would be the cleanest AP-level fix but is much higher risk than host DLL patching.

## Current Conclusion

The root problem is probably not "old cache" anymore.

The strongest current explanation is now:

```text
The AP 64 KB block erase path is unreliable for this media slot. The failure is not fixed by adding host-side wait after F1, so the likely fault is inside AP's internal 64 KB erase loop: the second 64 KB erase is skipped, lost, timed out, or left unverified. Because the timeout/status result is ignored and there is no readback verification, the AP reports success while the second 64 KB region remains unerased or partially unprogrammable.
```

The ignored return is therefore a key bug, but the underlying trigger is likely AP-internal 64 KB erase command acceptance/completion/verification failure, not simply stale cache and not simply "GCC should wait longer before chunks."


