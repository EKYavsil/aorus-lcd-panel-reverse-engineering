# AP 64 KB Path Definitive Investigation

Date: 2026-06-01

Scope: offline analysis only. No live DLL deploy, service stop/start, firmware updater execution, raw I2C, or panel command was used for this report.

## Current Question

The active question is no longer whether the static payload/converter is wrong. The static payload rendered correctly offline and the static panel write was fixed by changing only:

```text
F1[0x11]: 0x02 -> 0x01
```

The active question is:

```text
What is actually broken in the AP 64 KB path selected by F1[0x11] == 0x02?
```

## Evidence Read

Local offline artifacts:

```text
reports/AP_64KB_Erase_Program_Deep_Dive_20260601.md
reports/64KB_Path_Failure_Mechanism_Deep_Dive_20260601.md
reports/Post_F1_Wait_Tests_Revisited_20260601.md
exports/ap_custom_address_xrefs_AP_F14_gif_custom_state_fields.txt
docs/analysis/ap-flash-failure-propagation.md
docs/analysis/ap-spi-4byte-address-and-status.md
docs/analysis/f1-header-to-erase-mode.md
docs/analysis/f2-finalize-success-criteria.md
docs/analysis/upload-state-machine-version-comparison.md
```

External datasheet sanity checks:

- Winbond W25Q128JV: standard instructions include `0x20` sector erase, `0xD8` 64 KB block erase, `0x05` status register, and `0x06` write enable. Its listed 64 KB erase max is much longer than its typical time.
- Macronix MX25L12835F: its datasheet family describes WIP status behavior and a 64 KB block erase cycle that can exceed a short firmware poll window.

These datasheets are not proof of the exact chip on the panel. They are sanity checks showing that a 64 KB erase can legitimately take much longer than a short AP-side timeout and must be polled/handled correctly.

## Confirmed Host/AP Contract

Original static F1:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Important decoded fields:

| Field | Value |
|---|---:|
| destination | `0x01300000` |
| storage/type | `1` |
| pages/chunks | `0x01AA = 426` |
| chunk bytes | `426 * 256 = 0x1AA00` |
| erase selector | `F1[0x11] = 0x02` |

AP F1 parser:

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

Therefore:

| `F1[0x11]` | AP behavior |
|---:|---|
| `0x02` | 64 KB block erase path |
| `0x01` | 4 KB sector erase path |
| other non-2/non-3 | 4 KB sector erase path |
| `0x03` | special mode, not a normal media fix path |

## Static Boundary Math

Static media slot:

```text
slot base          = 0x01300000
visible data start = 0x0130000C
visible data size  = 320 * 170 * 2 = 0x1A900
program span       = 426 * 256 = 0x1AA00
```

64 KB erase mode requires two erase operations:

```text
0x01300000
0x01310000
```

Visible boundary:

```text
0x01310000 - 0x0130000C = 65524 bytes
65524 / 640 bytes-per-row ~= 102.38 rows
102.38 / 170 ~= 60.2%
```

That matches the observed failure shape:

```text
upper ~60% changes
lower ~40% remains black/stale
```

This is not a random display split. It is almost exactly the first 64 KB erase boundary inside the static slot.

## AP Upload State Machine

The AP upload loop has two phases.

### Phase 1: Erase

Relevant AP F1.4 decompile shape:

```c
if (*DAT_0000d160 == 1) {
    if (*DAT_0000d16c != 0) {
        *DAT_0000d16c -= 1;
        state_timer_0x98 = 0;

        if (*DAT_0000d178 == 2) {
            ok = FUN_0000b4d0(base + offset);  // 64 KB block erase
            if (ok == 0) return;
            offset += 0x10000;
        }
        else {
            ok = FUN_0000b910(base + offset);  // 4 KB sector erase
            if (ok == 0) return;
            offset += 0x1000;
        }
    }
    else {
        *DAT_0000d160 = 0;
        offset = 0;
    }
}
```

State variables:

