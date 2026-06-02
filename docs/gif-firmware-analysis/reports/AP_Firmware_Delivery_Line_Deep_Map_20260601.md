# AP Firmware Delivery Line Deep Map

Date: 2026-06-01

Scope: offline/static analysis only. No updater execution, no live I2C, no service control, no firmware flash, no live file changes.

## Executive Summary

The AORUS LCD firmware updater is a two-layer pipeline:

1. Managed WPF updater EXE extracts embedded resources (`AP`, `AP1`, `config.db`, DLLs) into its base directory.
2. Native `GvLcdFwUpdate.dll` reads the loose `AP` file from that base directory and streams it to the panel MCU through `GVDisplay.dll!GvWriteI2C/GvReadI2C`.

The updater does not use a separate hidden transport. It uses Gigabyte's display DLL I2C path.

Critical delivery finding:

- Patching loose `AP`/`AP1` beside the original EXE is not sufficient if the original EXE is launched normally, because `MainWindow.DisposeResource()` recreates/overwrites `AP`, `AP1`, and `config.db` from embedded resources using `File.Create`.
- The F1.4 EXE contains two full byte-identical AP payloads at offsets `0x66E4` and `0x14AC1` (decimal `26340` and `84673`), matching the loose `AP`/`AP1` SHA256.
- Therefore any future live AP-patch delivery must either:
  - use a controlled minimal flasher harness that calls the same exported DLL functions against staged patched `AP`, or
  - patch the EXE embedded resources / resource extraction behavior. This invalidates Authenticode and is higher risk.

## Files Confirmed

Firmware directory:

`<local-firmware-package-dir>`

| File | Size | SHA256 |
|---|---:|---|
| `AP` | 58,328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `AP1` | 58,328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `config.db` | 8,192 | `E75E8D9044E281D344D159CDAFD839BF1DD05EE9C75BA64D1EBC2AC8B1331BF0` |
| `GvLcdFwUpdate.dll` | 2,496,752 | `DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70` |
| `GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe` | 11,911,264 | `3B9E29D7D945AFC52DBA051244EFC48AB69D660E34201B03A13BC08B62600DE3` |

Signature status:

- `GvLcdFwUpdate.dll`: Valid, signed by GIGA-BYTE TECHNOLOGY CO., LTD.
- F1.2/F1.3/F1.4 updater EXEs: Valid signatures.
- Any embedded AP patch inside the EXE will invalidate the EXE signature.

## config.db

`config.db` contains one `config(info TEXT)` row. The JSON has a trailing comma, so strict JSON parsers reject it, but Gigabyte's updater parses it.

Decoded values:

```json
{
  "FW_Ver": "1.4",
  "FW_File": "AP",
  "FW_File1": "AP1",
  "FW_FileSize": "58328",
  "FW_FileSize1": "58328",
  "Model_SSID": "418C",
  "IAP_CODE_SIZE": "4096",
  "IAP_ADDRESS": "68",
  "IAP_ADDRESS1": "70",
  "AP_ADDRESS": "194",
  "AP_DataSize": "256",
  "I2C_Speed": "100"
}
```

Decimal interpretations:

| Field | Decimal | Hex | Meaning |
|---|---:|---:|---|
| `AP_ADDRESS` | 194 | `0xC2` | AP/control address used to enter IAP |
| `IAP_ADDRESS` | 68 | `0x44` | first IAP address candidate |
| `IAP_ADDRESS1` | 70 | `0x46` | fallback IAP address candidate |
| `IAP_CODE_SIZE` | 4096 | `0x1000` | AP code base/start |
| `FW_FileSize` | 58328 | `0xE3D8` | AP payload length |

There is no SHA/MD5/hash field in `config.db`; only file sizes are configured.

## Managed EXE Flow

Evidence: `f14_updater_managed_il.md`, method `Gv_lcd_fw_update.MainWindow.<Flashing>b__4_0`.

High-level flow:

1. `DisposeResource()`
   - Extracts embedded resources to `AppDomain.CurrentDomain.BaseDirectory`.
   - Files include `AP`, `AP1`, `config.db`, `GvDisplayA.dll`, `GvDisplay.dll`, `GvIntelI2C.dll`, `GvLcdFwUpdate.dll`, SQLite/Newtonsoft/runtime DLLs.
   - Uses `File.Create`, so existing loose files are overwritten.

