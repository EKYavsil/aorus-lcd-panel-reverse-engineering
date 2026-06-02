# Firmware Line Master Analysis

Date: 2026-06-01

Scope: offline/static analysis only. No updater execution, no live I2C, no firmware flash, no service control, no panel command, no live DLL deploy.

## Executive Summary

The LCD firmware update line is now mapped well enough to make a technical decision.

The updater is not a mysterious separate path. It is:

```text
Managed WPF updater EXE
  -> extracts embedded resources AP/AP1/config.db/DLLs
  -> reads config.db
  -> calls native GvLcdFwUpdate.dll exports
  -> GvLcdFwUpdate.dll uses GVDisplay.dll GvWriteI2C/GvReadI2C
  -> panel bootloader/IAP receives commands and AP bytes
```

The most important finding is a probable Gigabyte F1.4 updater bug:

```text
F1.4 AP payload is 58,328 bytes.
The native erase command erases only 0x1000..0xEFFF.
The native flash command programs 0x1000..0xF3D7.
So the last 984 bytes are programmed outside the fixed erase window.
```

This may explain why firmware runs can fail first and succeed later, and why firmware reinstall did not reliably reset the panel's custom-media behavior.

## Current Artifacts

Main verifier:

```text
<local-gif-investigation-dir>\verify_firmware_line_patch_candidates.py
```

Output:

```text
<local-gif-investigation-dir>\firmware_line_patch_candidates_20260601.json
```

Dry-run manifest generator:

```text
<local-gif-investigation-dir>\generate_firmware_dry_run_manifest.py
```

Dry-run manifest:

```text
<local-gif-investigation-dir>\firmware_dry_run_manifest_20260601.md
```

Earlier supporting reports:

```text
<local-gif-investigation-dir>\reports\AP_Firmware_Delivery_Line_Deep_Map_20260601.md
<local-gif-investigation-dir>\reports\AP_Firmware_Erase_Window_CrossVersion_20260601.md
<local-gif-investigation-dir>\reports\AP_64KB_Repair_Three_Option_Feasibility_20260601.md
<local-gif-investigation-dir>\reports\AP_Option1_Return_Propagation_Offline_Patch_Result_20260601.md
```

## Files Confirmed

| File | Size | SHA256 |
|---|---:|---|
| `Gv_lcd_fw_update_N50_ICE_F1.2.exe` | 9,156,056 | `7B08371C48FB7B5FDFD3ED17677720E0C5EF3561E1FF4D7002B276E70F5D0F41` |
| `GV-N5080AORUSM_ICE-16GD_LCD_F1.3.exe` | 11,908,056 | `BBFC72B8074F4700E2EA170D2FB27B5E6912AA5604944FB6357C71F8DA4A58E5` |
| `GV-N5080AORUSM_ICE-16GD_LCD_F1.4 (1).exe` | 11,911,264 | `3B9E29D7D945AFC52DBA051244EFC48AB69D660E34201B03A13BC08B62600DE3` |
| `AP` | 58,328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `AP1` | 58,328 | `DFDBB0BFCE3885C0D6438B6D9E07D06AFD62B156DEDF6B72FEA92762F6D6FB9C` |
| `GvLcdFwUpdate.dll` | 2,496,752 | `DE23086EDFD6EEBEDB5E97562CEF25AE41D44531F215FF23CA434DFDD63ECB70` |

Authenticode status:

- F1.4 updater EXE: valid Gigabyte signature.
- `GvLcdFwUpdate.dll`: valid Gigabyte signature.
- `GvDisplay.dll` / `GvDisplayA.dll`: valid Gigabyte signatures.

Any EXE/DLL binary patch will break the relevant Authenticode signature.

## Managed Updater Flow

Method:

```text
Gv_lcd_fw_update.MainWindow.<Flashing>b__4_0
```

Resource extraction:

```text
DisposeResource()
```

The updater extracts these embedded resources into `AppDomain.CurrentDomain.BaseDirectory`:

```text
AP
AP1
config.db
GvDisplayA.dll
GvDisplay.dll
GvIntelI2C.dll
GvLcdFwUpdate.dll
Newtonsoft.Json.dll
SQLite.Interop.dll
System.Data.SQLite.dll
msvcp140.dll
vcruntime140_1.dll
vcruntime140.dll
```

