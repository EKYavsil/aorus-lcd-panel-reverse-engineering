from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path


ROOT = Path(r"<local-investigation-dir>")
FW = Path(r"<local-official-firmware-folder>")
OUT = ROOT / "n2b_flash_result_propagation_staging"

ORIGINAL_AP_SHA256 = "DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C"
PATCHED_AP_SHA256 = "046CB6D001EA6787C789E78E8103450478EAE4FAA21F00A0E4219454F7DDD333"
ORIGINAL_GVLCD_SHA256 = "DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70"
PATCHED_CRC16 = 0xCB8A

PATCHES = [
    (0xAA4A, "4F F4 96 70", "40 F2 E8 30", "BA44 timeout 300 -> 1000"),
    (0xA534, "01 20", "00 BF", "B4D0 preserve BA44 erase result"),
    (0xA74E, "01 20", "00 BF", "B6CC preserve BA44 page-program result"),
]

COPY_FILES = [
    "GvLcdFwUpdate.dll",
    "GvDisplay.dll",
    "GvDisplayA.dll",
    "GvIntelI2C.dll",
    "config.db",
    "Newtonsoft.Json.dll",
    "SQLite.Interop.dll",
    "System.Data.SQLite.dll",
    "msvcp140.dll",
    "vcruntime140.dll",
    "vcruntime140_1.dll",
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def crc16_1021(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def patch_payload(raw: bytes) -> tuple[bytes, list[dict[str, str]]]:
    data = bytearray(raw)
    records = []
    for offset, old_hex, new_hex, name in PATCHES:
        old = bytes.fromhex(old_hex)
        new = bytes.fromhex(new_hex)
        actual = bytes(data[offset : offset + len(old)])
        if actual != old:
            raise RuntimeError(
                f"{name} guard failed at 0x{offset:X}: "
                f"expected {old.hex(' ').upper()}, got {actual.hex(' ').upper()}"
            )
        data[offset : offset + len(old)] = new
        records.append(
            {
                "name": name,
                "file_offset": f"0x{offset:X}",
                "old": old_hex,
                "new": new_hex,
            }
        )
    return bytes(data), records


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    manifest: dict = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "scope": "N2B offline staging only; no updater execution",
        "inputs": {},
        "outputs": {},
    }

    for name in ("AP", "AP1"):
        source = FW / name
        raw = source.read_bytes()
        if len(raw) != 58_328 or sha256(raw) != ORIGINAL_AP_SHA256:
            raise RuntimeError(f"{name} is not the exact tested official F1.4 payload")

        patched, records = patch_payload(raw)
        if sha256(patched) != PATCHED_AP_SHA256:
            raise RuntimeError(f"{name} patched SHA256 mismatch")
        if crc16_1021(patched[0x28:]) != PATCHED_CRC16:
            raise RuntimeError(f"{name} patched CRC16 mismatch")

        (OUT / name).write_bytes(patched)
        manifest["inputs"][name] = {"size": len(raw), "sha256": sha256(raw)}
        manifest["outputs"][name] = {
            "size": len(patched),
            "sha256": sha256(patched),
            "crc16_0x28_eof": f"0x{PATCHED_CRC16:04X}",
            "patches": records,
        }

    if (OUT / "AP").read_bytes() != (OUT / "AP1").read_bytes():
        raise RuntimeError("Patched AP and AP1 differ")

    for name in COPY_FILES:
        source = FW / name
        if source.exists():
            data = source.read_bytes()
            (OUT / name).write_bytes(data)
            manifest["outputs"][name] = {"size": len(data), "sha256": sha256(data)}

    updater = OUT / "GvLcdFwUpdate.dll"
    if not updater.exists() or sha256(updater.read_bytes()) != ORIGINAL_GVLCD_SHA256:
        raise RuntimeError("Original signed GvLcdFwUpdate.dll hash mismatch")

    (OUT / "n2b_staging_manifest.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8"
    )
    print(f"STAGED_ONLY: {OUT}")


if __name__ == "__main__":
    main()