2. Opens SQLite config.

3. Parses config fields:
   - `FW_File`, `FW_File1`
   - `FW_FileSize`, `FW_FileSize1`
   - `IAP_CODE_SIZE`
   - `IAP_ADDRESS`, `IAP_ADDRESS1`
   - `AP_ADDRESS`
   - `AP_DataSize`, `I2C_Speed`

4. Checks GPU SSID against `Model_SSID = 418C`.

5. Constructs initial firmware path:
   - `BaseDirectory + FW_File`, so normally `...\AP`.

6. Calls:
   - `I2CInitial(IAP_CODE_SIZE)`
   - Sleep 200 ms
   - `I2CAPChangeToIAP(AP_ADDRESS)`
   - Sleep 500 ms
   - `I2CIAPChangeToErase12ByteMode(IAP_ADDRESS)`

7. If first erase-address path fails:
   - tries `I2CIAPChangeToErase12ByteMode(IAP_ADDRESS1)`
   - if second succeeds, active IAP address becomes `IAP_ADDRESS1`
   - deletes the `AP` path
   - copies `AP1` to `AP`
   - switches expected size to `FW_FileSize1`

8. If both erase paths fail:
   - reports `Flash fail,I2CIAPChangeToErase12ByteMode fail!`

9. Size check:
   - `new FileInfo(BaseDirectory + active FW_File).Length == expected FW_FileSize`
   - failure reports `Flash fail,firmware size not match!`

10. Calls:
    - `I2CIAPSetAPFlashTable(size)`
    - Sleep 200 ms
    - `I2CIAPSubmitCRCAP(active IAP address)`
    - Sleep 200 ms
    - `I2CIAPFlashAP12ByteMode(active IAP address)`
    - Sleep 200 ms
    - `I2CIAPChangeToAP(active IAP address)`

Managed wrappers return `true` only when native return value is `0`.

## Native DLL Exports

Evidence: `exports_imports_gvlcdfwupdate.txt`, `gvlcdfwupdate_export_disasm.md`, `gvlcdfwupdate_focused_helpers_disasm.md`.

`GvLcdFwUpdate.dll` exports:

| Ord | Export | RVA | File Offset |
|---:|---|---:|---:|
| 1 | `I2CAPChangeToIAP` | `0x35B0` | `0x29B0` |
| 2 | `I2CIAPChangeToAP` | `0x3960` | `0x2D60` |
| 3 | `I2CIAPChangeToErase12ByteMode` | `0x35F0` | `0x29F0` |
| 4 | `I2CIAPFlashAP12ByteMode` | `0x3950` | `0x2D50` |
| 5 | `I2CIAPSetAPFlashTable` | `0x3660` | `0x2A60` |
| 6 | `I2CIAPSubmitCRCAP` | `0x3650` | `0x2A50` |
| 7 | `I2CInitial` | `0x3480` | `0x2880` |
| 8 | `I2CReadFWVersion` | `0x34A0` | `0x28A0` |

Imports of interest:

- `GVDisplay.dll!GvInitDispLib`
- `GVDisplay.dll!GvReadI2C`
- `GVDisplay.dll!GvWriteI2C`
- `GVDisplay.dll!GvFreeDispLib`
- `KERNEL32.dll!Sleep`
- file APIs such as `CreateFileW`, `ReadFile`, `GetFileSize`, `WriteFile`

## Native Command Helpers

### Helper `0x4060`: short command write + poll

Used by:

- `I2CAPChangeToIAP`
- `I2CIAPChangeToAP`
- `I2CIAPSubmitCRCAP`

Behavior:

- Takes a command struct.
- Writes `~command` into bytes `[2..3]`.
- If payload length at `[4]` is nonzero:
  - zeroes CRC field at `[6..7]`
  - computes CRC16 polynomial `0x1021`
  - stores CRC at `[6..7]`
- Sends command with `GvWriteI2C`.
- Sleeps 100 ms after write.
- Polls status with helper `0x3B30`, sleeping 1 ms between polls.
- Timeout limit: `0xBB8` = 3000 iterations.
- Native return:
  - `0` success
  - `1` timeout
  - `5` write failure

### Helper `0x42B0`: 12-byte mode write + poll

Used by:

