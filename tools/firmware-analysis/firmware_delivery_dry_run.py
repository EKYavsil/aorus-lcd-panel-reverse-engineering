from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def crc16_native_style(data: bytes) -> int:
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


def read_config(config_db: Path) -> dict:
    con = sqlite3.connect(str(config_db))
    try:
        row = con.execute("select info from config limit 1").fetchone()
        if not row:
            raise RuntimeError("config table is empty")
        text = row[0]
    finally:
        con.close()
    # Vendor JSON contains a trailing comma before closing braces. Tolerate it.
    text = text.replace(",\n  }\n}", "\n  }\n}")
    return json.loads(text)["Info"]


def file_info(path: Path) -> dict:
    data = path.read_bytes()
    return {
        "path": str(path),
        "exists": path.exists(),
        "size": len(data),
        "sha256": sha256_file(path),
        "crc16_over_0x28_to_end": f"0x{crc16_native_style(data[0x28:]):04X}" if len(data) >= 0x28 else None,
        "header_0x00_0x28_sha256": hashlib.sha256(data[:0x28]).hexdigest().upper() if len(data) >= 0x28 else None,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Offline-only firmware delivery dry run. Does not load DLLs or call I2C.")
    parser.add_argument("--stage", default=r"<local-gif-investigation-dir>\offline_firmware_patch_candidates_20260601")
    parser.add_argument("--out", default=r"<local-gif-investigation-dir>\firmware_delivery_dry_run_20260601.json")
    args = parser.parse_args()

    stage = Path(args.stage)
    out = Path(args.out)
    config = read_config(stage / "config.db")

    fw_file = config["FW_File"]
    fw_file1 = config["FW_File1"]
    expected_size = int(config["FW_FileSize"])
    expected_size1 = int(config["FW_FileSize1"])
    iap_address = int(config["IAP_ADDRESS"])
    iap_address1 = int(config["IAP_ADDRESS1"])
    ap_address = int(config["AP_ADDRESS"])

    candidates = {
        "AP_primary_name_from_config": fw_file,
        "AP_fallback_name_from_config": fw_file1,
        "staged_option3_AP": "AP_option3_16x4k.bin",
        "staged_option3_AP1": "AP1_option3_16x4k.bin",
    }

    files = {}
    for label, name in candidates.items():
        p = stage / name
        if p.exists():
            files[label] = file_info(p)
        else:
            files[label] = {"path": str(p), "exists": False}

    ap = stage / "AP_option3_16x4k.bin"
    ap1 = stage / "AP1_option3_16x4k.bin"
    dll_exact = stage / "GvLcdFwUpdate_erase_end_F3D7.dll"
    dll_sector = stage / "GvLcdFwUpdate_erase_end_FFFF.dll"

    checks = {
        "config_expected_primary_size_matches_patched_AP": ap.exists() and ap.stat().st_size == expected_size,
        "config_expected_fallback_size_matches_patched_AP1": ap1.exists() and ap1.stat().st_size == expected_size1,
        "patched_AP_and_AP1_hash_equal": ap.exists() and ap1.exists() and sha256_file(ap) == sha256_file(ap1),
        "dll_exact_exists": dll_exact.exists(),
        "dll_sector_exists": dll_sector.exists(),
        "no_live_action": True,
    }

    would_call_sequence = [
        {"step": "DisposeResource", "note": "Original EXE would extract embedded resources and can overwrite loose staged files."},
        {"step": "I2CInitial", "arg": int(config["IAP_CODE_SIZE"])},
        {"step": "I2CAPChangeToIAP", "arg": f"0x{ap_address:02X}"},
        {"step": "I2CIAPChangeToErase12ByteMode", "arg_primary": f"0x{iap_address:02X}", "arg_fallback": f"0x{iap_address1:02X}"},
        {"step": "If fallback succeeds", "effect": f"Copy {fw_file1} over {fw_file}; expected size becomes {expected_size1}"},
        {"step": "FileInfo length check", "path": str(stage / fw_file), "expected_size": expected_size},
        {"step": "I2CIAPSetAPFlashTable", "arg": expected_size, "note": "Native code reopens AP by name/path."},
        {"step": "I2CIAPSubmitCRCAP", "arg": "active IAP address", "crc_if_patched_AP_loaded": files.get("staged_option3_AP", {}).get("crc16_over_0x28_to_end")},
        {"step": "I2CIAPFlashAP12ByteMode", "arg": "active IAP address"},
        {"step": "I2CIAPChangeToAP", "arg": "active IAP address"},
    ]

    result = {
        "scope": "offline dry-run only; does not load native DLLs; does not execute updater; does not call I2C",
        "stage": str(stage),
        "config": config,
        "decoded_addresses": {
            "IAP_ADDRESS": f"0x{iap_address:02X}",
            "IAP_ADDRESS1": f"0x{iap_address1:02X}",
            "AP_ADDRESS": f"0x{ap_address:02X}",
        },
        "files": files,
        "checks": checks,
        "would_call_sequence": would_call_sequence,
        "verdict": "PASS_OFFLINE" if all(checks.values()) else "CHECK_FAILED",
    }

    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(out)
    print(result["verdict"])
    return 0 if result["verdict"] == "PASS_OFFLINE" else 1


if __name__ == "__main__":
    raise SystemExit(main())


