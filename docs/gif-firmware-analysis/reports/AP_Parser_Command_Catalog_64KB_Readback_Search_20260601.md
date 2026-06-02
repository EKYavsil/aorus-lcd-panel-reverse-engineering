# AP Parser Command Catalog and 64 KB Readback Search

Date: 2026-06-01

Scope: offline AP firmware analysis only. No live DLL deploy, service stop/start, firmware updater execution, raw I2C, panel command, or upload test was used for this report.

## Inputs

Ghidra project:

```text
<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ghidra_ap_project\AP_F14_pkg.gpr
```

Generated script:

```text
<local-gif-investigation-dir>\scratch\APFirmwareParserCommandCatalog.java
```

Generated raw output:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_parser_command_catalog_AP_F14_pkg_deep.txt
```

Analyzed functions:

| Function | Address | Role |
|---|---:|---|
| `FUN_00007db8` | `0x00007DB8` | AP host command parser |
| `FUN_0000CB54` | `0x0000CB54` | AP main loop / upload erase+program worker |
| `FUN_0000B4D0` | `0x0000B4D0` | 64 KB block erase helper |
| `FUN_0000B910` | `0x0000B910` | 4 KB sector erase helper |
| `FUN_0000B6CC` | `0x0000B6CC` | 256-byte page program helper |
| `FUN_0000B54C` | `0x0000B54C` | normal SPI flash read helper |
| `FUN_0000B760` | `0x0000B760` | fast/DMA-like SPI flash read helper |

## Parser Command Table

This table classifies the commands visible in `FUN_00007DB8`. Names are inferred from existing host/API behavior and AP decompile shape.

| Opcode | Parser behavior | Media flash readback? | Notes |
|---:|---|---|---|
| `0x66` | Magic handshake/reset-style branch with `CB 55 AC 38 AB CD EF` | No | Resets several state/config fields. |
| `0x11` | Magic handshake branch with `CB 55 AC 38 AB CD EF` | No | Clears receive state only. |
| `0x40` | RGB/config setup branch | No | Uses bytes 5-7 as color-ish values; not media storage. |
| `0x77` | Sets multiple display/config defaults | No | Not an upload/read command. |
| `0xAA` | Save/state-dirty marker | No | Sets a state flag; matches known Save command family. |
| `0xD6` | Returns firmware/version-like response | No | Response begins with `D6 14 01 02 03 04 05 50`. |
| `0xD7` | Returns upload/state phase byte | No | Response byte 1 is `*DAT_00008240`. |
| `0xD8` | Returns panel state bytes | No | Response bytes include `*DAT_000081C4`, `*DAT_00008260`, `*DAT_00008264`. |
| `0xDE` | Returns mode/display state | No | Response bytes include current mode and another state byte. |
| `0xDF` | Returns overlay/timer flags | No | Packs several boolean overlay flags into one byte. |
| `0xE1` | Metric/overlay display config write | No | Matches public clean apply `SetDisplay` behavior. |
| `0xE3` | Parameterized display/effect write | No | Writes local state fields; can call `FUN_000038AC`. |
| `0xE5` | Set mode write | No | Updates current mode and related mode flags. |
| `0xE6` | Larger color/effect/template write | No | Parses palette-like fields and copies colors. |
| `0xE7` | Open/enable LCD write | No | Stores byte 5 to an enable-like state field. |
| `0xE8` | Single-byte config write | No | Stores byte 5 to state. |
| `0xE9` | Receive-state clear | No | Sets parser receive state byte to 0. |
| `0xEA` | Template/state write | No | Writes per-slot template fields for ids 1, 2, 3. |
| `0xEB` | Template/state read | No | Returns 4 bytes from per-slot state fields selected by byte 5. |
| `0xEC` | Template dimension/state read | No | Returns two 16-bit values selected by byte 5. |
| `0xED` | Template dimension/state read | No | Returns two 16-bit values selected by byte 5. |
| `0xF1` | Upload header | No readback; yes storage write setup | Parses destination, storage type, chunk count, erase selector, timing/finalize fields. |
| `0xF2` | Upload start/finish and display transition | No | `byte5==1` starts/locks state; `byte5==2` finishes/applies upload state. |
| `0xF3` | Loop/carousel list write | No | Stores loop list bytes. |
| `0xF4` | Loop/carousel list read | No media readback | Returns stored loop list slices selected by byte 5. |
| `0xF9` | Sets mode/state flag | No | Sets one state byte. |
| `0xFA` | Magic command with `CB 55 AC 38` | No | Sets two state fields. |
| `0xFB` | Clears three state fields | No | No flash/media behavior visible. |

## F1 Upload Header Findings

The AP parser directly confirms the host/AP contract for custom media upload.

```c
uVar24 = F1[5..8];       // destination/address
bVar30 = F1[0x11];       // erase selector
uVar31 = F1[10..13];     // chunk/data count-like value

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

Destination mapping:

| F1 destination | AP slot/state |
|---:|---|
| `0x00000000` | slot/state `1`; if AP side current state is custom animation, base becomes `0x01000000` |
| `0x01320000` | slot/state `2` |
| `0x01300000` | slot/state `3` |