- `I2CIAPChangeToErase12ByteMode`
- the setup stage of `I2CIAPFlashAP12ByteMode`

Behavior:

- Sends 4-byte command header first.
- Polls ready.
- Computes CRC over the payload beginning at command struct offset `+4`.
- Sends the payload block.
- Sleeps 500 ms.
- Polls ready again.
- Timeout limit: `0xBB8` = 3000 iterations.
- Native return:
  - `0` success
  - `1` timeout
  - `5` write failure

### Helper `0x3B30`: status poll

Behavior:

- Calls `GvReadI2C`.
- Read length is 4.
- Uses the target address byte from the current context.
- If the returned 4-byte value is nonzero, poll result is treated as ready/success.
- Otherwise it remains not-ready.

## Native Commands

### `I2CAPChangeToIAP(AP_ADDRESS)`

At native RVA `0x35B0`:

- command word: `0x8101`
- payload length byte: `0x04`
- helper: `0x4060`
- caller passes config `AP_ADDRESS = 0xC2`

Purpose: ask AP/control side to enter IAP mode.

### `I2CIAPChangeToAP(IAP_ADDRESS)`

At native RVA `0x3960`:

- command word: `0x8102`
- payload length byte: `0x00`
- helper: `0x4060`

Purpose: leave IAP and return to AP/application mode.

### `I2CIAPChangeToErase12ByteMode(IAP_ADDRESS)`

At native RVA `0x35F0`:

- command word: `0x8203`
- payload length word: `0x000C`
- start/address field: `0x00001000`
- end/address field: `0x0000EFFF`
- helper: `0x42B0`

Decoded payload intent:

```text
command = 0x8203
mode/range payload length = 12
erase/program start = 0x1000
erase/program end   = 0xEFFF
```

This is the function that emitted the user-observed failure:

`Flash fail,I2CIAPChangeToErase12ByteMode fail!`

### `I2CIAPSetAPFlashTable(size)`

At native RVA `0x3660`.

Key behavior:

- Uses `GetModuleFileNameW` and removes filename.
- Appends the hardcoded UTF-16 string `AP`.
- Reads the loose `AP` file.
- Stores AP data pointer globally at native RVA data slot `0x242108`.
- Stores AP size globally at native RVA data slot `0x242110`.

Important: this DLL does not reference `AP1` directly. The managed EXE handles AP1 fallback by copying `AP1` over `AP`, after which the DLL still reads `AP`.

### `I2CIAPSubmitCRCAP(IAP_ADDRESS)`

At native RVA `0x3650`, body helper at `0x3E90`.

Key behavior:

- Requires AP data already loaded by `I2CIAPSetAPFlashTable`.
- Computes CRC16 over AP data region starting at offset `0x28`, length `AP_size - 0x28`.
- Builds command `0x8104`.
- Payload includes constants:
  - `0x1020`
  - `0x1027`
  - CRC-derived field ORed with `0x1A0C0000`
- Uses helper `0x4060`.

Interpretation: AP firmware CRC submission/check command.

### `I2CIAPFlashAP12ByteMode(IAP_ADDRESS)`

At native RVA `0x3950`, body helper at `0x3C30`.

Setup phase:

- Requires AP data pointer and AP size from `I2CIAPSetAPFlashTable`.
- Builds command `0x8204`.
- Payload length: `0x000C`.
- Start: `0x00001000`.
- End: `AP_size + 0x0FFF`.
- Uses helper `0x42B0`.

For F1.4:

```text
AP_size = 58328 = 0xE3D8
start   = 0x1000
end     = 0xF3D7
```

Streaming phase:

- Iterates through AP data in 8-byte chunks.
- For each chunk:
  - copies 8 bytes from loaded AP data
  - computes CRC16 polynomial `0x1021`
  - appends 2-byte CRC
  - sends a 10-byte payload through `GvWriteI2C`
- Progress string: `Programming %.2f %%`
- Completion string: `Finish Flashing`

## Critical Range Mismatch

The F1.4 AP payload is larger than the fixed erase range used by `I2CIAPChangeToErase12ByteMode`.

```text
Erase12ByteMode fixed range:
  0x1000..0xEFFF
  length = 0xE000 = 57344 bytes

F1.4 AP stream range:
  0x1000..0xF3D7
  length = 0xE3D8 = 58328 bytes

Difference:
  58328 - 57344 = 984 bytes
```

