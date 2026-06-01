# Current Diagnosis - 2026-05-27

## Scope

This note continues the isolated Codex investigation. It does not modify GCC live files and does not propose a deployed fix.

## Clean State Check

- Live `ucVga.dll` MD5: `B383C6BE8AC34F35C6C729D548B83777`
- Live `Codex.SendImageTrace.Helper.dll`: absent
- `GCC.exe`: not running during check
- `AorusLcdService.exe`: not running during check

## Confirmed SendImage Facts

- Static image upload enters `GvLcdApi.SendImage` with `nType=IMG(1)`.
- Static destination is `0x01300000`.
- Static pData length is `108812`.
- Static pData offline render is correct; no bottom black area in the PC-side buffer.
- Static upload sends all `426/426` chunks successfully.
- `F2_FINALIZE` succeeds.
- `SendImage` returns `True`.
- No clear, erase, reset, slot wipe, or format command exists inside `SendImage`.
- `F2_FINALIZE` is upload finalization, not a proven display/apply or erase command.

## Caller/State Facts

- Static upload flow `GCutImage.<OnClick_OK>b__0` stops `AorusLcdService`, calls `SendImage`, then restarts `AorusLcdService`.
- Static upload flow does not call `SetImageTpl`, `SetDisplay`, or `SetMode` after `SendImage`.
- Apply button calls `GvLcdApi.Save`.
- `Save` sends `AA CB 55 AC 38 00 00 00 00 00 00 00`.
- `AA` is therefore a save/commit command, not part of `SendImage`.

## Local Profile State

`C:\Program Files\GIGABYTE\Control Center\GvProfile\418C_0_LcdSetting.dat` currently contains:

- `OnOff=true`
- `Mode=3`
- `Display=255`
- `DisplayInterval=4`
- `ImgConfig.nType=2`
- `ImgConfig.imgPos=0,320`
- `ImgConfig.dataPos=146,64`
- `ImgConfig.bEnable=true`

This matches the runtime `GetImageTpl` result:

- `tpl=nType=IMG(2)`
- `clr=0x004E4E4E`
- `imgPos=(0,320)`
- `dataPos=(146,64)`
- `bEnable=True`

## Command Map From ucVga

- `OpenLcd`: `E7 CB 55 AC 38 ...`
- `GetFWVersion`: `D6 CB 55 AC 38 ...`
- `GetMode`: `DE CB 55 AC 38 ...`
- `SetMode`: `E5 CB 55 AC 38 ...`
- `GetDisplay`: `DF CB 55 AC 38 ...`
- `SetDisplay`: `E1 CB 55 AC 38 ...`
- `SetLoop`: `F3 CB 55 AC 38 ...`
- `GetLoop`: `F4 CB 55 AC 38 ...`
- `GetImageTpl`: `EB/ED CB 55 AC 38 ...`
- `SetImageTpl`: `EA CB 55 AC 38 ...`
- `SendImage`: `F2 init`, `F1 metadata`, chunks, `F2 finalize`
- `Save`: `AA CB 55 AC 38 ...`
- `SetPCPowerOffMode`: `FA CB 55 AC 38 ...`

No public `GvLcdApi` method is named or shaped like erase, clear, wipe, format, or reset of the custom image slot.

## AorusLcdService Findings

- `AorusLcdService.exe` is a separate managed assembly.
- It does not reference `ucVga.dll`.
- It has its own copied `I2CApi`, `I2CApi4LcdEx`, and `VGADataApi`.
- On start it runs a VGA telemetry loop:
  - queries firmware version via `D6` or LCD Ex path,
  - sends telemetry every second.
- Non-Ex telemetry packet:
  - `E3 CB 55 AC 38 ...` over `0xC2`
- Ex telemetry packet:
  - starts `23 01 ...` over `I2CApi4LcdEx`, save port `0x76`, speed `100`.
- Service analysis did not show `SetImageTpl`, `SetDisplay`, `SetMode`, `Save`, or any clear/erase command.

## Native/Binary Pattern Scan

Focused binary scan results:

- `ucVga_clean.dll` contains the known `CB 55 AC 38`, `AA`, and `F2` command arrays.
- `AorusLcdService.exe` contains `CB 55 AC 38` only for its known `D6` firmware query.
- `GvDisplayA.dll` and `GvDisplay.dll` contain address constants such as `0x01300000` and `0x01320000`, but no direct `CB 55 AC 38` packet pattern in the scanned files.
- `Flash.dll`/`FBIOS.dll` did not reveal the LCD packet magic in the focused scan.

## Current Interpretation

The static image data path is now mostly cleared:

- PC-side converted payload is correct.
- SendImage metadata is sane for static image.
- Chunk count matches payload size.
- Transport reports success.

The remaining fault is most likely in one of these areas:

1. Panel-side custom slot/cache/flash state is stale or fragmented.
2. Panel-side display engine is reading an old or partially invalid custom slot despite successful write.
3. GCC upload flow omits a pre-upload clear/erase/reinitialize step for the custom slot.
4. Less likely: mode/template state is stale, because current `GetImageTpl` and local profile agree on custom image state.
