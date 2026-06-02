# AP Option 1 Return Propagation Offline Patch Result

Date: 2026-06-01

Scope: offline artifact generation only. No live AP firmware flashing, no firmware updater execution, no DLL/service/panel action.

## Patch Intent

Patch AP F1.4 `FUN_0000B4D0`, the 64 KB block erase helper, so it no longer overwrites the status-poll result.

Original tail:

```text
0000B530  bl 0x0000BA44   ; poll SPI status / wait WIP clear
0000B534  movs r0,#0x1    ; force success, hiding poll failure
0000B536  b 0x0000B4DE    ; return
```

Offline patch:

```text
0000B534  nop             ; preserve r0 returned by FUN_0000BA44
```

Byte diff:

```text
AP address  : 0x0000B534
file offset : 0x0000A534
old bytes   : 01 20
new bytes   : 00 BF
```

## Script

Created:

```text
<local-gif-investigation-dir>\patch_option1_ap64_return_propagation_offline.ps1
```

The script:

1. verifies the source AP carve SHA256;
2. verifies bytes at `0xA534` are exactly `01 20`;
3. copies the source carve into a separate offline output directory;
4. patches only the copy;
5. verifies bytes at `0xA534` are exactly `00 BF`;
6. writes a manifest.

## Output Directory

```text
<local-gif-investigation-dir>\offline_ap_patch_option1_return_propagation
```

Manifest:

```text
<local-gif-investigation-dir>\offline_ap_patch_option1_return_propagation\option1_patch_manifest.txt
```

## Source Files

Both source carves were identical:

```text
SHA256 = DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
length = 58328 bytes
```

Sources:

```text
<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_firmware_package_extracts\F14_AP_carve_26340.bin
<local-private-artifacts>\panel_reset_investigation_raw\deep_reset_research_20260527\ap_firmware_package_extracts\F14_AP_carve_84673.bin
```

## Patched Files

Patched outputs:

```text
<local-gif-investigation-dir>\offline_ap_patch_option1_return_propagation\F14_AP_carve_26340_option1_return_propagation.bin
<local-gif-investigation-dir>\offline_ap_patch_option1_return_propagation\F14_AP_carve_84673_option1_return_propagation.bin
```

Both patched files are identical:

```text
SHA256 = EB8A892B79CFDE9F324EA1D7DC5CA94EB81C9689697144746E9E9D3018C09F94
length = 58328 bytes
bytes at 0xA534 = 00 BF
```

## Expected Behavioral Meaning

Before patch:

```text
64 KB erase poll fails -> AP still returns success -> upload state machine advances -> stale/corrupt media can remain hidden.
```

After patch:

```text
64 KB erase poll fails -> AP returns failure -> upload state machine should stop instead of silently corrupting storage.
```

This may not fix the visible GIF/static output by itself. It is primarily a diagnostic and integrity patch:

```text
turn silent corruption into a visible AP upload failure.
```

## Safety Status

Not safe for live flashing yet.

Remaining blockers before any live AP firmware experiment:

1. identify exact updater package embedding/checksum format;
2. confirm whether updater verifies AP payload hash/checksum;
3. confirm whether AP bootloader validates firmware checksum/signature;
4. establish a reliable recovery path if AP flashing fails;
5. create a no-guesswork packaging script that patches the original updater payload, not only a carved standalone AP binary.

## Current Decision

Option 1 has been implemented as an offline AP image patch artifact only.

No live application was performed.


