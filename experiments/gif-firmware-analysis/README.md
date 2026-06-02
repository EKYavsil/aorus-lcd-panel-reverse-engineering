# GIF / AP Firmware Investigation

This folder collects the public-facing research artifacts for the custom GIF and AP firmware side of the AORUS LCD investigation.

The important result is the native AP firmware fix candidate that preserves the 64 KB erase path instead of bypassing it:

```text
BA44 timeout: 300 -> 1000
B4D0: stop forcing success after the BA44 status poll
```

The live test result is documented under:

```text
docs/gif-firmware-analysis/reports/N2A_Native64_Timeout1000_Live_Test_Result_20260601.md
```

Excluded from this repository:

- GIGABYTE firmware binaries and extracted AP/AP1 blobs
- vendor DLL/EXE files
- compiled harness binaries
- live logs
- Ghidra project databases and generated disassembly dumps
- local machine backup files

Included here:

- research reports
- small public-safe manifests
- offline analysis scripts
- Ghidra Java scripts
- live-capable harness source code, without proprietary payloads
