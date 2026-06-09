# Page-Program Result Propagation Follow-up

Date: 2026-06-09

## Summary

After the original native 64 KB repair had completed many static-image and GIF tests successfully, one GIF upload caused the panel to become unresponsive. The failed upload produced repeated host-side transport errors:

```text
ucVga.I2CApi: SendData GvWriteI2C fail
ucVga.GvLcdApi: SendImage SendData Image Byte fail
```

The panel was recovered through the firmware update path. This event triggered a focused review of the AP firmware's 256-byte page-program helper.

## Confirmed Firmware Defect

The F1.4 AP firmware page-program helper calls the shared SPI status/WIP poll helper but then replaces its return value with success:

```text
FUN_0000B6CC: 256-byte page-program helper
FUN_0000BA44: SPI status/WIP poll helper
```

Relevant flow:

```asm
0x0000B74A  bl   FUN_0000BA44
0x0000B74E  movs r0,#1
```

The upload state machine checks the return value from `FUN_0000B6CC` before advancing its page address and remaining-page counters. The forced-success instruction therefore defeats an error-handling path that the caller explicitly expects to work.

This is consistent with an incomplete F1.4 refactor:

- F1.2 and F1.3 used void-style page-program helpers and advanced unconditionally.
- F1.4 introduced boolean-style helper returns and caller-side failure checks.
- The page-program helper retained the legacy forced-success tail.

## Minimal Repair

The existing repair was retained:

```text
AP offset 0xAA4A: BA44 timeout 300 -> 1000
AP offset 0xA534: B4D0 preserves the 64 KB erase poll result
```

One additional change was applied:

```text
AP file offset 0xA74E
AP address     0x0000B74E
old bytes      01 20
new bytes      00 BF
meaning        preserve the BA44 result instead of forcing page-program success
```

The same three changes were applied to `AP1`.

Validated output:

```text
AP/AP1 size:                  58328 bytes
AP/AP1 SHA256:                046CB6D001EA6787C789E78E8103450478EAE4FAA21F00A0E4219454F7DDD333
CRC16 over AP/AP1 0x28..EOF:  0xCB8A
```

## Live Firmware Update

The three-patch payload was flashed through the original signed `GvLcdFwUpdate.dll`. Every native update stage returned zero:

```text
I2CInitial
I2CAPChangeToIAP
I2CIAPChangeToErase12ByteMode
I2CIAPSetAPFlashTable
I2CIAPSubmitCRCAP
I2CIAPFlashAP12ByteMode
I2CIAPChangeToAP
```

Observed timeline:

```text
03:11:18-03:12:03  failed GIF attempt produced repeated GvWriteI2C/SendImage errors
04:01:05           three-patch firmware update started
04:03:45           AP flash and return-to-AP sequence completed successfully
04:10:21           the same source GIF was converted again
after 04:10:21     no new GCC transport or application crash entry was recorded
```

The panel recovered and a previously working GIF uploaded and played normally.

## Re-test Of The GIF That Triggered The Failure

The same GIF that had previously caused the panel failure was uploaded again after the firmware update.

Successful conversion artifacts:

```text
source GIF size:       2,302,671 bytes
staged animation.gif:  1,993,069 bytes
animation.bin payload: 5,310,534 bytes
frame count:           50
frame delays:          100 ms
```

The GIF uploaded and played successfully. No new `GvWriteI2C fail`, `SendImage ... fail`, .NET crash, or application crash entry was recorded during the successful attempt.

This result rules out a simple source-file-size threshold: the successful panel payload was larger than 5 MB.

## Interpretation And Evidence Limit

The result is consistent with the page-program repair preventing AP state from silently advancing after a page-program timeout. It also strengthens the broader diagnosis that the defect is in panel-side flash busy/result handling rather than GIF conversion or payload size.

However, the successful re-test is not by itself proof that the new instruction change caused the success. The firmware update also reset controller state, and the successful attempt did not produce a host-visible page-program timeout that could prove the repaired branch was exercised.

The defensible conclusion is:

```text
The forced-success page-program behavior is a confirmed firmware design defect.
The three-patch firmware completed the previously failing GIF successfully.
Direct causal attribution requires a captured page-program failure/recovery event or repeated controlled testing.
```

## Current Repair Set

```text
1. BA44: status-poll timeout 300 -> 1000
2. B4D0: preserve 64 KB erase poll result
3. B6CC: preserve 256-byte page-program poll result
```

This version is safer than the earlier two-patch build because neither native 64 KB erase nor page programming can silently convert the final BA44 timeout result into success.
