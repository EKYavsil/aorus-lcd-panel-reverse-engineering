from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
DUMP = ROOT / "ghidra_64kb_deep_outputs" / "ap_patch_site_dump_AP_F14_pkg_patchsites.txt"
READ = ROOT / "ghidra_64kb_deep_outputs" / "ap_read_caller_graph_AP_F14_pkg_deep.txt"
OUT = ROOT / "reports" / "AP_SPI_Status_And_64KB_Trigger_Notes_20260601.md"


SPI_NAMES = {
    0x02: "Page Program",
    0x03: "Read Data",
    0x05: "Read Status Register-1",
    0x06: "Write Enable",
    0x20: "4KB Sector Erase",
    0x35: "Read Status Register-2 / vendor-specific",
    0x50: "Volatile Status Register Write Enable / vendor-specific",
    0x9F: "JEDEC ID",
    0xB7: "Enter 4-byte Address Mode",
    0xD8: "64KB Block Erase",
}


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def function_blocks(text: str) -> dict[str, str]:
    blocks: dict[str, str] = {}
    matches = list(re.finditer(r"^## Function ([0-9a-fA-F]+) (FUN_[0-9a-fA-F]+)\s*$", text, re.M))
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        blocks[m.group(2).lower()] = text[start:end]
    return blocks


def b990_calls(block: str) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for m in re.finditer(r"FUN_0000b990\(([^)]+)\)", block):
        arg = m.group(1).strip()
        name = ""
        if re.fullmatch(r"0x[0-9a-fA-F]+|\d+", arg):
            value = int(arg, 0)
            name = SPI_NAMES.get(value, "")
        out.append((arg, name))
    return out


def bb8e_calls(block: str) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for m in re.finditer(r"FUN_0000bb8e\([^,]+,\s*([^)]+)\)", block):
        arg = m.group(1).strip()
        name = ""
        if re.fullmatch(r"0x[0-9a-fA-F]+|\d+", arg):
            value = int(arg, 0)
            name = SPI_NAMES.get(value, "")
        out.append((arg, name))
    return out


