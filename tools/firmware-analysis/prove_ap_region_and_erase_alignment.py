from __future__ import annotations

import json
import struct
from pathlib import Path


ROOT = Path(r"<local-gif-investigation-dir>")
AP = Path(r"<local-firmware-package-dir>\AP")
OUT = ROOT / "ap_region_erase_alignment_proof_20260601.json"


data = AP.read_bytes()
vectors = [struct.unpack_from("<I", data, i)[0] for i in range(0, 0x100, 4)]
ap_base = 0x1000
ap_size = len(data)
ap_end = ap_base + ap_size - 1
original_erase_start = 0x1000
original_erase_end = 0xEFFF
sector_size = 0x1000
containing_sector_start = ap_end & ~(sector_size - 1)
containing_sector_end = containing_sector_start + sector_size - 1

proof = {
    "scope": "offline arithmetic/vector-table/protocol proof only",
    "ap_file": str(AP),
    "ap_size": {"dec": ap_size, "hex": f"0x{ap_size:X}"},
    "ap_base_evidence": {
        "config_IAP_CODE_SIZE": "4096 / 0x1000",
        "native_8203_erase_start": "0x1000",
        "native_8204_program_start": "0x1000",
        "vector_table_first_words": [f"0x{x:08X}" for x in vectors[:16]],
        "reset_vector": f"0x{vectors[1]:08X}",
        "note": "Cortex-M vector entries point into the 0x1000+ address range, consistent with AP being linked to run at 0x1000 while bootloader/IAP occupies the lower 0x1000 bytes.",
    },
    "ranges": {
        "ap_program_range": {"start": "0x1000", "end": f"0x{ap_end:X}"},
        "original_fixed_erase_range": {"start": f"0x{original_erase_start:X}", "end": f"0x{original_erase_end:X}"},
        "not_erased_but_programmed_by_F1_4": {"start": "0xF000", "end": f"0x{ap_end:X}", "bytes": ap_end - 0xF000 + 1},
        "sector_containing_ap_end": {"start": f"0x{containing_sector_start:X}", "end": f"0x{containing_sector_end:X}", "sector_size": f"0x{sector_size:X}"},
    },
    "conclusion": {
        "erase_end_F3D7_is_exact_but_not_sector_aligned": ap_end == 0xF3D7,
        "erase_end_FFFF_is_sector_aligned": containing_sector_end == 0xFFFF,
        "why_FFFF_is_not_arbitrary": "The AP image extends into the 0xF000..0xFFFF 4KB sector. If the bootloader erases by 4KB sectors, a correct update must erase that whole sector before programming bytes through 0xF3D7.",
        "remaining_unknown": "This proves sector alignment for the AP image tail, but does not prove the bootloader has no special hidden metadata in the unused 0xF3D8..0xFFFF tail. However, such metadata would already conflict with any normal sector erase required to update F1.4 bytes in the same sector.",
    },
}

OUT.write_text(json.dumps(proof, indent=2), encoding="utf-8")
print(OUT)