| AP label | RAM | Meaning |
|---|---:|---|
| `DAT_0000d160` | `0x20000307` | upload erase/program phase |
| `DAT_0000d16c` | `0x20000316` | remaining erase operation count |
| `DAT_0000d174` | `0x20000310` | current offset |
| `DAT_0000d178` | `0x20000314` | erase selector from `F1[0x11]` |

### Phase 2: Program

Relevant AP F1.4 decompile shape:

```c
if (*DAT_0000d160 == 2) {
    state+0x2e = 1;
    ok = FUN_0000b6cc(staging, base + offset, 0x100);  // page program
    if (ok == 0) return;
    offset += 0x100;
    *DAT_0000d184 -= 1;
    if (*DAT_0000d184 == 0) {
        *DAT_0000d164 = 0;
    }
    state+0x2e = 0;
}
```

State variables:

| AP label | RAM | Meaning |
|---|---:|---|
| `DAT_0000d184` | `0x20000318` | remaining 256-byte pages |
| `DAT_0000d164` | `0x20000321` | upload pending/finalize gate |
| `state+0x2e` | AP state RAM | active upload/finalize state |

## Critical Helper Difference

### 4 KB Sector Erase Is Checked

```c
FUN_0000b910(address) {
    WREN();                   // 0x06
    send 0x20 + address;      // 4 KB sector erase
    ok = FUN_0000ba44();      // poll WIP via 0x05
    return ok != 0;
}
```

### 64 KB Block Erase Lies About Success

```c
FUN_0000b4d0(address) {
    WREN();                   // 0x06
    send 0xD8 + address;      // 64 KB block erase
    FUN_0000ba44();           // poll WIP via 0x05
    return 1;                 // poll result ignored
}
```

### Page Program Also Lies About Success

```c
FUN_0000b6cc(buf, address, 0x100) {
    WREN();                   // 0x06
    send 0x02 + address;
    send 256 bytes;
    FUN_0000ba44();           // poll WIP via 0x05
    return 1;                 // poll result ignored
}
```

So F1.4 does not totally ignore helper return values at the high-level loop. The subtler bug is worse:

```text
the high-level loop checks returns, but the 64 KB erase helper and page-program helper return success even after their internal poll can fail.
```

That means the AP state machine can advance through:

```text
erase offset
program offset
remaining page count
finalize state
```

without proving storage integrity.

## Why Longer Host Wait Did Not Fix It

Previous tests already tried this:

| Test | What changed | Result |
|---|---|---|
| 30-second post-F1 wait | Kept `F1[0x11]=0x02`, waited ~35s total before chunk 0 | still upper white / lower black |
| 10-second existing wait | Changed existing static wait from ~5s to ~10s | still negative |

This rules out a simple host-side explanation:

```text
GCC sends payload chunks too soon after F1.
```

The failure is more likely inside the AP-side erase loop:

```text
the second 64 KB erase is skipped, lost, timed out, or marked complete without proof.
```

Once AP has internally advanced past a failed erase, waiting longer on the host cannot force AP to retry it.

## Why Static Shows Lower Black Instead Of Full Failure

Static image payload is linear RGB565. If the first 64 KB block is erased/programmed and the second block is not, the panel can still display the first part correctly.

Likely static failure:

1. AP erases/programs `0x01300000..0x0130FFFF` well enough.
2. AP fails to erase/program `0x01310000..`.
3. AP still reports success.
4. Display reads contiguous static pixels.
5. Upper image region is new.
6. Lower region is stale/black because flash cells were not actually reset/programmed.

This also explains why white-image tests are very revealing. If lower flash has stuck `0` bits from stale data, page program cannot turn those `0` bits back into `1` unless erase really happened. So the lower region stays black when the intended pixels are white.

## Why GIF Fails Differently

GIF payload is not just linear pixels. It is a structured animation container:

```text
frame count
frame headers / end offsets
RLE frame streams
```

If 64 KB erase/program fails inside a GIF payload:

