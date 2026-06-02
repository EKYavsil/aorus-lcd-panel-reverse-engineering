# Firmware Dry-Run Manifest

Date: 2026-06-01

Scope: offline manifest only. No DLL loading, no updater execution, no I2C, no firmware flash, no file modification.

## Inputs

| Name | Path | Size | SHA256 |
|---|---|---:|---|
| `AP` | `<local-firmware-package-dir>\AP` | 58328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `AP1` | `<local-firmware-package-dir>\AP1` | 58328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `config.db` | `<local-firmware-package-dir>\config.db` | 8192 | `E75E8D9044E281D344D159CDAFD839BF1DD05EE9C75BA64D1EBC2AC8B1331BF0` |
| `GvLcdFwUpdate.dll` | `<local-firmware-package-dir>\GvLcdFwUpdate.dll` | 2496752 | `DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70` |
| `GvDisplay.dll` | `<local-firmware-package-dir>\GvDisplay.dll` | 2763880 | `42BF2FC0855BF8736C4608F98A62C5919356BF74D651E130229E9DC8DD495FD0` |
| `GvDisplayA.dll` | `<local-firmware-package-dir>\GvDisplayA.dll` | 2744560 | `131B085FA7AB86EC86248C83CC1A865D2C4DE7F43ED5465B4003DC80FC33CEC1` |
| `GvIntelI2C.dll` | `<local-firmware-package-dir>\GvIntelI2C.dll` | 68200 | `EC9701D7D1BABA70E838F309FE6BA75B3BE8CDC99635477CE900820EC939BC93` |
| `F1.4 updater` | `<local-firmware-package-dir>\GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe` | 11911264 | `3B9E29D7D945AFC52DBA051244EFC48AB69D660E34201B03A13BC08B62600DE3` |

## Decoded Config

| Field | Value | Hex |
|---|---:|---:|
| `AP_ADDRESS` | 194 | `0xC2` |
| `IAP_ADDRESS` | 68 | `0x44` |
| `IAP_ADDRESS1` | 70 | `0x46` |
| `IAP_CODE_SIZE` | 4096 | `0x1000` |
| `AP_DataSize` | 256 | `0x100` |
| `I2C_Speed` | 100 | `0x64` |
| `FW_FileSize` | 58328 | `0xE3D8` |

## AP Image

- AP size: `58328` / `0xE3D8`
- AP target range: `0x1000..0xF3D7`
- CRC16-CCITT over AP[0x28..end]: `0x4560`
- CRC input length: `58288` / `0xE3B0`

## Erase/Flash Range Check

| Range | Start | End | Length |
|---|---:|---:|---:|
| Native fixed erase | `0x1000` | `0xEFFF` | `57344` |
| Native flash/program | `0x1000` | `0xF3D7` | `58328` |
| Recommended erase for F1.4 coverage | `0x1000` | `0xFFFF` | `61440` |

Overflow past native fixed erase: `984` bytes.

## Official Call Sequence

Dry-run sequence only:

```text
I2CInitial(0x1000)
Sleep(200)
I2CAPChangeToIAP(0xC2)
Sleep(500)
I2CIAPChangeToErase12ByteMode(0x44)
if fail: I2CIAPChangeToErase12ByteMode(0x46), then AP1 fallback
Sleep(200)
I2CIAPSetAPFlashTable(58328)
Sleep(200)
I2CIAPSubmitCRCAP(active_iap_address)
Sleep(200)
I2CIAPFlashAP12ByteMode(active_iap_address)
Sleep(200)
I2CIAPChangeToAP(active_iap_address)
```

## Native Commands

```text
0x8101: AP -> IAP request through 0xC2
0x8203: erase range setup, fixed 0x1000..0xEFFF in current DLL
0x8104: AP CRC submission
0x8204: flash/program setup, dynamic 0x1000..0xF3D7
0x8102: IAP -> AP return
```

## Safety Gates Before Any Live Use

- This manifest is not a flasher.
- A live harness must refuse to run unless every input hash matches expected values.
- A live harness must fix or account for the F1.4 erase-window mismatch before flashing F1.4-sized AP.
- A live harness must have explicit user confirmation immediately before the first I2C call.
- Recovery path must be documented before live AP firmware writes.



