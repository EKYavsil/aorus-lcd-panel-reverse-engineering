from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
ORIG_AP = Path(r"<local-firmware-package-dir>\AP")
PATCHED_AP = ROOT / "offline_firmware_patch_candidates_20260601" / "AP_option3_16x4k.bin"
OUT = ROOT / "ap_patch_crc_header_analysis_20260601.json"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def crc16_native_style(data: bytes) -> int:
    # Mirrors the bit-serial loop seen in GvLcdFwUpdate.dll RVA 0x3E90.
    crc = 0
    poly = 0x1021
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ poly) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc & 0xFFFF


def diff_ranges(a: bytes, b: bytes):
    ranges = []
    i = 0
    while i < min(len(a), len(b)):
        if a[i] == b[i]:
            i += 1
            continue
        start = i
        while i < min(len(a), len(b)) and a[i] != b[i]:
            i += 1
        ranges.append({"start": start, "end_inclusive": i - 1, "length": i - start})
    if len(a) != len(b):
        ranges.append({"start": min(len(a), len(b)), "end_inclusive": max(len(a), len(b)) - 1, "length": abs(len(a) - len(b)), "size_delta": len(b) - len(a)})
    return ranges


def hex_at(data: bytes, offset: int, length: int) -> str:
    return " ".join(f"{x:02X}" for x in data[offset : offset + length])


orig = ORIG_AP.read_bytes()
patched = PATCHED_AP.read_bytes()

result = {
    "scope": "offline AP header/diff/native-CRC analysis only",
    "original": {
        "path": str(ORIG_AP),
        "size": len(orig),
        "sha256": sha256(orig),
        "header_0x00_0x7f": hex_at(orig, 0, 0x80),
        "crc16_over_0x28_to_end": f"0x{crc16_native_style(orig[0x28:]):04X}",
    },
    "patched": {
        "path": str(PATCHED_AP),
        "size": len(patched),
        "sha256": sha256(patched),
        "header_0x00_0x7f": hex_at(patched, 0, 0x80),
        "crc16_over_0x28_to_end": f"0x{crc16_native_style(patched[0x28:]):04X}",
    },
    "header_equal_first_0x28": orig[:0x28] == patched[:0x28],
    "header_equal_first_0x80": orig[:0x80] == patched[:0x80],
    "diff_ranges": diff_ranges(orig, patched),
    "patch_window_original": hex_at(orig, 0xA4D0, 48),
    "patch_window_patched": hex_at(patched, 0xA4D0, 48),
    "native_crc_observation": {
        "submit_crc_export": "I2CIAPSubmitCRCAP wraps RVA 0x3E90",
        "observed_behavior": "RVA 0x3E90 reads AP buffer pointer/size populated by SetAPFlashTable, skips first 0x28 bytes, computes bit-serial CRC16 poly 0x1021, and sends command 0x8104.",
        "implication": "A patched AP payload should receive a freshly computed CRC at update time if delivered through the same native DLL code path; no static header CRC update was identified in the first 0x28 bytes.",
    },
}

OUT.write_text(json.dumps(result, indent=2), encoding="utf-8")
print(str(OUT))


