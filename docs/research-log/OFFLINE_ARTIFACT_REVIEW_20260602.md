# Offline Artifact Review

Date: 2026-06-02

Reviewed local folder:

```text
<desktop>\aorus_gif_offline_investigation_20260531
```

## Imported Into Public Repo

- Public-facing GIF/AP firmware reports under `docs/gif-firmware-analysis/`.
- AP firmware manifests under `docs/gif-firmware-analysis/manifests/`.
- Offline AP analysis scripts under `tools/firmware-analysis/`.
- AP Ghidra scripts under `tools/ghidra-scripts/ap-firmware/`.
- Source-only N2a harness under `tools/firmware-harness/n2a-native64-timeout1000/`.
- Host command-byte scanner source under `tools/host-analysis/byte-command-scanner/`.
- External protocol reference notes in `docs/research-log/EXTERNAL_PROTOCOL_REFERENCES.md`.

## Deliberately Not Imported

The following local artifact classes were intentionally left out:

- GIGABYTE DLLs, EXEs, updater packages, `AP`, `AP1`, `config.db`, and other firmware/vendor binaries.
- Patched AP/AP1 payloads and staged live firmware folders.
- Compiled helper binaries, `.pdb` files, `bin/`, `obj/`, and generated runtime files.
- Raw `.log` files and live deployment scripts.
- Generated GIF/BIN diagnostic payloads and test images.
- Large raw Ghidra exports that are already summarized by curated reports and scripts.
- Local checkouts of third-party public repositories.

## Raw Export Decision

Large raw exports such as:

```text
exports\legacy_gif_log_hits.txt
ghidra_64kb_deep_outputs\ap_64kb_timeout_final_proof_dump_*.txt
scratch\cortex_m_irq_peripheral_list_cache.txt
```

were not imported. They are bulky generated outputs, not source. The useful conclusions from them are captured in:

- `docs/gif-firmware-analysis/reports/AP_64KB_Timeout_Final_Offline_Proof_20260601.md`
- `docs/gif-firmware-analysis/reports/AP_Timer_Tick_Evidence_20260601.md`
- `docs/gif-firmware-analysis/reports/AP_64KB_Path_Definitive_Investigation_20260601.md`
- `tools/ghidra-scripts/ap-firmware/`

## Current Public Boundary

The repository should remain source/documentation focused:

- It can generate patched AP/AP1 payloads from local official firmware files.
- It should not redistribute vendor firmware, vendor DLLs, patched AP/AP1 files, or live flashing launchers.
- It should keep live flashing instructions separate from the public patcher product.