Important: extraction uses `File.Create`, so a loose staged `AP` beside the EXE is overwritten if the original EXE is run normally.

The managed flashing sequence:

```text
I2CInitial(IAP_CODE_SIZE)
Sleep 200 ms
I2CAPChangeToIAP(AP_ADDRESS = 0xC2)
Sleep 500 ms
I2CIAPChangeToErase12ByteMode(IAP_ADDRESS = 0x44)
if fail:
    I2CIAPChangeToErase12ByteMode(IAP_ADDRESS1 = 0x46)
    if success:
        active IAP address = 0x46
        delete AP
        copy AP1 -> AP
        expected size = FW_FileSize1
    else:
        fail "Flash fail,I2CIAPChangeToErase12ByteMode fail!"
Sleep 200 ms
check AP file size equals expected size
I2CIAPSetAPFlashTable(size)
Sleep 200 ms
I2CIAPSubmitCRCAP(active IAP address)
Sleep 200 ms
I2CIAPFlashAP12ByteMode(active IAP address)
Sleep 200 ms
I2CIAPChangeToAP(active IAP address)
```

This directly matches the observed error:

```text
Flash fail,I2CIAPChangeToErase12ByteMode fail!
```

## config.db Values

For F1.4:

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

Decoded:

| Field | Decimal | Hex | Meaning |
|---|---:|---:|---|
| `AP_ADDRESS` | 194 | `0xC2` | normal AP/control address |
| `IAP_ADDRESS` | 68 | `0x44` | first bootloader/IAP address |
| `IAP_ADDRESS1` | 70 | `0x46` | fallback bootloader/IAP address |
| `IAP_CODE_SIZE` | 4096 | `0x1000` | AP code flash start/base |
| `FW_FileSize` | 58328 | `0xE3D8` | AP payload length |

No AP SHA/MD5/signature field was found in `config.db`.

## Native DLL Exports

`GvLcdFwUpdate.dll` exports:

| Export | Role |
|---|---|
| `I2CInitial` | initializes GVDisplay path |
| `I2CAPChangeToIAP` | sends `0x8101` through AP/control address |
| `I2CIAPChangeToErase12ByteMode` | sends fixed erase/range command `0x8203` |
| `I2CIAPSetAPFlashTable` | loads loose `AP` file into memory |
| `I2CIAPSubmitCRCAP` | computes/submits CRC over AP data |
| `I2CIAPFlashAP12ByteMode` | sends `0x8204` setup and streams AP bytes |
| `I2CIAPChangeToAP` | sends `0x8102` to leave bootloader/IAP |
| `I2CReadFWVersion` | reads firmware version |

Transport imports:

```text
GVDisplay.dll!GvWriteI2C
GVDisplay.dll!GvReadI2C
```

So firmware updater is still going through Gigabyte's I2C display library.

## IAP Commands

### `I2CAPChangeToIAP`

```text
command = 0x8101
payload length = 4
helper = 0x4060
address = AP_ADDRESS = 0xC2
```

### `I2CIAPChangeToErase12ByteMode`

Native code:

```text
0x003603: mov eax, 0x8203
0x00360D: mov word ptr [rsp+0x24], 0x000C
0x003614: mov dword ptr [rsp+0x28], 0x00001000
0x00361C: mov dword ptr [rsp+0x2C], 0x0000EFFF
0x00362B: call 0x42B0
```

Meaning:

```text
command = 0x8203
length  = 12
start   = 0x1000
end     = 0xEFFF
```

This is fixed in the native DLL.

Verifier result:

```json
"erase_end_pattern_offsets": ["0x2A1C"],
"erase_cmd_pattern_offsets": ["0x2A03"]
```

Patch candidate for the native DLL only:

```text
old bytes: C7 44 24 2C FF EF 00 00   // end = 0xEFFF
new bytes: C7 44 24 2C FF FF 00 00   // end = 0xFFFF
```

This would cover the F1.4 tail, but it patches a signed native DLL and still requires a live firmware-flash path. It is not a recommendation to run.

### `I2CIAPSubmitCRCAP`

Native behavior:

```text
AP data is loaded from loose "AP"
CRC16 polynomial 0x1021
CRC input offset = 0x28
CRC input length = AP_size - 0x28
command = 0x8104
payload includes 0x1020, 0x1027, CRC-derived field, and AP_size - 0x28
helper = 0x4060
```

