# AP 64 KB Erase / Page Program Deep Dive - 2026-06-01

Scope: offline firmware dump analysis only. No live DLL, no service action, no firmware updater execution, no panel I2C access.

## Question

Why is the `F1[0x11] == 0x02` path suspect?

More specifically:

1. Why did avoiding it fix static image upload?
2. Why does host/GCC still see success when the panel shows stale/black data?
3. Why did firmware reinstall/update not permanently fix the custom media slot?
4. Why is GIF not fixed by simply forcing the same byte to `0x01`?

## Source Artifacts Read

Read-only artifacts:

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F14_flash_helpers.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F14_spi_lowlevel.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F14_spi_driver_helpers.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F12_upload_loop.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F13_upload_loop.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_exact_function_dump_AP_F14_upload_loop.txt
```

## AP Helper Map

F1.4 / modern AP helper mapping:

| Function | Operation | SPI opcode | Polls status? | Propagates poll failure? |
|---|---|---:|---:|---:|
| `FUN_0000bab4()` | write enable | `0x06` | no | always returns `1` after command |
| `FUN_0000ba44()` | read status / wait ready | `0x05` | yes | returns `0/1` |
| `FUN_0000b4d0(address)` | 64 KB block erase | `0xD8` | yes | **no** |
| `FUN_0000b910(address)` | 4 KB sector erase | `0x20` | yes | **yes** |
| `FUN_0000b6cc(buf,address,0x100)` | 256-byte page program | `0x02` | yes | **no** |
| `FUN_0000b54c(buf,address,len)` | SPI read | `0x03` | no status poll needed | returns success after read |
| `FUN_0000b760(address,dst,len,mode)` | fast/DMA-like read | `0x3B` | waits controller state | returns failure on timeout |

The asymmetry is the key finding:

```text
4 KB sector erase checks the status poll return.
64 KB block erase and page program call the same poll helper but ignore its return.
```

## Status Poll Helper

`FUN_0000ba44()`:

```c
*timeout = 300;
send 0x05;
do {
    tick_or_delay();
    status = spi_byte(0);
    if (*timeout < 1) {
        return 0;
    }
} while ((status & 1) != 0);
return 1;
```

Interpretation:

- It polls the SPI WIP/busy bit.
- It has a finite timeout.
- It can report failure.

So the AP firmware has a mechanism for detecting flash timeout.

## 4 KB Sector Erase Handles Failure

`FUN_0000b910(address)` sends SPI opcode `0x20`:

```c
FUN_0000bab4();              // write enable
send 0x20 + 24-bit address;  // 4 KB sector erase
iVar4 = FUN_0000ba44();      // wait ready
return iVar4 != 0 ? 1 : 0;
```

This path propagates failure.

That aligns with the successful static fix:

```text
F1[0x11] = 0x01 -> AP uses this 4 KB sector path
```

## 64 KB Block Erase Hides Failure

`FUN_0000b4d0(address)` sends SPI opcode `0xD8`:

```c
FUN_0000bab4();              // write enable
send 0xD8 + 24-bit address;  // 64 KB block erase
FUN_0000ba44();              // wait ready, return ignored
return 1;
```

This is the strongest concrete bug found so far.

If the second 64 KB block erase at `0x01310000` times out or fails, the helper can still return success to the upload state machine.

## Page Program Also Hides Failure

`FUN_0000b6cc(staging,address,0x100)` sends SPI opcode `0x02`:

```c
FUN_0000bab4();              // write enable
send 0x02 + 24-bit address;  // page program
send 0x100 bytes;
FUN_0000ba44();              // wait ready, return ignored
return 1;
```

So even if erase succeeds but page program fails after the first 64 KB region, AP can still advance:

```text
offset += 0x100
remaining_pages -= 1
```

and host can still see `SendImage=True`.

## F1.4 Upload Loop Checks Helper Return, But Helper Return Is Not Trustworthy

F1.4 upload loop:

```c
if (erase_mode == 0x02) {
    ok = FUN_0000b4d0(base + offset);
    if (ok == 0) return;
    offset += 0x10000;
}
else {
    ok = FUN_0000b910(base + offset);
    if (ok == 0) return;
    offset += 0x1000;
}

ok = FUN_0000b6cc(staging, base + offset, 0x100);
if (ok == 0) return;
offset += 0x100;
remaining -= 1;
```

At first glance F1.4 looks safer because it checks return values.

But:

- `FUN_0000b4d0()` always returns success after calling the poll helper.
- `FUN_0000b6cc()` always returns success after calling the poll helper.

Therefore the high-level return check is not enough.

## F1.2 / F1.3 Are Not Safer

F1.2 upload-loop shape:

```c
if (mode == 2) {
    FUN_0000af30(base + offset);
    offset += 0x10000;
}
else {
    FUN_0000b2f4(base + offset);
    offset += 0x1000;
}

