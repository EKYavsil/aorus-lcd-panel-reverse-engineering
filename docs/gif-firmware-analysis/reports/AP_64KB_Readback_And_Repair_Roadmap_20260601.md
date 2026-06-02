# AP 64 KB Readback And Repair Roadmap

Date: 2026-06-01

Scope: offline research only. No live DLL deploy, service control, firmware updater execution, raw I2C command, panel write, or panel read was performed.

## Goal

The current objective is:

```text
learn exactly why the AP 64 KB upload path fails, and identify the lowest-risk repair path if possible
```

This report focuses on two separation questions:

1. Can we prove whether the second 64 KB block is physically stale by reading custom media flash back?
2. If not yet, what repair classes are technically plausible from the current AP evidence?

## Fresh Offline Work Performed

Ghidra headless was run against the existing offline AP projects:

```text
<local-tools-dir>\ghidra_12.0.4_PUBLIC\support\analyzeHeadless.bat
```

Projects used:

```text
AP_F12_pkg
AP_F13_pkg
AP_F14_pkg
```

Outputs written under:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs
```

Generated files:

```text
ap_page_program_pattern_search_AP_F12_pkg_deep.txt
ap_page_program_pattern_search_AP_F13_pkg_deep.txt
ap_page_program_pattern_search_AP_F14_pkg_deep.txt
ap_read_caller_graph_AP_F14_pkg_deep.txt
```

Note: The F12/F13 generic pattern-search script did not match the renamed helper functions in those projects, but the previously generated exact dumps still confirm their helper shapes. The F14 project produced the expected high-confidence matches.

## F1.4 Helper Confirmation

Fresh F1.4 pattern output confirms the critical helper split:

| Function | Opcode | Poll | Poll result behavior |
|---|---:|---|---|
| `FUN_0000b4d0` | `0xD8` 64 KB block erase | calls `FUN_0000ba44()` | ignored, returns success |
| `FUN_0000b910` | `0x20` 4 KB sector erase | calls `FUN_0000ba44()` | propagated |
| `FUN_0000b6cc` | `0x02` 256-byte page program | calls `FUN_0000ba44()` | ignored, returns success |

The relevant F1.4 output files are:

```text
ghidra_64kb_deep_outputs/ap_page_program_pattern_search_AP_F14_pkg_deep.txt
private/local_artifacts.../ap_exact_function_dump_AP_F14_flash_helpers.txt
```

This means F1.4 is only partially improved:

```text
the high-level upload loop checks helper return values, but two important helpers can still lie.
```

## F1.2/F1.3 Helper Confirmation

The older exact dumps show weaker behavior:

| Version | 64 KB erase | 4 KB erase | page program | Status poll |
|---|---|---|---|---|
| F1.2 | `FUN_0000af30`, void | `FUN_0000b2f4`, void | `FUN_0000b110`, void | `FUN_0000b3f8`, void |
| F1.3 | `FUN_0000aff0`, void | `FUN_0000b3b4`, void | `FUN_0000b1d0`, void | `FUN_0000b4b8`, void |
| F1.4 | `FUN_0000b4d0`, returns but hides poll | `FUN_0000b910`, returns poll | `FUN_0000b6cc`, returns but hides poll | `FUN_0000ba44`, returns `0/1` |

So firmware reinstall across F1.2/F1.3/F1.4 is not expected to reliably fix media storage writes:

- F1.2/F1.3 have no meaningful propagation at helper level.
- F1.4 has meaningful propagation only for 4 KB sector erase.
- F1.4 still hides 64 KB erase and page-program poll failure.

## Readback Search Result

AP F1.4 contains SPI flash read helpers:

| Function | Read style | Notes |
|---|---|---|
| `FUN_0000b54c` | normal read, opcode `0x03` | used by internal config/parser paths |
| `FUN_0000b760` | fast/DMA-like read, opcode `0x3B` | used heavily by display/frame decode paths |

The caller graph confirms these reads feed internal AP display/config logic. Important nodes include:

```text
FUN_0000b760 <- many display/frame readers
FUN_0000b54c <- config/parser helpers
FUN_0000cb54 -> several read/setup helpers during AP main loop
```

Known host readback-like commands in the parser:

| Host opcode | Observed meaning |
|---:|---|
| `0xD6` | firmware readback |
| `0xD7` | upload-state-ish readback; returns `DAT_00008240`/`DAT_0000d160` style state |
| `0xD8` | compact state readback; returns a few RAM status bytes |
| `0xDE` | current mode readback |
| `0xDF` | overlay/readback flags |

The parser snippet for `0xD7` and `0xD8` is state-oriented:

```c
if (opcode == 0xd7) {
    response[0] = 0xd7;
    response[1] = *DAT_00008240;  // upload phase/state
}