- a frame offset table can be wrong;
- an RLE block length can be stale;
- a frame stream can point into old data;
- the playback parser can wait forever, loop incorrectly, or render broken frames.

So static can show "upper good / lower black", while GIF can show:

```text
Loading stuck
black screen
broken animation
old/stale frames
panel state corruption
```

This is consistent with a common low-level 64 KB write problem producing different user-visible failures for linear static data versus structured GIF data.

## Why GIF `0x02 -> 0x01` Was Not Equivalent To Static

For GIF, `F1[0x11] == 0x02` is coupled to a timing/finalization field:

```c
if (bVar30 == 2) {
    *DAT_0000d168 = erase_count * 3000 + 3;
}
```

Later AP logic waits on this threshold:

```c
if (upload_pending && state_timer_0x98 >= *DAT_0000d168) {
    state+0x66 = state+0x6a;  // commit active frame count
    state+0x18 = state+0x68;  // commit active delay
    state+0x2e = 2;
    state+0x3d = 1;
    state+0x3f = 1;
}
```

That means changing GIF `F1[0x11]` from `0x02` to `0x01` changes two things at once:

1. erase unit changes from 64 KB to 4 KB;
2. the GIF long-upload timing/finalization threshold is not set by the same branch.

Static did not depend on this GIF timing branch in the same way. GIF does.

Therefore the failed GIF `0x02 -> 0x01` experiment does not prove the 4 KB erase idea is wrong. It proves that for GIF, erase mode and AP animation finalization state are coupled.

## New Xref Checks From This Pass

### `DAT_0000d168` / `0x20000308`

In the current AP F1.4 export set, the only direct write found to the GIF upload timing threshold was:

```text
ap_custom_address_xrefs_AP_F14_gif_custom_state_fields.txt:384
    *DAT_00008248 = (uint)uVar26 * 3000 + 3;
```

`DAT_00008248` and `DAT_0000d168` both point to:

```text
0x20000308
```

This strengthens the concern that GIF timing/finalization is only initialized by the `F1[0x11] == 0x02` parser branch in the currently inspected firmware text. I did not find a separate host command path in these exports that sets the same threshold while allowing `F1[0x11] == 0x01`.

### Protection / Alternate Status Opcodes

I also searched the current AP F1.4 exports for obvious SPI protection/status/control opcodes through the visible SPI byte helper call shape:

```text
0x01, 0x31, 0x11  // write status/config registers
0x35, 0x15        // read status/config registers 2/3
0x50              // volatile status-register write enable
0x7E, 0x98        // global lock/unlock on some Winbond parts
0x36, 0x39        // individual block lock/unlock on some Winbond parts
0x70              // flag status register on some parts
0x21, 0xDC        // dedicated 4-byte sector/block erase opcodes
```

No clear `FUN_0000b990(...)` hits were found for these opcode constants in the current export text. The visible SPI write/read command set remains essentially:

```text
0xB7  enter 4-byte address mode
0x06  write enable
0x05  read status register 1 / WIP
0x20  4 KB sector erase
0xD8  64 KB block erase
0x02  page program
0x03 / 0x3B read paths
```

This matters because the AP appears not to:

```text
verify WEL after WREN;
read protection bits;
clear protection bits;
read program/erase error flags;
use dedicated 4-byte erase opcodes;
read back media bytes after programming.
```

So the firmware has very little ability to distinguish "command accepted and completed" from "command timed out, was ignored, or left stale bytes behind."

## Most Likely Root Cause

The strongest current root cause is:

```text
AP 64 KB block erase path is not storage-safe.
```

More precise:

```text
The AP 64 KB helper issues 0xD8 and polls status, but it ignores the poll result and returns success.
The AP page-program helper does the same.
The high-level upload loop trusts those false successes and advances erase/program state.
The first 64 KB block can succeed, while the second block remains stale or unprogrammable.
F2 finalize does not verify storage contents, so the host sees success.
```

## Ranked Sub-Hypotheses

