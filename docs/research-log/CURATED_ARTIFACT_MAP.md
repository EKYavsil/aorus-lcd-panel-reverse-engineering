# Curated Artifact Map

Date: 2026-05-31

This repository keeps the useful public-facing parts of the investigation while excluding proprietary binaries and bulky generated output.

## Final Fix Path

- `build_final_static_sector_patch.cs`
  - Root-level minimal Mono.Cecil patch builder.
  - Changes only the static image F1 header erase-mode byte from `0x02` to `0x01`.
  - Guarded by static image type, local destination, F1 packet magic, F1 packet destination bytes, and expected original erase-mode byte.
- `StaticSectorPatcher.csproj`
  - Root-level .NET project file for building the patch builder.
- `PATCHER.md`
  - Root-level explanation of patch purpose, guard conditions, build command, and current limitations.
- `docs/evidence/static-sector-erase-success.md`
  - Records the successful full-screen static image result.
- `JOURNAL-en.md`
  - English narrative summary of the investigation path.
- `JOURNAL-tr.md`
  - Turkish narrative summary of the investigation path.

## Firmware And AP Analysis

- `tools/ghidra/`
  - Java Ghidra scripts used to inspect firmware/updater code, function references, AP firmware constants, SPI/page-program logic, and erase-mode behavior.
- `docs/analysis/f1-header-to-erase-mode.md`
  - Maps the F1 metadata byte to AP firmware erase behavior.
- `docs/analysis/ap-flash-failure-propagation.md`
  - Explains why host-side success could be misleading even when panel storage was stale.
- `docs/analysis/f2-finalize-success-criteria.md`
  - Documents finalize behavior and why it was not sufficient evidence of a valid full write.

## SendImage And Payload Elimination

- `experiments/sendimage-tracing/`
  - Source and notes from passive SendImage tracing.
  - This phase proved static pData and chunk transmission were not the root cause.
- `docs/analysis/sendimage-trace-report.md`
  - Summarizes SendImage metadata, destination, chunk count, and success path.

## Failed Or Rejected Paths

- `docs/research-log/FAILED_APPROACHES_AND_ELIMINATED_HYPOTHESES.md`
  - Human-readable summary of what was ruled out.
- `docs/research-log/legacy-research-summary.md`
  - Concise summary of earlier research phases and hypotheses later rejected.

## GIF Work

- `tools/python/gif_experiments/`
  - Python scripts from the custom GIF side of the investigation.
  - GIF remains unresolved and is intentionally separated from the fixed static-image path.
- `docs/analysis/gif-erase-mode-hypothesis.md`
  - Current offline hypothesis for whether GIF may share the same erase-mode bug as static image uploads.

## Local-Only Artifacts

The following are deliberately excluded from GitHub:

- GIGABYTE DLL/EXE files.
- Patched DLL outputs.
- Firmware blobs, extracted packages, and updater binaries.
- Raw logs and traces.
- Binary pData dumps and rendered test images.
- Ghidra project databases.
- Live-deploy/admin cleanup scripts.

Local-only raw material is stored outside the public tree. Some generated IL dumps, raw progress journals, local cache inventories, machine-specific scan outputs, and old live-deploy/admin cleanup scripts were intentionally moved out of the GitHub-facing tree.

## Additional Offline Tooling

- `tools/firmware_static_analysis/`
  - Read-only firmware inventory and updater inspection helpers from the static firmware analysis phase.
- `tools/windows_cache_investigation/`
  - Scripts used to investigate Windows-side GIGABYTE cache/service persistence hypotheses.
- `tools/ghidra/legacy/NativeI2CStaticReport.py`
  - Early Ghidra Python helper retained alongside the Java scripts.
