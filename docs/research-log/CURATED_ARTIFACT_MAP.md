# Curated Artifact Map

Date: 2026-06-01

This repository keeps the useful public-facing parts of the investigation while excluding proprietary binaries, firmware blobs, raw traces, and machine-specific output.

## Final Fix Path

- `AorusLcdFirmwarePatcher.csproj`
  - Root-level .NET project for the AP firmware payload patcher.
- `src/Program.cs`
  - Source for the guarded AP/AP1 patcher.
  - Applies the current flash-operation repair: `BA44` timeout `300 -> 1000`, `B4D0` erase-result propagation, and `B6CC` page-program-result propagation.
- GitHub Release asset: `AorusLcdFirmwarePatcher-win-x64-self-contained.exe`
  - Optional runtime-free convenience executable.
  - It is generated from the public source, does not contain GIGABYTE firmware files, and is not committed because the self-contained build is large.
- `FIRMWARE_PATCHER.md`
  - Usage, guard conditions, expected hashes, output format, and build instructions.
- `SAFETY.md`
  - Firmware safety boundaries and risk notes.
- `docs/evidence/gif-native64-timeout1000-success.md`
  - Records the original successful native 64 KB timeout/erase-result repair.
- `docs/evidence/page-program-result-propagation-20260609.md`
  - Records the page-program defect, three-patch firmware update, and controlled evidence limits.
- `JOURNAL-en.md`
  - English narrative summary of the investigation path.
- `JOURNAL-tr.md`
  - Turkish narrative summary of the investigation path.

## Firmware And AP Analysis

- `docs/gif-firmware-analysis/`
  - Public-facing reports from the custom GIF and AP firmware investigation.
  - Includes the final native 64 KB repair evidence.
- `tools/firmware-analysis/`
  - Offline scripts used for AP hash/CRC analysis, firmware delivery mapping, erase-window checks, and staging verification.
- `tools/firmware-harness/n2a-native64-timeout1000/`
  - Historical source-only dry-run verifier for the original two-patch N2a build.
- `tools/firmware-harness/n2b-flash-result-propagation/`
  - Current source-only dry-run verifier for all three patch sites.
  - No AP/AP1 blobs, vendor DLLs, compiled binaries, or live logs are included.
- `tools/host-analysis/byte-command-scanner/`
  - Source for the managed host assembly command-byte scanner.
  - Used to support host DLL command-byte scan reports without including vendor DLLs.
- `tools/ghidra-scripts/ap-firmware/`
  - Java Ghidra scripts used to inspect AP firmware timeout, timer vector, command parser, and patch sites.
- `tools/ghidra/`
  - Earlier Ghidra scripts for firmware/updater code, function references, AP firmware constants, SPI/page-program logic, and erase-mode behavior.

## SendImage And Payload Elimination

- `experiments/sendimage-tracing/`
  - Source and notes from passive SendImage tracing.
  - This phase proved static pData and chunk transmission were not the root cause.
- `docs/analysis/sendimage-trace-report.md`
  - Summarizes SendImage metadata, destination, chunk count, and success path.

## Superseded Host Workaround

- `docs/evidence/static-sector-erase-success.md`
  - Historical evidence for the temporary static-image host workaround.
  - Retained because it helped isolate the broken native 64 KB AP erase path.
- `experiments/static-sector-erase-test/`
  - Historical static-only host patch design.
  - Superseded by the AP firmware fix and not presented as the final product.
- `docs/analysis/f1-header-to-erase-mode.md`
  - Maps the F1 metadata byte to AP firmware erase behavior.

## Failed Or Rejected Paths

- `docs/research-log/FAILED_APPROACHES_AND_ELIMINATED_HYPOTHESES.md`
  - Human-readable summary of what was ruled out.
- `docs/research-log/legacy-research-summary.md`
  - Concise summary of earlier research phases and hypotheses later rejected.
- `docs/research-log/EXTERNAL_PROTOCOL_REFERENCES.md`
  - Public repository references that informed the protocol investigation without vendoring third-party code.
- `docs/research-log/OFFLINE_ARTIFACT_REVIEW_20260602.md`
  - Documents which local offline artifacts were imported and which were intentionally excluded.
- `experiments/`
  - Historical traces, diagnostic notes, and rejected hypotheses.

## Local-Only Artifacts

The following are deliberately excluded from GitHub:

- GIGABYTE DLL/EXE files.
- Firmware blobs, extracted packages, and updater binaries.
- Patched AP/AP1 payloads.
- Patched vendor DLL outputs.
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