| Rank | Hypothesis | Why it fits | What weakens it |
|---:|---|---|---|
| 1 | Second 64 KB erase at `0x01310000` times out/fails and is hidden | Exact boundary match; static fix avoids `0xD8`; 64 KB poll ignored | Need AP-side readback or SPI trace to prove physical second erase failure |
| 2 | Page program after `0x01310000` times out/fails and is hidden | Page-program poll is also ignored; would create same visible lower-stale result | Static 4 KB erase fix indirectly suggests erase path is the main differentiator |
| 3 | 64 KB erase command accepted but AP advances timing/state before flash is truly ready | Fits ignored poll and datasheet erase-time risk | 30s host wait says the issue is AP-internal, not simply host pacing |
| 4 | Flash protection/window/bank state affects 64 KB `0xD8` differently than 4 KB `0x20` | Explains why 4 KB works while 64 KB fails | No AP evidence of status-register protection handling was found |
| 5 | 4-byte address mode is wrong | Address range is above 24-bit; AP relies on `0xB7` | 4 KB path writes same address range successfully, so global 4-byte mode is probably active |

## What We Know About Firmware Versions

F1.2/F1.3/F1.4 do not provide a clean escape from this in the available dumps:

- F1.2/F1.3 have weaker propagation; helpers are effectively void from the high-level path.
- F1.4 checks high-level helper returns, but 64 KB erase and page program helpers still hide internal poll failure.
- Firmware updater/IAP erase is for AP firmware space, not a confirmed wipe of custom media slot `0x01300000`.

This explains why firmware reinstall can restore panel runtime state but does not necessarily erase or repair stale custom media data.

## What Would Prove The Exact Failure

These are proof directions, not live instructions:

1. AP-side readback of custom media flash after original 64 KB upload:
   - verify whether `0x01310000..` remains old/black/stale.
2. SPI logic analyzer on AP flash:
   - confirm whether the second `0xD8` at `0x01310000` is issued;
   - confirm whether WIP remains set past AP timeout;
   - confirm whether page-program commands after boundary are ignored.
3. AP firmware patch in a lab-only environment:
   - make `FUN_0000b4d0()` return `FUN_0000ba44()` result;
   - make `FUN_0000b6cc()` return `FUN_0000ba44()` result;
   - confirm original path would fail instead of lying.
4. AP firmware patch in a lab-only environment:
   - keep GIF timing semantics but replace internal 64 KB erases with repeated 4 KB sector erases.

## Next Offline Research Targets

Before any more live tests, the useful offline work is:

1. Find every AP write to `DAT_0000d168` / `0x20000308`.
   - Goal: learn whether GIF timing can be set independently of `F1[0x11] == 0x02`.
2. Find every AP read/write to `DAT_0000d160`, `DAT_0000d164`, `DAT_0000d16c`, `DAT_0000d174`, `DAT_0000d184`.
   - Goal: fully reconstruct erase/program/finalize state.
3. Search AP firmware for SPI protection/status instructions:
   - `0x01`, `0x31`, `0x11`, `0x35`, `0x15`, `0x50`, `0x7E`, `0x98`, `0x36`, `0x39`, `0x70`.
   - Goal: confirm whether AP ever unlocks or checks protection/error bits.
4. Search for a media readback/verify path around `0x01300000`, `0x01310000`, `0x01000000`.
   - Goal: determine whether any safe protocol command can verify storage.
5. Compare public working GIF implementations against GCC:
   - not for erase opcodes, but for GIF finalization sequence and state timing.

## Current Decision

The 64 KB path is definitely suspect and likely defective.

The most precise statement we can make now:

```text
The AP upload implementation treats the 64 KB erase/page-program path as successful even when its own SPI status polling can fail. Static image corruption begins exactly at the first 64 KB boundary, and changing only the static erase selector to the 4 KB sector path fixes the full-frame write. GIF fails more severely because the same defective storage path corrupts a structured animation container, and because the erase selector byte is coupled to GIF finalization timing.
```