Critical GIF-specific side effect:

```c
if (F1[0x11] == 2) {
    DAT_00008248 = erase_count * 3000 + 3;
}
```

This means `F1[0x11] == 0x02` is not only "64 KB erase". It also sets an AP-side timing/finalize threshold. This explains why directly changing GIF from `0x02` to `0x01` is not equivalent to the static fix: GIF uses a larger payload, destination `0`, storage type `2`, display mode `5`, and a timing path tied to the original erase selector.

## Main Loop Upload Worker

The AP main loop performs erase first, then program.

Erase phase:

```c
if (*DAT_0000D160 == 1) {
    if (*DAT_0000D16C != 0) {
        *DAT_0000D16C -= 1;

        if (*DAT_0000D178 == 2) {
            ok = FUN_0000B4D0(base + offset);   // 64 KB block erase
            if (ok == 0) return;
            offset += 0x10000;
        }
        else {
            ok = FUN_0000B910(base + offset);   // 4 KB sector erase
            if (ok == 0) return;
            offset += 0x1000;
        }
    }
    else {
        *DAT_0000D160 = 0;
        offset = 0;
    }
}
```

Program phase:

```c
if (*DAT_0000D160 == 2) {
    state+0x2E = 1;
    ok = FUN_0000B6CC(staging, base + offset, 0x100);
    if (ok == 0) return;
    offset += 0x100;
    remaining_pages -= 1;
    if (remaining_pages == 0) {
        upload_pending = 0;
    }
    state+0x2E = 0;
}
```

The high-level loop appears to check helper return values. The key bug is lower: the 64 KB erase helper returns success even when its poll result fails.

## 64 KB Path Root Cause Status

Previously confirmed helper asymmetry:

| Helper | SPI opcode | Polls WIP? | Propagates poll failure? |
|---|---:|---|---|
| `FUN_0000B910` | `0x20` 4 KB sector erase | Yes | Yes |
| `FUN_0000B4D0` | `0xD8` 64 KB block erase | Yes | No |
| `FUN_0000B6CC` | `0x02` page program | Yes | No |

The parser/main-loop catalog reinforces this model:

1. `F1[0x11] == 0x02` selects 64 KB erase and the AP loop calls `FUN_0000B4D0`.
2. `FUN_0000B4D0` can internally observe a failed/timeout poll but still reports success to the main loop.
3. The main loop then advances `offset += 0x10000`.
4. For static image, the second 64 KB region starts at `0x01310000`, exactly where the lower ~40% begins.
5. For GIF, the same hidden failure can corrupt a structured multi-frame payload instead of producing a simple lower-black split.

## Readback Search Result

No confirmed host-exposed arbitrary custom media flash readback command was found in `FUN_00007DB8`.

Important negative evidence:

- Parser-visible read commands `D6/D7/D8/DE/DF/EB/EC/ED/F4` build small state/config responses via `FUN_00009330`.
- These responses read AP RAM/config fields, not caller-supplied flash addresses.
- The SPI flash read helpers `FUN_0000B54C` and `FUN_0000B760` are referenced by internal display/config functions, not directly by the host command parser with an address/length supplied by the command payload.
- No parser branch currently shows a pattern like "read address from command bytes, call flash read helper, return buffer".

Therefore the desired proof:

```text
read back flash bytes at/after 0x01310000 and compare them with uploaded payload
```

is not yet available through a known safe host command.

## What This Means

Most likely current explanation:

```text
The 64 KB AP erase path is broken or unreliable, and the firmware hides that failure from the host.
```

Static image was fixed by avoiding that path:

```text
F1[0x11] 0x02 -> 0x01
```

That selects 4 KB sector erase, whose AP helper propagates failures and, in practice, successfully erased/wrote the full static slot.

GIF is harder because:

1. GIF original upload relies on `F1[0x11] == 0x02` for 64 KB erase.
2. That same byte also sets a timing/finalization threshold.
3. GIF target/destination semantics differ from static (`destination=0`, AP internal custom animation base likely `0x01000000`).
4. GIF payload corruption has higher blast radius because frame headers/offsets/RLE streams depend on contiguous valid storage.

## Current Decision

Status:

```text
D) More disassembly is required for a complete repair-grade GIF fix.
```

But for the 64 KB path itself, the working diagnosis is already strong:

```text
The AP 64 KB erase helper is defective because it ignores its own status-poll result.
```

## Recommended Next Offline Steps

1. Diff `FUN_0000B4D0` against `FUN_0000B910` at instruction level and determine whether a minimal AP firmware binary patch could make 64 KB erase propagate poll failure. This is for diagnosis only, not live flashing.
2. Disassemble the AP upload finalization/timing gate around `DAT_0000D168` to understand whether GIF can safely use 4 KB erase while preserving the old timing threshold.
3. Search host DLL callsites for any use of `D7/D8/EB/EC/ED/F4` after upload. These can expose state transitions even if they cannot read media bytes.
4. Continue looking for a hidden readback path outside `FUN_00007DB8`, but treat it as unconfirmed until a parser branch shows caller-controlled address/length plus `FUN_0000B54C` or `FUN_0000B760`.

No live experiment should be started from this report alone.


