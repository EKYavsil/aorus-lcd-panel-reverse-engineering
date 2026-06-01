# Experiments

This directory contains historical experiment notes, trace plans, diagnostic reports, and intermediate investigation artifacts.

These files are **not** the final solution and should not be treated as authoritative implementation guidance. Some notes in this directory may describe hypotheses that were later rejected, superseded, or narrowed by later AP firmware analysis and static-sector erase testing.

## Final Working Path

For the current working static-image workaround, use the root-level patch builder and its documentation:

- [`PATCHER.md`](../PATCHER.md)
- [`build_final_static_sector_patch.cs`](../build_final_static_sector_patch.cs)
- [`StaticSectorPatcher.csproj`](../StaticSectorPatcher.csproj)

For the final static-image evidence and technical explanation, start with:

- [`docs/evidence/static-sector-erase-success.md`](../docs/evidence/static-sector-erase-success.md)
- [`docs/analysis/f1-header-to-erase-mode.md`](../docs/analysis/f1-header-to-erase-mode.md)
- [`docs/analysis/sendimage-trace-report.md`](../docs/analysis/sendimage-trace-report.md)
