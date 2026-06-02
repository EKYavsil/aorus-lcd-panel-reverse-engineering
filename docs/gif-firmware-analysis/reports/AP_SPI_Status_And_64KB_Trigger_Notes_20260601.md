# AP SPI Status And 64KB Trigger Notes - 2026-06-01

Scope: offline text analysis of the AP firmware Ghidra exports. No live device access.

## Key SPI Helper Calls

| Function | Role | Direct `FUN_0000B990` constants | Status-poll handling |
|---|---|---|---|
| `FUN_0000BAB4` | WREN helper | `6` Write Enable | sends WREN, no WEL verification found |
| `FUN_0000BA44` | status poll helper | `5` Read Status Register-1, `0` | sets timeout 300, polls WIP bit, returns 0/1 |
| `FUN_0000B4D0` | 64KB block erase helper | `0xd8` 64KB Block Erase, `param_1 >> 0x18`, `(param_1 << 8`, `(param_1 << 0x10`, `param_1 & 0xff` | calls `BA44`, then forces `r0=1` |
| `FUN_0000B910` | 4KB sector erase helper | `0x20` 4KB Sector Erase, `param_1 >> 0x18`, `(param_1 << 8`, `(param_1 << 0x10`, `param_1 & 0xff` | calls `BA44`, compares result, returns 0 on failure |
| `FUN_0000B6CC` | page program helper | `2` Page Program, `param_2 >> 0x18`, `(param_2 << 8`, `(param_2 << 0x10`, `param_2 & 0xff`, `*param_1` | calls `BA44`, then forces `r0=1` |

## Read/Write Path Clarification

`FUN_0000B54C` is the normal flash read helper. It sends opcode `0x03` plus a four-byte address through `FUN_0000B990`, then reads bytes with dummy `0` transfers.

`FUN_00001D98` reads address `0x01400000` and checks for a stored magic pattern involving `0x9F`, `0x0140`, and `0xAA`. This is not currently evidence of SPI JEDEC-ID opcode `0x9F`; it is a flash-content magic check through the normal read helper.

The AP upload init path sends `0xB7` through `FUN_0000B990`, which strongly supports that the AP enters 4-byte address mode before using the high `0x01300000`/GIF storage regions.

## Protection / Status Register Search

A plain text scan was checked for common SPI status/protection opcodes. The important distinction is whether the value is actually passed to `FUN_0000B990`/SPI transfer, not merely present as a host-protocol constant or bit shift.

| Candidate | Meaning | Current offline assessment |
|---|---|---|
| `0X35` | status-register-2 read candidate | no direct SPI transfer evidence found in current exports |
| `0X15` | status/config register read candidate | no direct SPI transfer evidence found in current exports |
| `0X01` | status-register write candidate | no direct SPI transfer evidence found in current exports |
| `0X31` | status-register-2 write candidate | no direct SPI transfer evidence found in current exports |
| `0X11` | status/config write candidate | no direct SPI transfer evidence found in current exports |
| `0X50` | volatile status-register write-enable candidate | seen as parser/catalog immediate, not confirmed as SPI transfer |
| `0X9F` | JEDEC-ID read candidate | seen in flash-content magic check, not as direct SPI opcode |

## Trigger Ranking After This Pass

1. Most likely: `0xD8` 64KB erase needs longer or fails to finish, `BA44` can return 0, and `B4D0` hides that failure.
2. Also likely: page program then fails on an unerased/busy block, and `B6CC` hides that failure too.
3. Plausible but not yet proven: WREN/WEL is not latched before one of the erase/program commands, because `BAB4` never reads status to verify WEL.
4. Less supported right now: status-register protection bits specific to 64KB erase. No clear direct SPI status-register read/write path beyond `0x05` has been proven in the current exports.
5. Weak: missing 4-byte address mode. The AP explicitly sends `0xB7` during upload initialization.

## Repair Implication

The cleanest direct repair line remains inside the AP firmware 64KB path:

- `B4D0` must not discard `BA44`'s result.
- If `BA44` timeout is too short for `0xD8`, 64KB erase needs a longer wait/poll budget than page program and 4KB sector erase.
- `B6CC` should also propagate page-program poll failure, otherwise an erase fix can still be masked by program failure.
- WREN/WEL verification is a second-stage hardening candidate, but it is more invasive than fixing timeout/return propagation.

## Next Offline Proof Step

We need to establish whether the `300` timeout is actually short for the AP's timer tick. That requires following the decrement source for the timeout variable used by `BA44` and estimating real time. If 300 ticks is below common 64KB block erase worst-case timing, the root cause becomes: short timeout plus ignored failure.


