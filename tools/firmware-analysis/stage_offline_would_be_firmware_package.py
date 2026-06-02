from __future__ import annotations

import hashlib
import json
import shutil
from datetime import datetime
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
SRC = ROOT / "offline_firmware_patch_candidates_20260601"
OUT = ROOT / "offline_would_be_firmware_package_sector_FFFF_20260601"


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


def copy(src: Path, dst_name: str) -> dict:
    dst = OUT / dst_name
    shutil.copy2(src, dst)
    data = dst.read_bytes()
    return {
        "path": str(dst),
        "source": str(src),
        "size": len(data),
        "sha256": sha256_file(dst),
        "crc16_over_0x28_to_end": f"0x{crc16_native_style(data[0x28:]):04X}" if dst_name in {"AP", "AP1"} else None,
    }


def main() -> int:
    if OUT.exists():
        raise SystemExit(f"Refusing to overwrite existing package directory: {OUT}")
    OUT.mkdir(parents=True)

    manifest = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "scope": "offline would-be package staging only; do not run from this directory without a separate live-test plan",
        "package_dir": str(OUT),
        "variant": "AP option3 16x4K + updater erase end 0xFFFF",
        "files": {},
        "warnings": [
            "This package is not a live instruction.",
            "No executable was run to create this package.",
            "The original FWUpgrade.exe resource extraction behavior can overwrite loose files if used incorrectly.",
            "Patched GvLcdFwUpdate.dll has an invalid Authenticode hash by design.",
        ],
    }

    manifest["files"]["AP"] = copy(SRC / "AP_option3_16x4k.bin", "AP")
    manifest["files"]["AP1"] = copy(SRC / "AP1_option3_16x4k.bin", "AP1")
    manifest["files"]["GvLcdFwUpdate.dll"] = copy(SRC / "GvLcdFwUpdate_erase_end_FFFF.dll", "GvLcdFwUpdate.dll")
    for name in ["GvDisplay.dll", "GvDisplayA.dll", "GvIntelI2C.dll", "config.db"]:
        manifest["files"][name] = copy(SRC / name, name)

    checks = {
        "AP_exists": (OUT / "AP").exists(),
        "AP1_exists": (OUT / "AP1").exists(),
        "AP_AP1_hash_equal": manifest["files"]["AP"]["sha256"] == manifest["files"]["AP1"]["sha256"],
        "AP_size_58328": manifest["files"]["AP"]["size"] == 58328,
        "AP1_size_58328": manifest["files"]["AP1"]["size"] == 58328,
        "AP_crc_4BDD": manifest["files"]["AP"]["crc16_over_0x28_to_end"] == "0x4BDD",
        "AP1_crc_4BDD": manifest["files"]["AP1"]["crc16_over_0x28_to_end"] == "0x4BDD",
        "GvLcdFwUpdate_size_matches_source": manifest["files"]["GvLcdFwUpdate.dll"]["size"] == (SRC / "GvLcdFwUpdate_erase_end_FFFF.dll").stat().st_size,
    }
    manifest["checks"] = checks
    manifest["verdict"] = "PASS_OFFLINE_PACKAGE_STAGING" if all(checks.values()) else "CHECK_FAILED"

    (OUT / "package_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(OUT)
    print(manifest["verdict"])
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())


