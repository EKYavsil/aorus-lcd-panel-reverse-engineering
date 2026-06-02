# N2A Native64 Timeout1000 Live Test Preflight - 2026-06-01

Scope: live-test preparation. The preflight and dry-run were completed. No live firmware call was made by this report creation.

## Candidate

```text
Name: N2a native64 timeout1000
Goal: keep native 0xD8 64KB erase path, increase AP internal BA44 poll timeout, and stop B4D0 from forcing success.
```

Patch bytes:

```text
AP file offset 0xAA4A: 4F F4 96 70 -> 40 F2 E8 30
AP address      0xBA4A: BA44 timeout 300 -> 1000

AP file offset 0xA534: 01 20 -> 00 BF
AP address      0xB534: B4D0 preserves BA44 poll result instead of forcing success
```

This is not the 16x4KB fallback. It keeps:

```text
F1[0x11] == 0x02
SPI erase opcode 0xD8
native 64KB erase unit 0x10000
GIF timing/finalization semantics
```

## Staging Directory

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601
```

## Staged File Verification

| File | Size | SHA256 / CRC |
|---|---:|---|
| `AP` | `58328` | `FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737` |
| `AP1` | `58328` | `FFD3ACBA17D8C338CDE7FBFAFF71DE979C7E6847CD7E577A93183BF8AE3EC737` |
| `AP/AP1 CRC16 0x28..EOF` | | `0xFFFE` |
| `GvLcdFwUpdate.dll` | `2496752` | `DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70` |

`GvLcdFwUpdate.dll` Authenticode status:

```text
Valid
Signer: GIGA-BYTE TECHNOLOGY CO., LTD.
```

## Why Original Signed GvLcdFwUpdate.dll Is Used

N2a changes only bytes inside the AP payload:

```text
0xA534
0xAA4A
```

Both are inside the native updater's original fixed AP erase window:

```text
0x1000..0xEFFF
```

Therefore the first N2a test does not need the modified `GvLcdFwUpdate_erase_end_FFFF.dll`.

This avoids the extra risk of a modified native flasher DLL with `HashMismatch`.

## Why The Original FWUpgrade.exe Is Not Used

The original managed updater extracts embedded resources through `DisposeResource()`.

That can overwrite loose patched files:

```text
AP
AP1
GvLcdFwUpdate.dll
```

The controlled harness avoids that by:

1. running from the staging directory;
2. using staged `AP` and `AP1`;
3. loading original signed `GvLcdFwUpdate.dll` from that same directory;
4. logging AP hash/CRC before any live call;
5. calling the same native export sequence as the updater.

## Harness

Project:

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\N2aFirmwareHarness.csproj
```

Published executable:

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\N2aFirmwareHarness.exe
```

Dry-run log:

```text
<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\n2a_dry_run.log
```

Dry-run result:

```text
PASS
No native firmware exports were called.
```

## Live Sequence

The harness live mode follows the official updater call order:

```text
I2CInitial(4096)
Sleep 200
I2CAPChangeToIAP(0xC2)
Sleep 500
I2CIAPChangeToErase12ByteMode(0x44)
if fail:
    I2CIAPChangeToErase12ByteMode(0x46)
    copy AP1 -> AP
I2CIAPSetAPFlashTable(58328)
Sleep 200
I2CIAPSubmitCRCAP(active IAP address)
Sleep 200
I2CIAPFlashAP12ByteMode(active IAP address)
I2CIAPChangeToAP(active IAP address)
```

If an exception happens after entering IAP mode, the harness attempts:

```text
I2CIAPChangeToAP(active IAP address)
```

in `finally`.

## Live Command

Run from PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File "<local-gif-investigation-dir>\n2a_native64_timeout1000_live_staging_20260601\run_live_n2a_native64_timeout1000_admin.ps1" -ConfirmN2a
```

This launches the harness as administrator and writes:

```text
n2a_live_launcher.log
n2a_live_flash.log
```

## Expected Interpretation

If AP flash succeeds and GIF/static original 64KB path improves:

```text
300-tick BA44 timeout was too short, and B4D0 forced-success was masking it.
```

If AP flash succeeds but behavior is unchanged:

```text
64KB failure is not solved by 1000 ticks alone.
Next native64 candidates are 1500/2000 or deeper B4D0 preparation analysis.
```

If AP flash fails cleanly:

```text
The patched AP may be rejected or the IAP line failed.
Recover with official firmware.
```

If panel becomes unstable:

```text
Stop immediately and recover with official firmware.
Do not stack more experimental patches.
```

## Recovery Assumption

This test relies on the already observed recovery path:

```text
official firmware updater can restore the panel after failed/unstable attempts
```

Official firmware recovery should be ready before live execution.