This strongly implies the bootloader checks an AP CRC, but the CRC is computed from the loaded AP data. Therefore a patched AP can be internally consistent if the native DLL computes CRC over the patched file at runtime.

### `I2CIAPFlashAP12ByteMode`

Setup phase:

```text
command = 0x8204
length  = 12
start   = 0x1000
end     = AP_size + 0x0FFF
helper  = 0x42B0
```

Native code:

```text
0x003C68: start = 0x1000
0x003C85: eax = AP_size + 0x0FFF
0x003C94: end = eax
```

Verifier result:

```json
"flash_dynamic_end_pattern_offsets": ["0x3068"]
```

Streaming phase:

```text
for each 8 AP bytes:
    copy 8 bytes
    compute CRC16 over those 8 bytes
    append 2-byte CRC
    send 10-byte payload with GvWriteI2C
```

## Cross-Version Erase Window Bug

| Package | AP size | Flash range | Fixed erase range | Overflow |
|---|---:|---|---|---:|
| F1.2 | `56,404 / 0xDC54` | `0x1000..0xEC53` | `0x1000..0xEFFF` | `0` |
| F1.3 | `56,720 / 0xDD90` | `0x1000..0xED8F` | `0x1000..0xEFFF` | `0` |
| F1.4 | `58,328 / 0xE3D8` | `0x1000..0xF3D7` | `0x1000..0xEFFF` | `984` |

This is a strong firmware-updater defect candidate.

Implications:

- F1.2/F1.3 fit inside the fixed erase range.
- F1.4 does not.
- F1.4 may program `0xF000..0xF3D7` without first erasing that region.
- NOR flash can program erased `1` bits to `0`, but cannot reliably change `0` back to `1` without erase.
- Repeated firmware attempts may behave differently depending on stale tail contents.

## AP Repair Candidates

### Option 1: Return propagation diagnostic

Location:

```text
AP function : FUN_0000B4D0
AP address  : 0x0000B534
file offset : 0x0000A534
old bytes   : 01 20    // movs r0,#1
new bytes   : 00 BF    // nop
```

Meaning:

```text
Before: 64 KB erase poll can fail, but AP forces success.
After : 64 KB erase poll failure propagates upward.
```

Status:

- Offline patched AP artifacts already exist.
- This is diagnostic/integrity-oriented.
- It may turn silent corruption into visible update/upload failure.
- It is not the best final GIF repair by itself.

### Option 2: Increase AP SPI poll timeout

Location:

```text
AP function : FUN_0000BA44
candidate   : timeout immediate around 0x0000BA4A
```

Assembler feasibility found:

```text
movw r0,#0x03e8  // 1000
movw r0,#0x0bb8  // 3000
movw r0,#0x1388  // 5000
```

Meaning:

```text
If 64 KB erase is accepted but AP's WIP wait is too short, increasing the internal AP poll window may help.
```

Limitation:

- If the 64 KB command is skipped/rejected/lost, timeout increase will not fix it.
- It affects shared poll behavior.

### Option 3: Replace 64 KB erase helper with 16x 4 KB sector erase

Location:

```text
AP function : FUN_0000B4D0
AP address  : 0x0000B4D0
file offset : 0x0000A4D0
current     : 2D E9 F0 41 04 46 ...
```

Concept:

```c
int FUN_0000B4D0(uint32_t address) {
    for (int i = 0; i < 16; i++) {
        if (FUN_0000B910(address + i * 0x1000) == 0) {
            return 0;
        }
    }
    return 1;
}
```

Assembler feasibility already produced a compact 30-byte Thumb implementation:

```text
30 B5 04 46 10 25 20 46 00 F0 1A FA 28 B1 04 F5
80 54 01 3D F7 D1 01 20 30 BD 00 20 30 BD
```

This preserves:

- host `F1[0x11] == 0x02`;
- GIF timing/finalization side effect;
- AP upload state machine expectations;
- reliable 4 KB sector erase behavior.

This is the best technical repair candidate for large GIFs, but it requires firmware patch delivery.

## Delivery Models

### Model A: Patch loose AP beside original EXE

Verdict: not reliable.