FUN_0000b110(staging, base + offset, 0x100);
offset += 0x100;
remaining -= 1;
```

F1.3 upload-loop shape:

```c
if (mode == 2) {
    FUN_0000aff0(base + offset);
    offset += 0x10000;
}
else {
    FUN_0000b3b4(base + offset);
    offset += 0x1000;
}

FUN_0000b1d0(staging, base + offset, 0x100);
offset += 0x100;
remaining -= 1;
```

In these package dumps the high-level loop does not visibly check helper return values at all.

So:

```text
F1.4 improves the shape of the state machine, but the critical 64 KB/page-program helpers still hide failures.
F1.2/F1.3 do not show a more robust upload path.
```

This explains why firmware versions did not reliably wipe or repair the stuck custom media area.

## Static Failure Mechanism

Original static upload:

```text
F1 target         = 0x01300000
storage/type     = 1
chunks           = 426
program span     = 426 * 256 = 0x1AA00
F1[0x11]          = 0x02
erase unit       = 0x10000
erase addresses  = 0x01300000, 0x01310000
```

The visible data begins after the static container header:

```text
visible pixel start ~= 0x0130000C
```

The failure boundary:

```text
0x01310000 - 0x0130000C = 65524 bytes
```

This maps to about 60% of the 320x170 RGB565 visible frame:

```text
65524 / (320 * 170 * 2) ~= 60.2%
```

That matches the user-visible symptom:

```text
upper part changes, lower roughly 40% stays black/stale
```

## Why The Static Patch Works

Patched static upload:

```text
F1[0x11] = 0x01
erase unit = 0x1000
erase count = 27
erase addresses = 0x01300000, 0x01301000, ..., 0x0131A000
```

This avoids `FUN_0000b4d0()` and uses `FUN_0000b910()` instead.

Because the 4 KB path propagates status-poll failure and avoids the problematic 64 KB block operation, the same payload writes correctly.

This is better evidence than a delay/state hypothesis because:

- post-F1 wait increases did not fix static;
- clean apply/state sequences did not fix static;
- pData rendered correctly offline;
- only the erase mode byte fixed the symptom.

## Read Path Notes

`FUN_0000b54c()` normal read:

```text
opcode 0x03
24-bit address
read length bytes
```

`FUN_0000b760()` fast/DMA-like read:

```text
opcode 0x3B
24-bit address
dummy byte
max single length < 0x4000
returns 0 on controller timeout
```

GIF frame display code calls `FUN_0000b760()` in `0x500` byte units.

This makes the write/erase side still the stronger static culprit. The static symptom disappeared without changing display read logic.

## Current Strongest Static Root-Cause Statement

```text
For static custom image upload, GCC/AP selects F1 erase mode 0x02.
This routes AP firmware through the 64 KB block erase helper and normal 256-byte page program helper.
Both helpers poll SPI flash status but ignore the poll result.
The second 64 KB block around 0x01310000 can fail or remain stale while AP still advances offsets and reports upload success.
Changing only the static erase mode to 0x01 routes AP through the 4 KB sector erase helper, which propagates status and avoids the faulty 64 KB block path; the full static frame writes correctly.
```

## GIF Implication

GIF is not just static with another payload:

```text
GIF host target = 0
AP internal slot = 1
GIF storage/type = 2
display mode = 5
custom animation base can become 0x01000000
```

But large GIF uploads still use:

```text
F1[0x11] = 0x02
```

Therefore the GIF path may still suffer from the same low-level 64 KB/page-program blind spot.

The difference is that GIF uses `F1[0x11] == 0x02` for another purpose too:

```c
DAT_0000d168 = erase_count * 3000 + 3;
```

That field participates in upload finalization / animation commit timing.

So changing GIF `0x02 -> 0x01` is not equivalent to the static fix:

- it avoids the 64 KB path;
- but it also changes or removes the animation finalization threshold.

That likely explains why the GIF byte-change test destabilized the panel.

## Next Offline Targets

1. Search AP firmware for status-register write/protection logic:
   - `0x01` write status
   - `0x06` write enable
   - `0x04` write disable
   - block-protect bit handling
2. Check whether AP ever reads back media payload after page program.
3. Map GIF custom animation state after upload:
   - `state+0x19`
   - `state+0x1a`
   - `state+0x1b`
   - `state+0x47`
   - `state+0x66`
   - `state+0x18`
4. Determine how AP selects custom GIF base `0x01000000` versus built-in mode-5 range `0x01F4B00C..0x01F6590C`.
5. Do not run another live GIF patch until the animation commit state is better understood.



