import hashlib
import json
import pathlib
import re
import struct


ROOT = pathlib.Path(r"<local-firmware-package-dir>")
OUT = pathlib.Path(r"<local-gif-investigation-dir>\ap_firmware_delivery_verifier_output.json")

PACKAGES = [
    ROOT / "Gv_lcd_fw_update_N50_ICE_F1.2.exe",
    ROOT / "GV-N5080AORUSM_ICE-16GD_LCD_F1.3.exe",
    ROOT / "GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe",
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def parse_embedded_config(data: bytes):
    match = re.search(rb'\{\s*"Info"\s*:\s*\{.*?\}\s*\}', data, re.S)
    if not match:
        return None
    text = match.group(0).decode("latin1")
    # Gigabyte config has a trailing comma before the closing brace.
    relaxed = re.sub(r",\s*([}\]])", r"\1", text)
    return {
        "offset": match.start(),
        "text": text,
        "parsed": json.loads(relaxed),
    }


def vector_table_score(blob: bytes) -> int:
    if len(blob) < 64:
        return 0
    words = struct.unpack_from("<16I", blob, 0)
    sp = words[0]
    reset = words[1]
    if not (0x20000000 <= sp <= 0x20020000):
        return 0
    if not (reset & 1):
        return 0
    if not (0x1000 <= (reset & ~1) <= 0x20000):
        return 0

    score = 0
    for word in words[1:]:
        if word in (0, 0xFFFFFFFF):
            score += 1
        elif (word & 1) and 0x1000 <= (word & ~1) <= 0x20000:
            score += 1
    return score


def find_ap_candidates(data: bytes, size: int, config_offset: int):
    candidates = []
    for offset in range(0, len(data) - size + 1):
        blob = data[offset : offset + size]
        score = vector_table_score(blob)
        if score < 10:
            continue
        # The real WPF resources appear before the embedded config JSON. This
        # filter drops unrelated framework/code false positives later in the EXE.
        before_config = offset < config_offset
        candidates.append(
            {
                "offset": offset,
                "offset_hex": f"0x{offset:X}",
                "before_config": before_config,
                "score": score,
                "sha256": sha256(blob),
                "first16": blob[:16].hex(" ").upper(),
                "last16": blob[-16:].hex(" ").upper(),
                "byte_a534": blob[0xA534 : 0xA536].hex(" ").upper() if len(blob) > 0xA536 else None,
            }
        )
    return candidates


def main():
    results = []
    erase_start = 0x1000
    erase_end = 0xEFFF
    erase_len = erase_end - erase_start + 1

    for package in PACKAGES:
        data = package.read_bytes()
        cfg = parse_embedded_config(data)
        if cfg is None:
            results.append({"package": str(package), "error": "embedded config not found"})
            continue

        info = cfg["parsed"]["Info"]
        size = int(info["FW_FileSize"])
        size1 = int(info["FW_FileSize1"])
        flash_start = 0x1000
        flash_end = flash_start + size - 1
        overflow = max(0, flash_end - erase_end)
        candidates = find_ap_candidates(data, size, cfg["offset"])
        real_candidates = [c for c in candidates if c["before_config"]]

        results.append(
            {
                "package": str(package),
                "package_size": len(data),
                "package_sha256": sha256(data),
                "config_offset": cfg["offset"],
                "config_offset_hex": f"0x{cfg['offset']:X}",
                "config_info": info,
                "declared_size": size,
                "declared_size1": size1,
                "erase_start_hex": f"0x{erase_start:X}",
                "erase_end_hex": f"0x{erase_end:X}",
                "erase_len": erase_len,
                "flash_start_hex": f"0x{flash_start:X}",
                "flash_end_hex": f"0x{flash_end:X}",
                "flash_len": size,
                "overflow_bytes_past_erase": overflow,
                "fits_fixed_erase_window": overflow == 0,
                "ap_candidates_before_config": real_candidates,
                "all_vector_candidates_count": len(candidates),
            }
        )

    OUT.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(OUT)
    for item in results:
        if "error" in item:
            print(item["package"], item["error"])
            continue
        print()
        print(pathlib.Path(item["package"]).name)
        print("  FW_Ver:", item["config_info"].get("FW_Ver"))
        print("  size:", item["declared_size"], f"0x{item['declared_size']:X}")
        print("  flash:", item["flash_start_hex"], "..", item["flash_end_hex"])
        print("  fixed erase:", item["erase_start_hex"], "..", item["erase_end_hex"])
        print("  overflow:", item["overflow_bytes_past_erase"])
        print("  AP resource candidates:", [c["offset_hex"] for c in item["ap_candidates_before_config"]])
        print("  AP sha:", [c["sha256"] for c in item["ap_candidates_before_config"]])


if __name__ == "__main__":
    main()


