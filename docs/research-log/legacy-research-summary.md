# Legacy Research Summary

This project went through several investigation phases before the final static-image fix was found.

## Early Hypotheses Tested

- GPU detection failure was ruled out: the RTX 5080 was visible to the GIGABYTE display APIs.
- NVIDIA I2C support removal was ruled out: both public and private NVAPI I2C entry points were present.
- Complete panel communication failure was ruled out: normal LCD I2C write/read paths returned success.
- Static image converter failure was ruled out: captured static `pData` rendered correctly offline.
- Caller-level display/apply flow was investigated and did not explain the lower-screen corruption.
- Windows-side cache and GIGABYTE System32 helper replay were investigated and became unlikely root causes.
- Firmware reinstall/update behavior was investigated; it did not reliably erase the stale custom image area.

## Firmware And AP Direction

Offline updater and AP firmware analysis showed erase-related paths and helped identify that the host F1 upload metadata selected erase behavior. This led to the key comparison between 64 KB block erase and 4 KB sector erase.

## Final Static Finding

The successful fix changes the static-image F1 metadata erase mode byte from `0x02` to `0x01`, switching static custom image upload from the failing 64 KB block erase path to the working 4 KB sector erase path.

## Local Raw Archive

Raw progress journals, generated IL dumps, long scan outputs, and machine-specific inventories are not kept in the public tree. They remain available locally under `local_artifacts_not_for_github/`.
