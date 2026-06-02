#!/usr/bin/env python3
"""
Create offline-only staged firmware patch candidates for analysis.

This script writes only under the investigation staging directory.
It does not execute firmware updaters, does not load DLLs, does not call I2C,
and does not modify Downloads/Program Files/live files.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from datetime import datetime
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
FW_DIR = Path(r"<local-firmware-package-dir>")
STAGE = ROOT / "offline_firmware_patch_candidates_20260601"

AP = FW_DIR / "AP"
AP1 = FW_DIR / "AP1"
DLL = FW_DIR / "GvLcdFwUpdate.dll"

EXPECTED_AP_SHA = "DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C"
EXPECTED_DLL_SHA = "DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70"

AP_OPTION3_OFFSET = 0xA4D0
AP_OPTION3_ORIGINAL_PREFIX = bytes.fromhex("2D E9 F0 41 04 46")
AP_OPTION3_16X4K_PATCH = bytes.fromhex(
    "30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1 04 F5"
    "80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD"
)

DLL_ERASE_END_OFFSET = 0x2A1C
DLL_ERASE_END_ORIGINAL = bytes.fromhex("C7 44 24 2C FF EF 00 00")
DLL_ERASE_END_F3D7 = bytes.fromhex("C7 44 24 2C D7 F3 00 00")
DLL_ERASE_END_FFFF = bytes.fromhex("C7 44 24 2C FF FF 00 00")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def read_checked(path: Path, expected_sha: str) -> bytes:
    data = path.read_bytes()
    actual = sha256(data)
    if actual != expected_sha:
        raise RuntimeError(f"SHA mismatch for {path}: expected {expected_sha}, got {actual}")
    return data


def patch_bytes(data: bytes, offset: int, expected: bytes, replacement: bytes) -> bytes:
    current = data[offset:offset + len(expected)]
    if current != expected:
        raise RuntimeError(
            f"Patch precondition failed at 0x{offset:X}: "
            f"expected {expected.hex(' ').upper()}, got {current.hex(' ').upper()}"
        )
    out = bytearray(data)
    out[offset:offset + len(replacement)] = replacement
    return bytes(out)


def write_file(path: Path, data: bytes) -> dict:
    path.write_bytes(data)
    return {
        "path": str(path),
        "size": len(data),
        "sha256": sha256(data),
    }


def main() -> None:
    STAGE.mkdir(parents=True, exist_ok=True)

    ap_data = read_checked(AP, EXPECTED_AP_SHA)
    ap1_data = read_checked(AP1, EXPECTED_AP_SHA)
    dll_data = read_checked(DLL, EXPECTED_DLL_SHA)

    ap_patched = patch_bytes(
        ap_data,
        AP_OPTION3_OFFSET,
        AP_OPTION3_ORIGINAL_PREFIX,
        AP_OPTION3_16X4K_PATCH,
    )
    ap1_patched = patch_bytes(
        ap1_data,
        AP_OPTION3_OFFSET,
        AP_OPTION3_ORIGINAL_PREFIX,
        AP_OPTION3_16X4K_PATCH,
    )
    dll_f3d7 = patch_bytes(
        dll_data,
        DLL_ERASE_END_OFFSET,
        DLL_ERASE_END_ORIGINAL,
        DLL_ERASE_END_F3D7,
    )
    dll_ffff = patch_bytes(
        dll_data,
        DLL_ERASE_END_OFFSET,
        DLL_ERASE_END_ORIGINAL,
        DLL_ERASE_END_FFFF,
    )

    manifest = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "scope": "offline staging only; no execution; no live files modified",
        "inputs": {
            "AP": {"path": str(AP), "sha256": sha256(ap_data), "size": len(ap_data)},
            "AP1": {"path": str(AP1), "sha256": sha256(ap1_data), "size": len(ap1_data)},
            "GvLcdFwUpdate.dll": {"path": str(DLL), "sha256": sha256(dll_data), "size": len(dll_data)},
        },
        "patches": {
            "AP_option3_16x4k": {
                "offset_hex": f"0x{AP_OPTION3_OFFSET:X}",
                "old_prefix": AP_OPTION3_ORIGINAL_PREFIX.hex(" ").upper(),
                "new_bytes": AP_OPTION3_16X4K_PATCH.hex(" ").upper(),
                "meaning": "Replace AP FUN_0000B4D0 64KB erase helper entry with 16x FUN_0000B910 sector erase loop.",
            },
            "DLL_erase_end_exact": {
                "offset_hex": f"0x{DLL_ERASE_END_OFFSET:X}",
                "old_bytes": DLL_ERASE_END_ORIGINAL.hex(" ").upper(),
                "new_bytes": DLL_ERASE_END_F3D7.hex(" ").upper(),
                "meaning": "Change I2CIAPChangeToErase12ByteMode fixed end from 0xEFFF to exact AP F1.4 end 0xF3D7.",
            },
            "DLL_erase_end_sector": {
                "offset_hex": f"0x{DLL_ERASE_END_OFFSET:X}",
                "old_bytes": DLL_ERASE_END_ORIGINAL.hex(" ").upper(),
                "new_bytes": DLL_ERASE_END_FFFF.hex(" ").upper(),
                "meaning": "Change I2CIAPChangeToErase12ByteMode fixed end from 0xEFFF to sector end 0xFFFF.",
            },
        },
        "outputs": {},
        "notes": [
            "Patched DLL outputs will have invalid Authenticode signatures.",
            "Patched AP outputs are raw firmware payload candidates only, not flashable by themselves.",
            "The original updater EXE overwrites loose AP/AP1 resources, so these staged AP files are not consumed by the original EXE unless a controlled harness or modified package is used.",
        ],
    }

    manifest["outputs"]["AP_option3_16x4k"] = write_file(STAGE / "AP_option3_16x4k.bin", ap_patched)
    manifest["outputs"]["AP1_option3_16x4k"] = write_file(STAGE / "AP1_option3_16x4k.bin", ap1_patched)
    manifest["outputs"]["GvLcdFwUpdate_erase_end_F3D7.dll"] = write_file(
        STAGE / "GvLcdFwUpdate_erase_end_F3D7.dll", dll_f3d7
    )
    manifest["outputs"]["GvLcdFwUpdate_erase_end_FFFF.dll"] = write_file(
        STAGE / "GvLcdFwUpdate_erase_end_FFFF.dll", dll_ffff
    )

    # Include untouched dependencies for completeness of a future dry-run bundle,
    # but these are simple copies and not live-deployable artifacts.
    for dep in ("GvDisplay.dll", "GvDisplayA.dll", "GvIntelI2C.dll", "config.db"):
        src = FW_DIR / dep
        if src.exists():
            dst = STAGE / dep
            shutil.copy2(src, dst)
            manifest["outputs"][dep] = {
                "path": str(dst),
                "size": dst.stat().st_size,
                "sha256": sha256(dst.read_bytes()),
                "copied_unmodified": True,
            }

    (STAGE / "staging_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Wrote {STAGE}")
    print(f"Wrote {STAGE / 'staging_manifest.json'}")


if __name__ == "__main__":
    main()


