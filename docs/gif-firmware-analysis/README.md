# GIF And AP Firmware Investigation

This section documents the custom GIF / AP firmware branch of the AORUS LCD investigation.

## Result

The original static workaround avoided the AP firmware's native 64 KB erase path by changing the host upload header from `0x02` to `0x01`. The later GIF investigation found and tested the deeper AP firmware root cause:

```text
BA44 status-poll timeout was too short for the native 64 KB erase path.
B4D0 discarded the BA44 failure result and returned success anyway.
```

The successful AP firmware candidate preserved the native 64 KB path and changed only:

```text
BA44 timeout: 300 -> 1000
B4D0: propagate BA44 result instead of forcing success
```

This fixed the custom GIF path in local testing while keeping the intended `F1[0x11] == 0x02` GIF semantics.

## Key Reports

- `reports/N2A_Native64_Timeout1000_Live_Test_Result_20260601.md`
  - Live result for the successful native 64 KB repair candidate.
- `reports/AP_BA44_Timeout_1000_First_Risk_Assessment_20260601.md`
  - Why the first timeout increase was limited to `1000` rather than jumping to `3000`.
- `reports/AP_Native_64KB_Repair_First_Patch_Design_20260601.md`
  - Design notes for repairing native 64 KB erase instead of bypassing it.
- `reports/AP_64KB_Timeout_Final_Offline_Proof_20260601.md`
  - Offline proof that BA44 has a real interrupt-driven timeout and that B4D0 hides its result.
- `reports/AP_Timer_Tick_Evidence_20260601.md`
  - Timer/vector evidence used to interpret the timeout counter.
- `reports/Firmware_Updater_AP_Delivery_Path_20260601.md`
  - How the vendor updater stages AP/AP1 and submits CRC.

## Included

- Research reports and decision logs.
- Small manifests and public-safe hash/CRC summaries.
- Offline analysis scripts.
- Ghidra Java scripts used to inspect AP firmware.
- The controlled firmware harness source code used for the N2a test, without vendor binaries or firmware payloads.

## Excluded

- GIGABYTE firmware binaries, DLLs, and EXEs.
- Patched AP/AP1 firmware blobs.
- Compiled harness binaries.
- Live logs and raw traces.
- Ghidra project databases and generated dump files.
- Local machine backup files.

The included code is research tooling. It expects locally supplied vendor files and should not be treated as a general-purpose updater.

