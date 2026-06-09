# GIF Native 64 KB Firmware Fix Success

Date: 2026-06-01

## Summary

The custom GIF corruption path was fixed in local testing by repairing the AP firmware's native 64 KB erase path instead of forcing GIF uploads through the host-side 4 KB erase path.

Successful AP firmware candidate:

```text
BA44 timeout: 300 -> 1000
B4D0 forced success: removed
B4D0 now propagates BA44 status-poll result
```

This preserved:

```text
F1[0x11] == 0x02
SPI opcode 0xD8
64 KB erase unit 0x10000
GIF timing/finalization semantics
```

## Root Cause

The AP firmware's native 64 KB erase helper called the shared SPI status-poll function but then overwrote its result with success. If a 64 KB erase exceeded the original 300-tick poll window, the upload state machine could continue as though the flash had been erased.

That matched both major symptoms:

- static image uploads updated the upper region while the lower region stayed black/stale;
- GIF uploads could complete from the host perspective while panel-side storage remained inconsistent.

## Evidence Trail

Detailed reports:

- `docs/gif-firmware-analysis/reports/AP_64KB_Timeout_Final_Offline_Proof_20260601.md`
- `docs/gif-firmware-analysis/reports/AP_BA44_Timeout_1000_First_Risk_Assessment_20260601.md`
- `docs/gif-firmware-analysis/reports/N2A_Native64_Timeout1000_Live_Test_Result_20260601.md`

Source-only harness:

- `tools/firmware-harness/n2a-native64-timeout1000/`

No vendor firmware blobs, patched AP images, DLLs, or live logs are included in this repository.

## 2026-06-09 Follow-up

After many successful static-image and GIF uploads, one GIF later destabilized the panel. Further analysis confirmed that the F1.4 `FUN_0000B6CC` page-program helper also discarded the shared BA44 status-poll result.

The current repair therefore adds:

```text
B6CC: preserve BA44 page-program result instead of forcing success
```

The same previously failing GIF subsequently uploaded and played successfully with a converted `animation.bin` payload of `5,310,534` bytes. This is consistent with the repair, but the firmware update also reset panel state, so a single successful retry is not treated as conclusive causal proof.

See:

- `docs/evidence/page-program-result-propagation-20260609.md`