This means the updater appears to erase `57,344` bytes but then streams `58,328` bytes.

For the current Option 1 AP patch this mismatch is not directly blocking, because the patch byte is at AP offset `0xA534`, which maps to target address:

```text
0x1000 + 0xA534 = 0xB534
```

That address lies inside the fixed erase range `0x1000..0xEFFF`.

But the mismatch is still a real updater design risk:

- F1.4 AP tail bytes past `0xEFFF` may be programmed without a matching erase.
- If those bytes differ from the existing panel AP image, flash programming can fail or silently leave stale bits.
- This may explain why repeated firmware runs sometimes fail first and succeed second, depending on previous flash contents.

## Embedded AP Evidence

The F1.4 EXE contains two full AP payload occurrences:

| Occurrence | Decimal Offset | Hex Offset | SHA256 |
|---:|---:|---:|---|
| 1 | 26340 | `0x66E4` | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| 2 | 84673 | `0x14AC1` | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |

Both contain original bytes at AP file offset `0xA534`:

```text
0xA534: 01 20
```

Option 1 patched AP changes only:

```text
0xA534: 01 20 -> 00 BF
```

## Option 1 Delivery Feasibility

Option 1 patch recap:

- AP function: `FUN_0000B4D0`
- AP address: `0xB534`
- AP file offset: `0xA534`
- Original bytes: `01 20` (`movs r0,#1`)
- Patched bytes: `00 BF` (`nop`)
- Effect: stop forcing success after the 64 KB erase poll; propagate the poll result.

Delivery feasibility:

1. Loose AP replacement before launching original F1.4 EXE:
   - Not reliable.
   - The EXE resource extraction uses `File.Create` and will overwrite `AP`/`AP1` with embedded originals.

2. Patch embedded AP/AP1 inside F1.4 EXE:
   - Technically feasible because both embedded AP payloads are same length and exact full-blob matches.
   - Must patch two offsets:
     - `0x66E4 + 0xA534 = 0x10C18`
     - `0x14AC1 + 0xA534 = 0x1EFF5`
   - Breaks Authenticode signature.
   - Higher-risk because it changes a signed updater executable.

3. Patch managed EXE resource extraction to preserve staged AP/AP1:
   - Technically feasible but also breaks signature.
   - Requires managed IL patching and more moving parts.

4. Controlled minimal flasher harness:
   - Preferred delivery design if live testing is ever considered.
   - It would use original signed `GvLcdFwUpdate.dll` and `GVDisplay.dll`.
   - It would stage patched `AP` in an isolated folder.
   - It would call the exported functions in the same order as the official updater.
   - It would avoid the EXE's resource overwrite behavior.
   - Still high risk because it flashes panel AP firmware.

## Current Best Understanding

The firmware updater/AP delivery line is now sufficiently mapped to explain several observed behaviors:

1. The updater can fail at `I2CIAPChangeToErase12ByteMode` before any AP flash starts.
2. It tries two IAP slave addresses: `0x44`, then `0x46`.
3. If fallback succeeds, it copies `AP1` over `AP` and continues.
4. The native flasher reads only a hardcoded `AP` filename.
5. Loose AP patching is overwritten by the EXE resource extraction.
6. F1.4 AP stream length exceeds the native fixed erase range by 984 bytes.
7. No AP payload hash/signature field was found in `config.db`; validation visible in managed code is size-based.
8. The signed EXE itself embeds the AP/AP1 payloads.

## Recommendation Before Any Live Attempt

Do not jump straight to flashing patched AP.

Recommended next offline step:

1. Build an offline-only verifier that:
   - reads F1.4 EXE
   - locates both embedded AP blobs
   - verifies SHA256 and target bytes
   - computes patched output EXE in a separate staging folder only
   - reports Authenticode status before/after without running it

2. In parallel, design a minimal flasher harness but keep it disabled/offline:
   - no auto-run
   - no live I2C
   - emits the exact official call sequence only as a dry-run manifest

3. Only after that choose between:
   - patched-resource updater route, or
   - custom harness route.

The safer engineering path is the custom harness route, because it avoids patching a signed EXE and avoids the EXE resource overwrite problem. The risk remains panel AP firmware flashing, so recovery assumptions must be explicit before any live use.



