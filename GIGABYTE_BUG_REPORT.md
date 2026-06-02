# GIGABYTE AORUS LCD Custom Image/GIF Corruption Report

Date: 2026-06-02

## Subject

AP firmware defect in the AORUS RTX 5080 ICE LCD controller native 64 KB flash erase path causes custom static image and custom GIF corruption.

## Affected Hardware And Software

Tested hardware:

```text
GPU:  GIGABYTE AORUS GeForce RTX 5080 MASTER ICE 16G
SSID: 0x418C
VID:  0x10DE
DID:  0x2C02
SVID: 0x1458
```

Tested official LCD firmware package:

```text
Firmware package: GV-N5080AORUSM_ICE-16GD_LCD_F1.4.exe
AP size:          58328 bytes
AP SHA256:        DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
AP1 SHA256:       DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C
GvLcdFwUpdate.dll SHA256:
                  DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70
```

Related GIGABYTE software observed during the investigation:

```text
GIGABYTE Control Center
GBT_VGA module
ucVga.dll SendImage path
GvLcdFwUpdate.dll firmware update path
GVDisplay.dll/GvDisplayA.dll I2C transport
```

## User-Visible Symptoms

Custom LCD media uploaded through GIGABYTE Control Center can become corrupted even though the host-side upload path reports success.

Observed symptoms:

- Custom static images update only the upper part of the LCD.
- The lower region, roughly the lower 40 percent, stays black or stale.
- Custom GIFs can upload but then display incorrectly, freeze, stay on loading, or leave the panel in an inconsistent state.
- Built-in/default LCD modes can still display correctly.
- Reinstalling GCC, cleaning NVIDIA drivers, and rerunning official LCD firmware packages did not reliably clear the bad custom-media state.

The static image symptom aligned with the first 64 KB boundary of a 320 x 170 RGB565 frame:

```text
Frame payload: 320 * 170 * 2 = 108800 bytes
Row size:      320 * 2 = 640 bytes
64 KB / 640 = about 102.4 rows
102.4 / 170 = about 60 percent visible
```

This matches the observed "upper part updates, lower part remains black/stale" failure mode.

## Summary Of Investigation

The investigation ruled out several host-side causes:

1. The GPU is detected correctly by GIGABYTE display DLLs.
2. NVIDIA/GIGABYTE I2C transport is reachable.
3. The host sends custom image/GIF payload chunks successfully.
4. Static image source conversion is not the root cause. The `pData` buffer rendered correctly offline and did not contain the black lower region.
5. The GCC post-upload display/apply sequence was not the root cause. A clean apply sequence could refresh the panel but did not fix the lower-region corruption.

The strongest root cause is in the panel AP firmware's native 64 KB flash erase implementation.

## Root Cause

The AP firmware has a defective native 64 KB erase helper and a too-short status-poll timeout.

Two AP firmware functions are central:

```text
FUN_0000BA44: shared SPI status/WIP poll helper
FUN_0000B4D0: native 64 KB block erase helper using SPI opcode 0xD8
```

`FUN_0000BA44` starts a timeout counter at `300`:

```asm
0000ba4a  mov.w r0,#0x12c   ; 300
```

The timeout counter is decremented by a hardware interrupt/timer path. Offline analysis strongly indicates this is a short millisecond-class wait window, approximately 300 ms.

`FUN_0000B4D0` issues a 64 KB erase command and calls `FUN_0000BA44`, but then overwrites the poll result with success:

```asm
0000b500  movs r0,#0xd8       ; SPI 64 KB block erase
...
0000b530  bl   FUN_0000BA44   ; wait for WIP clear
0000b534  movs r0,#0x1        ; force success
```

Therefore, if the 64 KB erase takes longer than the `300` timeout, `FUN_0000BA44` can report failure/timeout, but `FUN_0000B4D0` discards that failure and reports success to the upload state machine.

The 4 KB erase helper does not have this problem. It checks the same poll helper result and propagates failure:

```asm
0000b940  movs r0,#0x20       ; SPI 4 KB sector erase
...
0000b970  bl   FUN_0000BA44
0000b974  cmp  r0,#0x0
0000b976  beq  fail
```

This explains why a temporary host workaround that forced static uploads onto the 4 KB erase path could repair static images, while preserving the native 64 KB path without fixing the AP firmware continued to corrupt large writes.

## Local Validation Of The Fix

A local AP firmware repair was tested on the affected panel.

The repair preserved the normal native 64 KB path and changed only the AP firmware behavior:

```text
BA44 timeout: 300 -> 1000
B4D0 forced-success removed
B4D0 now propagates BA44 status-poll result
```

Patch details for the tested F1.4 AP/AP1 payload:

```text
AP file offset 0xAA4A:
  old: 4F F4 96 70
  new: 40 F2 E8 30
  meaning: BA44 timeout 300 -> 1000

AP file offset 0xA534:
  old: 01 20
  new: 00 BF
  meaning: remove forced success after BA44 in B4D0
```

The same two changes were applied to `AP1`.

Patched AP/AP1 validation:

```text
Patched AP/AP1 SHA256:
FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737

Patched AP/AP1 CRC16 over 0x28..EOF:
0xFFFE
```

Result after flashing the repaired AP payload locally:

```text
Static custom image: full screen writes correctly
Custom GIF:          writes and plays correctly
```

This strongly validates the root-cause hypothesis: the native 64 KB erase path was timing out or finishing too late, and the AP firmware was hiding that failure from the caller.

## Additional Firmware-Updater Observation

The F1.4 firmware updater path also contains a separate suspicious erase-window issue.

F1.4 AP size:

```text
58328 bytes = 0xE3D8
AP is programmed from 0x1000 through 0xF3D7
```

Observed native updater erase range:

```text
I2CIAPChangeToErase12ByteMode:
start = 0x1000
end   = 0xEFFF
```

This means the F1.4 AP image extends 984 bytes beyond the fixed erase range:

```text
programmed tail: 0xF000..0xF3D7
bytes beyond fixed erase window: 984
```

F1.2 and F1.3 AP payloads fit inside the fixed `0x1000..0xEFFF` erase range. F1.4 does not.

This may explain why the official firmware updater can fail or behave inconsistently on repeated attempts. It is a separate issue from the custom-media 64 KB erase defect, but it is worth reviewing in the official updater implementation.

## Requested Vendor Fix

Please review and fix the LCD AP firmware's flash operation logic for this product line.

Recommended AP firmware changes:

1. Increase the WIP/status poll wait budget for 64 KB block erase operations to cover the actual worst-case erase time of the SPI NOR flash used by the LCD controller.
2. Do not overwrite the status-poll result in the 64 KB erase helper.
3. Propagate erase failure to the caller so the upload/update state machine can stop instead of continuing after an incomplete erase.
4. Review page-program failure handling as well, because the page-program helper also appears to call the same poll helper and then force success.
5. Review the firmware updater's F1.4 AP erase range. If AP is programmed through `0xF3D7`, the erase window should cover the sector containing that region.

The validated local fix that resolved the issue was:

```text
BA44 timeout: 300 -> 1000
B4D0: preserve/propagate BA44 result instead of forcing success
```

## Evidence Available

Public research repository:

```text
https://github.com/EKYavsil/aorus-lcd-panel-reverse-engineering
```

The repository intentionally does not redistribute GIGABYTE firmware or binaries.

## Responsible Disclosure Note

This report is intended to help GIGABYTE reproduce and fix the issue in an official firmware/software update. The local repair was used to validate the technical root cause on owned hardware. Public distribution should prefer an official vendor fix.