def emit():
    dump = read_text(DUMP)
    read = read_text(READ)
    blocks = function_blocks(dump)

    key_funcs = [
        ("FUN_0000BAB4", "WREN helper"),
        ("FUN_0000BA44", "status poll helper"),
        ("FUN_0000B4D0", "64KB block erase helper"),
        ("FUN_0000B910", "4KB sector erase helper"),
        ("FUN_0000B6CC", "page program helper"),
    ]

    lines: list[str] = []
    lines.append("# AP SPI Status And 64KB Trigger Notes - 2026-06-01")
    lines.append("")
    lines.append("Scope: offline text analysis of the AP firmware Ghidra exports. No live device access.")
    lines.append("")
    lines.append("## Key SPI Helper Calls")
    lines.append("")
    lines.append("| Function | Role | Direct `FUN_0000B990` constants | Status-poll handling |")
    lines.append("|---|---|---|---|")

    for fn, role in key_funcs:
        block = blocks.get(fn.lower(), "")
        calls = b990_calls(block)
        call_text = ", ".join(f"`{arg}` {name}".rstrip() for arg, name in calls) or "(none found)"
        if fn == "FUN_0000B4D0":
            handling = "calls `BA44`, then forces `r0=1`"
        elif fn == "FUN_0000B910":
            handling = "calls `BA44`, compares result, returns 0 on failure"
        elif fn == "FUN_0000B6CC":
            handling = "calls `BA44`, then forces `r0=1`"
        elif fn == "FUN_0000BA44":
            handling = "sets timeout 300, polls WIP bit, returns 0/1"
        elif fn == "FUN_0000BAB4":
            handling = "sends WREN, no WEL verification found"
        else:
            handling = ""
        lines.append(f"| `{fn}` | {role} | {call_text} | {handling} |")

    lines.append("")
    lines.append("## Read/Write Path Clarification")
    lines.append("")
    lines.append("`FUN_0000B54C` is the normal flash read helper. It sends opcode `0x03` plus a four-byte address through `FUN_0000B990`, then reads bytes with dummy `0` transfers.")
    lines.append("")
    lines.append("`FUN_00001D98` reads address `0x01400000` and checks for a stored magic pattern involving `0x9F`, `0x0140`, and `0xAA`. This is not currently evidence of SPI JEDEC-ID opcode `0x9F`; it is a flash-content magic check through the normal read helper.")
    lines.append("")
    lines.append("The AP upload init path sends `0xB7` through `FUN_0000B990`, which strongly supports that the AP enters 4-byte address mode before using the high `0x01300000`/GIF storage regions.")
    lines.append("")

    interesting = {
        "0x35": "status-register-2 read candidate",
        "0x15": "status/config register read candidate",
        "0x01": "status-register write candidate",
        "0x31": "status-register-2 write candidate",
        "0x11": "status/config write candidate",
        "0x50": "volatile status-register write-enable candidate",
        "0x9f": "JEDEC-ID read candidate",
    }
    lines.append("## Protection / Status Register Search")
    lines.append("")
    lines.append("A plain text scan was checked for common SPI status/protection opcodes. The important distinction is whether the value is actually passed to `FUN_0000B990`/SPI transfer, not merely present as a host-protocol constant or bit shift.")
    lines.append("")
    lines.append("| Candidate | Meaning | Current offline assessment |")
    lines.append("|---|---|---|")
    for token, meaning in interesting.items():
        pat = re.compile(rf"FUN_0000b990\({re.escape(token)}\)|FUN_0000b990\({int(token, 16)}\)", re.I)
        found_spi = bool(pat.search(dump) or pat.search(read))
        if found_spi:
            assessment = "direct SPI transfer candidate found"
        elif token == "0x9f":
            assessment = "seen in flash-content magic check, not as direct SPI opcode"
        elif token == "0x50":
            assessment = "seen as parser/catalog immediate, not confirmed as SPI transfer"
        else:
            assessment = "no direct SPI transfer evidence found in current exports"
        lines.append(f"| `{token.upper()}` | {meaning} | {assessment} |")

    lines.append("")
    lines.append("## Trigger Ranking After This Pass")
    lines.append("")
    lines.append("1. Most likely: `0xD8` 64KB erase needs longer or fails to finish, `BA44` can return 0, and `B4D0` hides that failure.")
    lines.append("2. Also likely: page program then fails on an unerased/busy block, and `B6CC` hides that failure too.")
    lines.append("3. Plausible but not yet proven: WREN/WEL is not latched before one of the erase/program commands, because `BAB4` never reads status to verify WEL.")
    lines.append("4. Less supported right now: status-register protection bits specific to 64KB erase. No clear direct SPI status-register read/write path beyond `0x05` has been proven in the current exports.")
    lines.append("5. Weak: missing 4-byte address mode. The AP explicitly sends `0xB7` during upload initialization.")
    lines.append("")
    lines.append("## Repair Implication")
    lines.append("")
    lines.append("The cleanest direct repair line remains inside the AP firmware 64KB path:")
    lines.append("")
    lines.append("- `B4D0` must not discard `BA44`'s result.")
    lines.append("- If `BA44` timeout is too short for `0xD8`, 64KB erase needs a longer wait/poll budget than page program and 4KB sector erase.")
    lines.append("- `B6CC` should also propagate page-program poll failure, otherwise an erase fix can still be masked by program failure.")
    lines.append("- WREN/WEL verification is a second-stage hardening candidate, but it is more invasive than fixing timeout/return propagation.")
    lines.append("")
    lines.append("## Next Offline Proof Step")
    lines.append("")
    lines.append("We need to establish whether the `300` timeout is actually short for the AP's timer tick. That requires following the decrement source for the timeout variable used by `BA44` and estimating real time. If 300 ticks is below common 64KB block erase worst-case timing, the root cause becomes: short timeout plus ignored failure.")
    lines.append("")

    OUT.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    emit()
    print(OUT)


