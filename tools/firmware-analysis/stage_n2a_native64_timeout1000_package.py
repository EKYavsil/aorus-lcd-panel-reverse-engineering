from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
FW = Path(r"<local-firmware-package-dir>")
OUT = ROOT / "n2a_native64_timeout1000_live_staging_20260601"

ORIGINAL_AP_SHA256 = "DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C"
ORIGINAL_GVLCD_SHA256 = "DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70"

PATCHES = [
    {
        "name": "BA44 timeout 300 -> 1000",
        "offset": 0xAA4A,
        "old": bytes.fromhex("4F F4 96 70"),
        "new": bytes.fromhex("40 F2 E8 30"),
        "ap_address": "0x0000BA4A",
        "meaning": "Extend AP internal SPI WIP poll timeout from 300 ticks to 1000 ticks.",
    },
    {
        "name": "B4D0 propagate BA44 result",
        "offset": 0xA534,
        "old": bytes.fromhex("01 20"),
        "new": bytes.fromhex("00 BF"),
        "ap_address": "0x0000B534",
        "meaning": "Do not force 64KB erase success after BA44; preserve poll result.",
    },
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
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def patch_ap(src: Path) -> tuple[bytes, list[dict]]:
    data = bytearray(src.read_bytes())
    applied = []
    for p in PATCHES:
        off = p["offset"]
        old = p["old"]
        new = p["new"]
        actual = bytes(data[off : off + len(old)])
        if actual != old:
            raise RuntimeError(
                f"{src} offset 0x{off:X}: expected {old.hex(' ').upper()}, got {actual.hex(' ').upper()}"
            )
        data[off : off + len(old)] = new
        applied.append(
            {
                "name": p["name"],
                "file_offset": f"0x{off:X}",
                "ap_address": p["ap_address"],
                "old": old.hex(" ").upper(),
                "new": new.hex(" ").upper(),
                "meaning": p["meaning"],
            }
        )
    return bytes(data), applied


def copy_file(name: str) -> dict:
    src = FW / name
    dst = OUT / name
    data = src.read_bytes()
    dst.write_bytes(data)
    return {
        "source": str(src),
        "path": str(dst),
        "size": len(data),
        "sha256": sha256(data),
    }


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    manifest: dict = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "scope": "N2a native 64KB live staging; files staged only, no updater execution",
        "staging_dir": str(OUT),
        "design": {
            "name": "N2a native64 timeout1000",
            "goal": "Keep native 0xD8 64KB erase path, increase BA44 timeout to 1000, and stop B4D0 from forcing success.",
            "uses_original_signed_native_flasher": True,
            "does_not_use_16x4kb_fallback": True,
            "does_not_patch_gvlcdfwupdate": True,
        },
        "inputs": {},
        "files": {},
        "checks": {},
    }

    for name in ["AP", "AP1"]:
        src = FW / name
        raw = src.read_bytes()
        raw_sha = sha256(raw)
        if raw_sha != ORIGINAL_AP_SHA256:
            raise RuntimeError(f"{name} SHA256 mismatch: {raw_sha}")
        patched, applied = patch_ap(src)
        dst = OUT / name
        dst.write_bytes(patched)
        manifest["inputs"][name] = {
            "path": str(src),
            "size": len(raw),
            "sha256": raw_sha,
        }
        manifest["files"][name] = {
            "path": str(dst),
            "size": len(patched),
            "sha256": sha256(patched),
            "crc16_1021_over_0x28_to_eof": f"0x{crc16_1021(patched[0x28:]):04X}",
            "patches": applied,
        }

    for name in COPY_FILES:
        manifest["files"][name] = copy_file(name)

    if manifest["files"]["GvLcdFwUpdate.dll"]["sha256"] != ORIGINAL_GVLCD_SHA256:
        raise RuntimeError("Staged GvLcdFwUpdate.dll is not the original signed DLL")

    ap = (OUT / "AP").read_bytes()
    ap1 = (OUT / "AP1").read_bytes()
    manifest["checks"] = {
        "AP_size_58328": len(ap) == 58328,
        "AP1_size_58328": len(ap1) == 58328,
        "AP_AP1_identical": ap == ap1,
        "GvLcdFwUpdate_original_signed_hash": manifest["files"]["GvLcdFwUpdate.dll"]["sha256"]
        == ORIGINAL_GVLCD_SHA256,
        "BA44_patch_present": ap[0xAA4A : 0xAA4E].hex(" ").upper() == "40 F2 E8 30",
        "B4D0_patch_present": ap[0xA534 : 0xA536].hex(" ").upper() == "00 BF",
    }

    if not all(manifest["checks"].values()):
        raise RuntimeError(f"One or more checks failed: {manifest['checks']}")

    (OUT / "n2a_staging_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest["checks"], indent=2))
    print(f"STAGED: {OUT}")


if __name__ == "__main__":
    main()


