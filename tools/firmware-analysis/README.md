# Firmware Analysis Tools

Offline helper scripts used during AP firmware and updater-path analysis.

These scripts are included to show the research workflow and make the analysis reproducible for someone who has their own locally obtained firmware package.

## Notes

- No proprietary firmware, AP/AP1 blob, DLL, EXE, or extracted package is included.
- Some scripts contain placeholder paths such as `<local-firmware-package-dir>` or `<local-gif-investigation-dir>`.
- Replace those placeholders with local paths before running a script.
- Scripts in this folder are intended for offline analysis and staging verification unless their comments explicitly say otherwise.

## Main Areas

- AP patch-site and CRC analysis.
- Firmware delivery and updater AP/AP1 path mapping.
- 64 KB erase-window verification.
- N2a native64 timeout1000 staging.
- Historical failed-candidate tooling retained for transparency.

