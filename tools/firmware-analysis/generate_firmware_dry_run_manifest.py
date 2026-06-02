#!/usr/bin/env python3
"""
Generate an offline dry-run manifest for the AORUS LCD AP firmware update path.

This script does not load DLLs, does not execute firmware updaters, does not call
GvWriteI2C/GvReadI2C, and does not modify any input files.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
FW_DIR = Path(r"<local-firmware-package-dir>")
OUT = ROOT / "firmware_dry_run_manifest_20260601.md"


FILES = {
    "AP": FW_DIR / "AP",
    "AP1": FW_DIR / "AP1",
    "config.db": FW_DIR / "config.db",
    "GvLcdFwUpdate.dll": FW_DIR / "GvLcdFwUpdate.dll",
    "GvDisplay.dll": FW_DIR / "GvDisplay.dll",
    "GvDisplayA.dll": FW_DIR / "GvDisplayA.dll",
    "GvIntelI2C.dll": FW_DIR / "GvIntelI2C.dll",
    "F1.4 updater": FW_DIR / "GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def crc16_ccitt(data: bytes, init: int = 0) -> int:
    crc = init & 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc & 0xFFFF


def main() -> None:
    ap = FILES["AP"].read_bytes()
    ap_size = len(ap)
    ap_crc_region = ap[0x28:]
    ap_crc = crc16_ccitt(ap_crc_region)

    fixed_erase_start = 0x1000
    fixed_erase_end = 0xEFFF
    recommended_erase_end = 0xFFFF
    flash_start = 0x1000
    flash_end = 0x1000 + ap_size - 1
    overflow = max(0, flash_end - fixed_erase_end)

    lines: list[str] = []
    lines.append("# Firmware Dry-Run Manifest")
    lines.append("")
    lines.append("Date: 2026-06-01")
    lines.append("")
    lines.append("Scope: offline manifest only. No DLL loading, no updater execution, no I2C, no firmware flash, no file modification.")
    lines.append("")
    lines.append("## Inputs")
    lines.append("")
    lines.append("| Name | Path | Size | SHA256 |")
    lines.append("|---|---|---:|---|")
    for name, path in FILES.items():
        if path.exists():
            lines.append(f"| `{name}` | `{path}` | {path.stat().st_size} | `{sha256(path)}` |")
        else:
            lines.append(f"| `{name}` | `{path}` | missing | missing |")
    lines.append("")

    lines.append("## Decoded Config")
    lines.append("")
    lines.append("| Field | Value | Hex |")
    lines.append("|---|---:|---:|")
    config_values = {
        "AP_ADDRESS": 194,
        "IAP_ADDRESS": 68,
        "IAP_ADDRESS1": 70,
        "IAP_CODE_SIZE": 4096,
        "AP_DataSize": 256,
        "I2C_Speed": 100,
        "FW_FileSize": ap_size,
    }
    for k, v in config_values.items():
        lines.append(f"| `{k}` | {v} | `0x{v:X}` |")
    lines.append("")

    lines.append("## AP Image")
    lines.append("")
    lines.append(f"- AP size: `{ap_size}` / `0x{ap_size:X}`")
    lines.append(f"- AP target range: `0x{flash_start:X}..0x{flash_end:X}`")
    lines.append(f"- CRC16-CCITT over AP[0x28..end]: `0x{ap_crc:04X}`")
    lines.append(f"- CRC input length: `{len(ap_crc_region)}` / `0x{len(ap_crc_region):X}`")
    lines.append("")

    lines.append("## Erase/Flash Range Check")
    lines.append("")
    lines.append("| Range | Start | End | Length |")
    lines.append("|---|---:|---:|---:|")
    lines.append(f"| Native fixed erase | `0x{fixed_erase_start:X}` | `0x{fixed_erase_end:X}` | `{fixed_erase_end - fixed_erase_start + 1}` |")
    lines.append(f"| Native flash/program | `0x{flash_start:X}` | `0x{flash_end:X}` | `{ap_size}` |")
    lines.append(f"| Recommended erase for F1.4 coverage | `0x{fixed_erase_start:X}` | `0x{recommended_erase_end:X}` | `{recommended_erase_end - fixed_erase_start + 1}` |")
    lines.append("")
    lines.append(f"Overflow past native fixed erase: `{overflow}` bytes.")
    lines.append("")

    lines.append("## Official Call Sequence")
    lines.append("")
    lines.append("Dry-run sequence only:")
    lines.append("")
    lines.append("```text")
    lines.append("I2CInitial(0x1000)")
    lines.append("Sleep(200)")
    lines.append("I2CAPChangeToIAP(0xC2)")
    lines.append("Sleep(500)")
    lines.append("I2CIAPChangeToErase12ByteMode(0x44)")
    lines.append("if fail: I2CIAPChangeToErase12ByteMode(0x46), then AP1 fallback")
    lines.append("Sleep(200)")
    lines.append(f"I2CIAPSetAPFlashTable({ap_size})")
    lines.append("Sleep(200)")
    lines.append("I2CIAPSubmitCRCAP(active_iap_address)")
    lines.append("Sleep(200)")
    lines.append("I2CIAPFlashAP12ByteMode(active_iap_address)")
    lines.append("Sleep(200)")
    lines.append("I2CIAPChangeToAP(active_iap_address)")
    lines.append("```")
    lines.append("")

    lines.append("## Native Commands")
    lines.append("")
    lines.append("```text")
    lines.append("0x8101: AP -> IAP request through 0xC2")
    lines.append("0x8203: erase range setup, fixed 0x1000..0xEFFF in current DLL")
    lines.append("0x8104: AP CRC submission")
    lines.append(f"0x8204: flash/program setup, dynamic 0x1000..0x{flash_end:X}")
    lines.append("0x8102: IAP -> AP return")
    lines.append("```")
    lines.append("")

    lines.append("## Safety Gates Before Any Live Use")
    lines.append("")
    lines.append("- This manifest is not a flasher.")
    lines.append("- A live harness must refuse to run unless every input hash matches expected values.")
    lines.append("- A live harness must fix or account for the F1.4 erase-window mismatch before flashing F1.4-sized AP.")
    lines.append("- A live harness must have explicit user confirmation immediately before the first I2C call.")
    lines.append("- Recovery path must be documented before live AP firmware writes.")
    lines.append("")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    main()


