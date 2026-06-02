# Experiments

This directory contains historical experiment notes, trace plans, diagnostic reports, and intermediate investigation artifacts.

These files are **not** the final solution and should not be treated as authoritative implementation guidance. Some notes in this directory describe hypotheses that were later rejected, superseded, or narrowed by AP firmware analysis.

## Final Working Path

For the current working AP firmware repair, use:

- [`FIRMWARE_PATCHER.md`](../FIRMWARE_PATCHER.md)
- [`src/Program.cs`](../src/Program.cs)
- [`docs/evidence/gif-native64-timeout1000-success.md`](../docs/evidence/gif-native64-timeout1000-success.md)

## Historical Context

The static-image host workaround and SendImage tracing remain useful as research history because they helped isolate the true panel-side failure:

- [`docs/evidence/static-sector-erase-success.md`](../docs/evidence/static-sector-erase-success.md)
- [`docs/analysis/f1-header-to-erase-mode.md`](../docs/analysis/f1-header-to-erase-mode.md)
- [`docs/analysis/sendimage-trace-report.md`](../docs/analysis/sendimage-trace-report.md)

The final product is no longer the host DLL workaround. It is the AP firmware payload patcher that repairs the native 64 KB erase path.
