# Dual-F1 Race And Feasibility

Date: 2026-06-01

Scope: offline research only. No live DLL deploy, no service stop/start, no firmware updater execution, no panel command, no GIF/static upload test.

## Question

Can we make GIF uploads use the reliable 4 KB erase path while preserving the GIF timing/finalization side effect normally created by `F1[0x11] == 0x02`?

The proposed host-only idea was:

```text
F2 01 start
F1 same GIF metadata, F1[0x11] = 0x02   // set AP GIF timing threshold
F1 same GIF metadata, F1[0x11] = 0x01   // switch AP erase selector to 4 KB
payload pages
F2 02 finish
```

## Why This Was Worth Studying

AP F1 parser behavior:

```c
bVar30 = F1[0x11];

if (bVar30 == 3)      erase_unit = 1;
else if (bVar30 == 2) erase_unit = 0x10000;
else                  erase_unit = 0x1000;

erase_count = ((page_count << 8) / erase_unit) + 1;

if (bVar30 == 2) {
    timing_threshold = erase_count * 3000 + 3;
}
```

So `F1[0x11] == 0x02` does two things:

| Effect | Needed For GIF? | Problem |
|---|---:|---|
| Selects 64 KB erase | No | This path is unreliable on this panel. |
| Sets AP timing/finalization threshold | Probably yes for large GIF | Direct `0x02 -> 0x01` loses this side effect. |

The dual-F1 theory tries to split those two effects without AP firmware patching.

## Host SendImage Insertion Point

Host `ucVga.Api.GvLcdApi.SendImage` order from IL:

```text
Sleep(2000)
F2 01 start upload
Sleep(500)
build F1 metadata
SendData(F1)
if F1 fails -> return false
host prepare sleep loop
Sleep(1000)
payload chunks
Sleep(500)
F2 02 finish
```

The clean host insertion point exists:

```text
after original F1 SendData succeeds
before the host prepare sleep loop
```

A theoretical patch could clone the first F1 packet, change only byte `0x11` from `0x02` to `0x01`, send that second F1, then continue normal payload flow.

## New AP Receive-Path Evidence

Generated offline dump:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_receive_path_exact_dump_AP_F14_pkg_receive.txt
```

Ghidra script:

```text
<local-gif-investigation-dir>\scratch\APReceivePathExactDump.java
```

Relevant functions:

| Function | Role |
|---|---|
| `FUN_0000CB54` | AP main loop |
| `FUN_00007DB8` | AP host-command parser |
| `FUN_00001CF0` | special reset/boot-style path, not a normal multi-command queue |
| `FUN_000091E8` | receive/response peripheral setup helper |
| `FUN_00009330` | bit set/clear helper for response/interrupt state |

`FUN_00001CF0` is not a normal command queue drain. It checks a special pattern:

```c
if (*(int *)(state + 0xc) == 8) {
    if ((*buf == 0x8101) && (buf[1] == 0x7efe)) {
        ...
        // response/reboot/reset-like path
        while (true) {}
    }
}
```

This does not help dual-F1. It is a special path, not a FIFO that lets two host commands be parsed before the upload worker runs.

Normal host command parsing is centered on:

```text
DAT_000081b8      receive descriptor/state
DAT_000081b8[1]   packet-ready/parser-state byte
DAT_000081cc      command payload buffer
```

The parser begins with:

```c
if (DAT_000081b8[1] != 0) {
    if (DAT_000081b8[1] != 1) {
        return;
    }
    ...
}
```

This looks like a single active command buffer/state, not a visible multi-packet queue.

## Main-Loop Race

AP main loop order:

```c
FUN_0000c370();
...
FUN_00001cf0();
FUN_00007db8();       // parse one visible host command state
...
if (*upload_phase == 1) {
    if (*erase_count != 0) {
        erase_count--;
        upload_elapsed = 0;

        if (*erase_selector == 2) {
            FUN_0000b4d0(base + offset);   // 64 KB erase
            offset += 0x10000;
        } else {
            FUN_0000b910(base + offset);   // 4 KB erase
            offset += 0x1000;
        }

        goto loop_start;
    }
}
```

This is the key risk:

```text
F1 mode2 parsed
upload_phase = 1
erase_selector = 2
erase_count = 64KB count
timing_threshold = erase_count * 3000 + 3