if (opcode == 0xd8) {
    response[0] = 0xd8;
    response[1] = *DAT_000081c4;
    response[2] = *DAT_00008260;
    response[3] = *DAT_00008264;
}
```

I did not find a known host command that accepts an arbitrary flash address and returns bytes from:

```text
0x01300000
0x01310000
0x01000000
```

## Readback Conclusion

There is no currently confirmed safe host-level custom media flash readback.

The AP can read flash internally, but the exposed host readback commands found so far appear to return state/mode/config, not arbitrary media bytes.

Therefore, to prove physically stale bytes at `0x01310000`, the future options are:

1. Find a hidden host command that wraps `FUN_0000b54c` or `FUN_0000b760` with user-controlled address/length.
2. Instrument AP firmware in a lab-only environment.
3. Use a hardware SPI logic analyzer or physical flash dump.
4. Infer from controlled write tests, as was done with the static `0x02 -> 0x01` fix.

Option 1 is the only software-only route that would be acceptable before more live tests, but it is not found yet.

## What We Can Already Say About The Failure

The current strongest split is:

```text
64 KB erase failure is more likely than pure page-program failure,
but page-program failure still remains part of the same unsafe path.
```

Why 64 KB erase is currently the lead:

- static corruption starts exactly at the first 64 KB boundary;
- changing only the erase selector to the 4 KB sector path fixed static;
- the same payload, destination, chunks, F2 finalize, and display path worked afterward;
- 4 KB erase helper propagates poll failure, 64 KB helper does not.

Why page-program cannot be fully eliminated:

- `FUN_0000b6cc` also ignores its status-poll result;
- lower pages could still fail to program after an erase issue or after crossing a timing/protection boundary;
- F2 finalize does not verify storage.

The most accurate statement is therefore:

```text
the AP 64 KB upload path is not storage-integrity-safe because both 64 KB erase and page program can silently fail.
```

## Repair Class A: Avoid 64 KB Path

This is already proven for static image:

```text
if nType == 1
and destination == 0x01300000
and F1[0x11] == 0x02
then change F1[0x11] to 0x01
```

Effect:

```text
AP uses 4 KB sector erase instead of 64 KB block erase.
```

Status:

```text
proven working for static
not directly safe for GIF
```

GIF problem:

```text
F1[0x11] == 0x02 also sets DAT_0000d168 = erase_count * 3000 + 3,
which participates in GIF upload/finalization timing.
```

So GIF needs either:

- 4 KB erase while preserving the long-upload timing state; or
- a smaller/natural GIF payload path that does not need the 64 KB branch; or
- a separate finalization-state repair.

## Repair Class B: Preserve GIF Timing, Replace Erase Internally

Ideal behavior:

```text
host sends F1[0x11] == 0x02 so GIF timing/finalization is set correctly;
AP internally erases with 4 KB sectors instead of 64 KB blocks.
```

This would be the cleanest conceptual GIF repair.

Problem:

```text
With host-only DLL patching, F1[0x11] controls both timing and erase mode inside AP.
```

A pure host patch cannot currently split those effects unless another command/field can set `DAT_0000d168` independently. Current xref search found only the `F1[0x11] == 0x02` parser branch writing that threshold.

Status:

```text
best theoretical direction, but not yet implementable as a low-risk host-only patch
```

## Repair Class C: Make 64 KB Path Honest

AP firmware-level repair:

```c
FUN_0000b4d0() should return FUN_0000ba44() result;
FUN_0000b6cc() should return FUN_0000ba44() result;
the upload loop should stop/fail instead of advancing offsets after failure.
```

This would not necessarily fix the write, but it would expose the real failure instead of showing false success.

Safer AP firmware-level repair:

```text
increase 64 KB erase timeout;
verify WEL after WREN;
verify protection/error status bits;
retry failed erase/program;
read back/CRC verify media slot before finalizing.
```

Risk:

```text
high; requires AP firmware patching or vendor fix
```

This is suitable for a Gigabyte technical report, not for a casual local patch.

## Repair Class D: Find A Hidden Media Read/Erase Command

If AP exposes a hidden command that can:

```text
read arbitrary flash bytes;
erase media sectors;
verify media CRC;
force sector erase for media slot;
```

then we could design a safer software-only diagnostic or repair.

Current status:

```text
not found in known protocol commands
```

Next offline target:

```text
decompile the entire AP parser command table around all opcodes, not only known GCC commands,
and specifically look for host-controlled address/length fields feeding FUN_0000b54c/FUN_0000b760/FUN_0000b910.
```

## Recommended Next Step

Do not run another live GIF repair test yet.

The next best offline step is:

```text
build a full AP command opcode catalog from FUN_00007db8 and mark every command that:
1. returns data to host,
2. accepts address/length-like fields,
3. calls or influences flash read/erase/program helpers,
4. touches DAT_0000d168 / GIF finalization state.
```

This directly targets the missing piece:

```text
is there a software-only way to read/verify or decouple GIF timing from 64 KB erase?
```

## Current Decision

The 64 KB failure is now well-supported but not physically proven by readback.

The static repair is proven and low risk because it avoids the bad AP path.

The general/GIF repair is not ready for live testing because:

```text
the same F1 byte controls both 64 KB erase selection and GIF timing/finalization,
and no independent timing setter or media readback command has been found yet.
```



