#!/usr/bin/env python3
"""
Offline verifier for AORUS LCD firmware updater/AP delivery patch candidates.

Reads files only. Does not execute updater binaries, does not call DLL exports,
does not touch live Program Files paths, and does not write patched binaries.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
FW_DIR = Path(r"<local-firmware-package-dir>")
OUT = ROOT / "firmware_line_patch_candidates_20260601.json"


FILES = {
    "f12_exe": FW_DIR / "Gv_lcd_fw_update_N50_ICE_F1.2.exe",
    "f13_exe": FW_DIR / "GV-N5080AORUSM_ICE-16GD_LCD_F1.3.exe",
    "f14_exe": FW_DIR / "GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe",
    "loose_ap": FW_DIR / "AP",
    "loose_ap1": FW_DIR / "AP1",
    "gvlcdfwupdate": FW_DIR / "GvLcdFwUpdate.dll",
}


ERASE_END_PATTERN = bytes.fromhex("C7 44 24 2C FF EF 00 00")
ERASE_END_PATCH_FFFF = bytes.fromhex("C7 44 24 2C FF FF 00 00")

ERASE_CMD_PATTERN = bytes.fromhex(
    "B8 03 82 00 00"
    "66 89 44 24 20"
    "66 C7 44 24 24 0C 00"
    "C7 44 24 28 00 10 00 00"
    "C7 44 24 2C FF EF 00 00"
)

FLASH_DYNAMIC_END_PATTERN = bytes.fromhex(
    "C7 44 24 38 00 10 00 00"
    "48 85 DB"
    "0F 84 0F 02 00 00"
    "B9 04 82 00 00"
    "66 C7 44 24 34 0C 00"
    "05 FF 0F 00 00"
)

AP_OPTION1_OFFSET = 0xA534
AP_OPTION1_ORIGINAL = bytes.fromhex("01 20")
AP_OPTION1_PATCH = bytes.fromhex("00 BF")

AP_OPTION3_OFFSET = 0xA4D0
AP_OPTION3_ORIGINAL_PREFIX = bytes.fromhex("2D E9 F0 41 04 46")
AP_OPTION3_PATCH_PREFIX = bytes.fromhex("30 B5 04 46 10 25")


KNOWN_AP_SIZES = {
    "f12_exe": 56404,
    "f13_exe": 56720,
    "f14_exe": 58328,
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def all_offsets(data: bytes, needle: bytes) -> list[int]:
    out = []
    pos = data.find(needle)
    while pos >= 0:
        out.append(pos)
        pos = data.find(needle, pos + 1)
    return out


def find_ap_candidates(data: bytes, size: int) -> list[dict]:
    candidates = []
    # ARM vector table shape seen in packages: initial SP starts around 0x20003bxx,
    # reset vector is Thumb and in low AP range. This is intentionally conservative.
    for i in range(0, max(0, len(data) - size + 1)):
        if data[i + 3] != 0x20:
            continue
        if data[i + 7] != 0x00:
            continue
        reset = int.from_bytes(data[i + 4:i + 8], "little")
        if (reset & 1) != 1:
            continue
        if not (0x1000 <= (reset & ~1) <= 0xF000):
            continue
        blob = data[i:i + size]
        if len(blob) == size:
            candidates.append({
                "offset": i,
                "offset_hex": f"0x{i:X}",
                "sha256": sha256(blob),
                "first16": blob[:16].hex(" ").upper(),
                "option1_bytes": blob[AP_OPTION1_OFFSET:AP_OPTION1_OFFSET + 2].hex(" ").upper()
                    if len(blob) >= AP_OPTION1_OFFSET + 2 else None,
                "option3_bytes": blob[AP_OPTION3_OFFSET:AP_OPTION3_OFFSET + 2].hex(" ").upper()
                    if len(blob) >= AP_OPTION3_OFFSET + 2 else None,
            })
    return candidates


def file_info(path: Path) -> dict:
    data = path.read_bytes()
    return {
        "path": str(path),
        "exists": True,
        "size": len(data),
        "sha256": sha256(data),
    }


def analyze() -> dict:
    result: dict = {"files": {}, "native_dll": {}, "packages": {}, "ap_patch_candidates": {}}

    for key, path in FILES.items():
        if path.exists():
            result["files"][key] = file_info(path)
        else:
            result["files"][key] = {"path": str(path), "exists": False}

    dll_path = FILES["gvlcdfwupdate"]
    if dll_path.exists():
        data = dll_path.read_bytes()
        result["native_dll"] = {
            "erase_end_pattern_offsets": [f"0x{x:X}" for x in all_offsets(data, ERASE_END_PATTERN)],
            "erase_cmd_pattern_offsets": [f"0x{x:X}" for x in all_offsets(data, ERASE_CMD_PATTERN)],
            "flash_dynamic_end_pattern_offsets": [f"0x{x:X}" for x in all_offsets(data, FLASH_DYNAMIC_END_PATTERN)],
            "erase_end_patch_candidate": {
                "meaning": "Change fixed erase end 0xEFFF to 0xFFFF in GvLcdFwUpdate.dll only.",
                "old_bytes": ERASE_END_PATTERN.hex(" ").upper(),
                "new_bytes": ERASE_END_PATCH_FFFF.hex(" ").upper(),
                "expected_unique": True,
            },
        }

    for key in ("f12_exe", "f13_exe", "f14_exe"):
        path = FILES[key]
        if not path.exists():
            continue
        data = path.read_bytes()
        ap_size = KNOWN_AP_SIZES[key]
        candidates = find_ap_candidates(data, ap_size)
        result["packages"][key] = {
            "path": str(path),
            "ap_size": ap_size,
            "flash_start_hex": "0x1000",
            "flash_end_hex": f"0x{0x1000 + ap_size - 1:X}",
            "fixed_erase_end_hex": "0xEFFF",
            "overflow_past_fixed_erase": max(0, (0x1000 + ap_size - 1) - 0xEFFF),
            "ap_candidates": candidates,
        }

    for key in ("loose_ap", "loose_ap1"):
        path = FILES[key]
        if not path.exists():
            continue
        data = path.read_bytes()
        result["ap_patch_candidates"][key] = {
            "option1": {
                "offset_hex": f"0x{AP_OPTION1_OFFSET:X}",
                "current": data[AP_OPTION1_OFFSET:AP_OPTION1_OFFSET + 2].hex(" ").upper(),
                "expected_original": AP_OPTION1_ORIGINAL.hex(" ").upper(),
                "patch": AP_OPTION1_PATCH.hex(" ").upper(),
                "matches_expected": data[AP_OPTION1_OFFSET:AP_OPTION1_OFFSET + 2] == AP_OPTION1_ORIGINAL,
                "meaning": "Diagnostic: do not force success after 64KB erase poll.",
            },
            "option3": {
                "offset_hex": f"0x{AP_OPTION3_OFFSET:X}",
                "current_prefix": data[AP_OPTION3_OFFSET:AP_OPTION3_OFFSET + 6].hex(" ").upper(),
                "expected_prefix": AP_OPTION3_ORIGINAL_PREFIX.hex(" ").upper(),
                "patch_prefix": AP_OPTION3_PATCH_PREFIX.hex(" ").upper(),
                "matches_expected_prefix": data[
                    AP_OPTION3_OFFSET:AP_OPTION3_OFFSET + len(AP_OPTION3_ORIGINAL_PREFIX)
                ] == AP_OPTION3_ORIGINAL_PREFIX,
                "meaning": "Repair concept: replace 64KB erase helper body with 16x4KB sector erase loop.",
            },
        }

    return result


def main() -> None:
    OUT.write_text(json.dumps(analyze(), indent=2), encoding="utf-8")
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    main()


