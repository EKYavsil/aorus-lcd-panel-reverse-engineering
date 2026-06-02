# AP Firmware Erase Window Cross-Version Analysis

Date: 2026-06-01

Scope: offline/static only. No updater execution, no live I2C, no firmware flash.

## Result

The fixed IAP erase window used by `GvLcdFwUpdate.dll` is compatible with the older AP payloads in F1.2/F1.3, but not with the F1.4 AP payload.

`GvLcdFwUpdate.dll` is embedded byte-identically in all three examined packages, so this is not caused by a different native flasher in F1.4. The flasher logic stayed the same while the F1.4 AP payload grew beyond the older fixed erase range.

## Reproducible Verifier

Script:

`<local-gif-investigation-dir>\verify_ap_firmware_delivery_line.py`

Output:

`<local-gif-investigation-dir>\ap_firmware_delivery_verifier_output.json`

The script:

- extracts embedded `config` JSON from each updater EXE
- reads declared `FW_FileSize` / `FW_FileSize1`
- locates embedded AP/AP1 ARM vector-table payloads before the config resource
- calculates AP flash target range as `0x1000 .. 0x1000 + size - 1`
- compares it to the native fixed erase command range `0x1000 .. 0xEFFF`

## Version Table

| Package | Config FW_Ver | AP size | AP flash range | Native fixed erase range | Overflow past erase |
|---|---:|---:|---|---|---:|
| `Gv_lcd_fw_update_N50_ICE_F1.2.exe` | `1.1` | `56,404` / `0xDC54` | `0x1000..0xEC53` | `0x1000..0xEFFF` | `0` |
| `GV-N5080AORUSM_ICE-16GD_LCD_F1.3.exe` | `1.3` | `56,720` / `0xDD90` | `0x1000..0xED8F` | `0x1000..0xEFFF` | `0` |
| `GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe` | `1.4` | `58,328` / `0xE3D8` | `0x1000..0xF3D7` | `0x1000..0xEFFF` | `984` |

## Embedded AP/AP1 Payloads

| Package | AP/AP1 offsets | AP/AP1 SHA256 |
|---|---|---|
| F1.2 | `0x4DDC`, `0x12A35` | `8F8F295036A025421CF85BB590FC86620088DECAFB647F3488F7537153F4DAD2` |
| F1.3 | `0x66E4`, `0x14479` | `6B9AFD55FE7D190B6BECC794962337A23C9E586D117811ABFFAB5ACFD954D0BF` |
| F1.4 | `0x66E4`, `0x14AC1` | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |

Each package contains two identical AP blobs, matching `AP` and `AP1`.

## Native DLL Same Across Packages

The loose current `GvLcdFwUpdate.dll` blob was found byte-for-byte inside all packages:

| Package | Embedded `GvLcdFwUpdate.dll` offset |
|---|---:|
| F1.2 updater | `2,973,037` |
| F1.3 updater | `5,724,642` |
| F1.4 updater | `5,727,858` |

This means the native erase/program logic is not version-adapted for F1.4. The AP payload changed; the flasher DLL did not.

## Why This Matters

Native `I2CIAPChangeToErase12ByteMode` sends:

```text
command = 0x8203
length  = 0x000C
start   = 0x00001000
end     = 0x0000EFFF
```

Native `I2CIAPFlashAP12ByteMode` then prepares/programs using:

```text
start = 0x00001000
end   = AP_size + 0x0FFF
```

For F1.4 this becomes:

```text
erase: 0x1000..0xEFFF
flash: 0x1000..0xF3D7
tail not covered by fixed erase: 0xF000..0xF3D7
tail length: 984 bytes
```

Flash programming generally can change erased bits from `1` to `0`, but cannot reliably change `0` back to `1` without erasing the sector. If `0xF000..0xF3D7` contains stale data from a previous AP version or failed attempt, F1.4 may:

- fail during AP flashing,
- appear to succeed while leaving stale tail bytes,
- behave differently on a second run depending on what the first run already changed,
- recover more reliably with smaller F1.2/F1.3 payloads because those fit inside the fixed erase window.

This is a strong firmware-updater bug candidate.

## Caveat

The function name and command layout strongly indicate an erase/range setup command, but final proof would require live trace or AP-side reverse engineering of the IAP command parser. We are not doing live I2C here.

Still, the cross-version evidence is strong:

- same flasher DLL
- same fixed erase command
- F1.2/F1.3 fit
- F1.4 exceeds the window
- user observed firmware runs that sometimes fail first and succeed later

## Implication For AP Patch Options

Option 1 patch location:

```text
AP file offset 0xA534
AP target addr 0x1000 + 0xA534 = 0xB534
```

This lies inside the fixed erase window `0x1000..0xEFFF`, so the Option 1 diagnostic byte itself is not in the F1.4 unerased tail. However, flashing the full F1.4 AP still has the tail-range risk unless the delivery path also fixes/expands the erase range or uses a smaller AP image.

## Recommended Next Offline Step

Disassemble the managed updater around the native P/Invoke calls and design two offline-only delivery models:

1. Patched-resource EXE model:
   - patch both AP blobs inside the EXE
   - optionally patch the native DLL fixed erase end from `0xEFFF` to at least `0xFFFF`
   - report Authenticode breakage
   - do not run

2. Minimal harness model:
   - stage patched AP in an isolated folder
   - call original native exports in the official order
   - optionally use a patched native DLL only for the erase range
   - emit dry-run manifest first
   - do not call live I2C until separately approved

The cleaner engineering direction is now not just Option 1 return propagation. F1.4 likely also needs the IAP erase range corrected from `0xEFFF` to cover `AP_size + 0x0FFF`, probably sector-aligned to `0xFFFF`.