Reason:

```text
DisposeResource() overwrites AP/AP1 with embedded resources via File.Create.
```

### Model B: Patch embedded AP/AP1 inside updater EXE

Technically possible.

For F1.4, embedded AP blobs were found at:

```text
0x66E4
0x14AC1
```

Option 1 embedded AP patch offsets:

```text
0x66E4  + 0xA534 = 0x10C18
0x14AC1 + 0xA534 = 0x1EFF5
```

Risks:

- breaks updater EXE Authenticode signature;
- original EXE still embeds old native DLL with fixed erase end unless that DLL is also patched/replaced;
- running a modified vendor firmware updater is high-risk.

### Model C: Patch extracted/staged GvLcdFwUpdate.dll erase end

Technically simple:

```text
file offset 0x2A1C:
C7 44 24 2C FF EF 00 00
->
C7 44 24 2C FF FF 00 00
```

Effect:

```text
I2CIAPChangeToErase12ByteMode erases 0x1000..0xFFFF instead of 0x1000..0xEFFF.
```

Risks:

- breaks native DLL signature;
- still needs a controlled execution environment;
- not enough by itself to fix GIF custom-media erase path. It only fixes AP firmware updater erase coverage.

### Model D: Minimal controlled flasher harness

Preferred if a live firmware experiment is ever considered.

Concept:

```text
staged folder
  AP / AP1 patched or original
  config.db
  original or intentionally patched GvLcdFwUpdate.dll
  original GVDisplay.dll stack

custom harness:
  call same exports in same order
  no resource overwrite
  explicit manifest
  explicit file hashes
  explicit target address
  dry-run mode by default
```

Benefits:

- avoids patching a signed EXE;
- avoids `DisposeResource()` overwriting staged AP;
- lets us fix/verify F1.4 erase range explicitly;
- can produce a precise log and stop before live calls.

Risk remains high because it flashes panel AP firmware once live mode is enabled.

## Current Decision

For GIF, DLL-side host patching is now lower-confidence than AP-side repair.

Best technical target:

```text
Keep host/GCC F1[0x11] == 0x02.
Fix AP's implementation of the 64 KB erase path.
Prefer Option 3: internally perform 16x 4 KB sector erase in FUN_0000B4D0.
Also fix F1.4 updater erase window from 0xEFFF to 0xFFFF before flashing any F1.4-sized AP image.
```

But live firmware patching is not approved or ready yet.

## Remaining Unknowns

1. Does the bootloader validate only the CRC submitted by `I2CIAPSubmitCRCAP`, or does it also have an internal signature/hash?
2. Does `I2CIAPChangeToErase12ByteMode` treat the end address as inclusive or rounded internally?
3. Does expanding erase end to `0xFFFF` erase a safe sector only, or could it overlap bootloader/config storage?
4. Is the AP flash layout definitely:

```text
bootloader/IAP below 0x1000
AP application 0x1000..
```

5. Can a bad AP flash be recovered by rerunning official updater, or can it brick LCD IAP access?
6. Are `0x44` and `0x46` two physical IAP endpoints, two panel variants, or fallback aliases?

## Next Offline Steps

1. Extend the dry-run minimal flasher manifest generator:
   - no DLL loading;
   - no I2C;
   - emits exact call order, file hashes, AP size, erase range, flash range, and selected IAP address.

2. Build an offline package patch planner:
   - patch AP/AP1 in a staging copy only;
   - optionally patch staged `GvLcdFwUpdate.dll` erase end;
   - verify every byte offset and SHA before/after;
   - do not execute output.

3. Analyze bootloader/IAP command parser if present in the AP package or older updater resources.

4. Decide first live firmware experiment only after:
   - recovery path is written down;
   - original official updater can restore known-good state;
   - staged files and hashes are verified;
   - user explicitly approves live flashing.

## Bottom Line

The firmware line is now mostly understood:

- the updater uses Gigabyte I2C DLLs;
- AP/AP1 are embedded resources and overwritten on launch;
- F1.4 AP is larger than the updater's fixed erase window;
- native DLL computes AP CRC at runtime;
- the AP 64 KB erase helper is the most coherent root repair target;
- the safest future delivery model is a controlled minimal harness, not patching GCC DLLs and not blindly running a modified vendor EXE.


