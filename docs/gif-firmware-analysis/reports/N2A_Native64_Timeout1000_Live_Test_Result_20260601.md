# N2A Native64 Timeout1000 Live Test Result - 2026-06-01

Scope: live AP firmware flash test result.

## Candidate Flashed

```text
N2a native64 timeout1000
```

Patch intent:

```text
Keep native 0xD8 64KB erase path.
Keep F1[0x11] == 0x02 / GIF timing semantics.
Increase BA44 WIP poll timeout from 300 to 1000.
Make B4D0 propagate BA44 result instead of forcing success.
```

Patch bytes in flashed AP:

```text
0xAA4A: 40 F2 E8 30
0xA534: 00 BF
```

This was not the 16x4KB fallback.

## Delivery Method

Controlled harness:

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\N2aFirmwareHarness.exe
```

Launcher:

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\run_live_n2a_native64_timeout1000_admin.ps1
```

Native flasher DLL:

```text
GvLcdFwUpdate.dll
SHA256 = DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70
Authenticode = Valid
```

Important: the test used the original signed `GvLcdFwUpdate.dll`; it did not use the modified erase-window DLL.

## AP Payload Verification

Logged before live firmware calls:

```text
AP.size=58328
AP.sha256=FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737
AP.crc16_0x28_eof=0xFFFE
AP.patch.BA44.bytes=40 F2 E8 30
AP.patch.B4D0.bytes=00 BF

AP1.size=58328
AP1.sha256=FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737
```

## Live Call Results

Log file:

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\n2a_live_flash.log
```

Call sequence:

| Step | Result |
|---|---:|
| `I2CInitial(4096)` | `0` |
| `I2CAPChangeToIAP(0xC2)` | `0` |
| `I2CIAPChangeToErase12ByteMode(0x44)` | `0` |
| `I2CIAPSetAPFlashTable(58328)` | `0` |
| `I2CIAPSubmitCRCAP(0x44)` | `0` |
| `I2CIAPFlashAP12ByteMode(0x44)` | `0` |
| `I2CIAPChangeToAP(0x44)` | `0` |

Fallback `0x46` was not used.

Harness exit code:

```text
0
```

## Timing

```text
LIVE_START: 22:01:25.154
FlashAP start after CRC sleep: 22:01:27.313
I2CIAPFlashAP12ByteMode returned: 22:04:05.943
ChangeToAP returned: 22:04:06.074
```

Approximate AP flash duration:

```text
158.6 seconds
```

## Immediate Verdict

Firmware delivery succeeded at the updater/IAP level.

The live test proved:

```text
The panel accepted an AP payload with BA44 timeout1000 and B4D0 return propagation.
The native flasher submitted the patched AP CRC successfully.
The AP flash sequence completed and returned to AP mode.
```

This does not yet prove the LCD media bug is fixed. The next required observation is panel behavior after reboot and after static/GIF upload tests.

## Next Observation Needed

After reboot:

1. Confirm panel is responsive and modes can change.
2. Test static image through the original/native 64KB path if possible.
3. Test custom GIF upload without host-side GIF state patches.
4. Record whether:
   - lower black region is gone;
   - GIF exits loading state;
   - GIF displays stale/white/black frames;
   - panel locks or remains responsive.

## Interpretation Matrix

If static original 64KB path and GIF both improve:

```text
Strong evidence that BA44 timeout 300 was too short and B4D0 forced-success masked the failure.
```

If static improves but GIF does not:

```text
Native 64KB erase was part of the issue, but GIF has an additional storage/finalization/programming issue.
```

If nothing improves but panel is stable:

```text
300->1000 was accepted but not enough, or timeout is not the main 0xD8 failure cause.
Next candidates: 1500/2000 or B4D0 preparation analysis.
```

If uploads fail cleanly instead of corrupting:

```text
B4D0 return propagation is working; the AP is now exposing native 64KB erase failure.
That is useful proof, but not yet the final fix.
```

If panel is unstable:

```text
Recover with official firmware. Do not stack more patches.
```