same AP loop continues
erase worker sees upload_phase == 1
first 64 KB erase can start before second F1 arrives
```

Because each host `SendData(F1)` call is a separate I2C transaction, the host cannot prove offline that the second F1 lands before the AP loop performs one erase step.

## State Overwrite If Second F1 Arrives In Time

If the second F1 is parsed before any erase worker step, it should update the important upload fields:

| Field | First F1 mode2 | Second F1 mode1 |
|---|---|---|
| destination/storage selector | GIF values | same GIF values |
| page count | full GIF page count | same full GIF page count |
| erase selector | `2` | `1` |
| erase count | 64 KB count | 4 KB count |
| upload phase | `1` | `1` |
| upload offset | `0` | `0` |
| pending frame count/delay | GIF values | same GIF values |
| timing threshold | set | not visibly cleared |

So the theory is internally coherent only in this narrow timing window.

## Host Wait/Prep Problem

For the known large GIF example:

```text
payload size       ~= 1,748,766 bytes
page count         = 6832
mode2 erase_count  = 27
mode1 erase_count  = 428
```

Original large-mode host prep:

```text
27 * 2000 ms = about 54 s
```

4 KB-mode equivalent prep:

```text
428 * 400 ms = about 171 s
```

If dual-F1 switches AP into 4 KB erase but leaves the host prep loop in large-mode timing, payload pages may begin while AP is still erasing. A correct host-only dual-F1 design would likely need both:

1. send the second F1 with `F1[0x11] = 0x01`;
2. force the host prepare loop to use the 4 KB count/timing after that second F1.

That makes the patch larger than "send one extra F1".

## Existing Tests That Matter

Old static wait experiments:

| Test | Meaning |
|---|---|
| Extra 30-second post-F1 wait on original 64 KB static path | Did not fix lower black. |
| Natural wait increased from about 5 s to about 10 s | Did not fix lower black. |

Conclusion:

Host-side waiting after F1 does not repair the broken 64 KB erase once AP has already entered or falsely completed that internal erase path.

Tiny GIF finding:

| Test | Result |
|---|---|
| Original DLL + tiny natural mode1 GIF | Panel did not lock, upload appeared to complete, but GIF stayed black/white failure persisted. |

Conclusion:

GIF has a display/playback-state problem in addition to the 64 KB erase problem. Fixing 64 KB may be necessary for large GIFs, but may not be sufficient for all GIF playback behavior.

## Feasibility Verdict

Dual-F1 remains technically possible as a host-only experiment, but the new receive-path evidence makes it risky:

| Question | Current answer |
|---|---|
| Can host insert a second F1 cleanly? | Yes. |
| Does AP F1 mode2 set the missing timing field? | Yes. |
| Does AP F1 mode1 avoid 64 KB erase? | Yes. |
| Does F1 mode1 visibly clear timing threshold? | No evidence. |
| Is there a visible AP queue that guarantees both F1s parse before erase? | No. |
| Can first F1 mode2 start the broken 64 KB erase before second F1? | Yes, plausible. |
| Would host prep timing also need adjustment? | Yes, probably. |
| Is this ready for live testing? | No. |

## Safer Interpretation

Dual-F1 is not the best next live direction unless we accept the possibility of touching the broken 64 KB path again.

The cleaner technical repair is still AP-side:

```text
keep F1[0x11] == 0x02 semantics
but make the AP 64 KB erase helper reliable
```

The most coherent AP repair concept remains:

```text
FUN_0000B4D0(address):
    replace one unreliable 64 KB erase with 16 reliable 4 KB sector erases
```

That preserves:

- large GIF timing/finalization threshold;
- original host/GCC behavior;
- original mode2 protocol semantics;
- reliable sector erase behavior already proven by the static fix.

But AP firmware patching is a separate safety problem and should not be attempted until package checksum/signature/recovery are fully understood.

## Recommended Next Offline Work

1. Do not live-test dual-F1 yet.
2. Search AP receive implementation deeper only if we can identify the exact I2C ISR/ring-buffer path; current visible state looks single-buffer.
3. Continue AP firmware package safety analysis:
   - AP image checksum/signature;
   - updater validation path;
   - bootloader validation path;
   - recovery if AP image is invalid.
4. If staying host-only, design a non-live patch plan first:
   - second F1 insertion;
   - host prep loop forced to 4 KB timing;
   - full trace;
   - automatic restore;
   - no GIF state pre-manipulation;
   - tiny GIF first.

## Bottom Line

The dual-F1 path is clever and not impossible, but current AP evidence says it is race-prone.

For a robust fix that can explain both static and large GIF behavior, the better engineering target is still the broken AP 64 KB erase helper, not more host-side choreography around it.


