# Clean Apply Sequence Gap Report

Date: 2026-05-27

Scope: read-only/offline Codex analysis. No live DLL deploy, no patch, no payload change, no custom GIF test.

## External Protocol Reference

Public reference: https://github.com/Maheidem/gigabyte-gpu-lcd/blob/main/docs/protocol.md

The public RTX 5090 AORUS MASTER ICE protocol notes match the old GCC `GvLcdApi` family, not `GvLcdExApi`. The documented stable sequence is:

1. `E7` enable LCD
2. `F2 01` start upload
3. `F1` upload header
4. payload pages
5. `F2 02` finish upload
6. `E1` clear metric overlay
7. `F3` lock carousel/loop to intended mode
8. `E5` force intended mode
9. `AA` save
10. `E5` force intended mode again
11. `AA` save again

The key claim to compare against our trace is that the post-upload `E1/F3/E5/AA/E5/AA` sequence is needed for stable output, and that the second `E5` matters.

## ucVga.dll Command Mapping

All required commands are present in `ucVga_clean.dll`:

| Protocol opcode | ucVga method | Static base packet | Notes |
|---|---|---|---|
| `E7` | `ucVga.Api.GvLcdApi.OpenLcd(int gpu, bool open)` | `E7 CB 55 AC 38 00 00 00 00 00 00 00` | byte 5 is `01` enable, `02` disable |
| `E1` | `ucVga.Api.GvLcdApi.SetDisplay(int gpu, uint flags, int interval)` | `E1 CB 55 AC 38 00 00 00 00 00 00 00 00 00 00` | eight overlay flags start at byte 5; `flags=0` clears overlay |
| `F3` | `ucVga.Api.GvLcdApi.SetLoop(int gpu, List<int> modes, int interval)` | `F3 CB 55 AC 38 ...` | byte 5 interval; mode list starts at byte 6 as `mode + 1` |
| `E5` | `ucVga.Api.GvLcdApi.SetMode(int gpu, int mode)` | `E5 CB 55 AC 38 00 00 00 00 00 00 00` | byte 5 is `mode + 1`; carousel mode 7 is special-cased to wire mode 9, byte 5 = 10 |
| `AA` | `ucVga.Api.GvLcdApi.Save(int gpu)` | `AA CB 55 AC 38 00 00 00 00 00 00 00` | already confirmed by runtime trace |

Source artifacts:

- `static_arrays_dump.txt` confirms raw packet templates for `E7`, `E1`, `F3`, `E5`, `AA`.
- `all_gvlcdapi_il.txt` confirms the corresponding method bodies and `I2CApi.SendData(..., slave=0xC2)`.
- `lcd_api_command_catalog.txt` confirms all these methods are in the normal `GvLcdApi` path.

## GCutImage Upload Path

Method:

`ucVga.UserControls.CombineUC.GCutImage/<>c__DisplayClass15_0::<OnClick_OK>b__0()`

Observed IL flow:

1. Build `IMG_DATA`.
2. `nType = 1` when `m_nMode == 3` static image, otherwise `nType = 0` GIF.
3. Stop `AorusLcdService`.
4. Start progress thread.
5. Call `GvLcdApi.SendImage(gpu, data, device_led_Id_simple)`.
6. If success: restart `AorusLcdService`.
7. Set dialog result true.
8. Re-enable UI controls.

Important absence:

- No `OpenLcd` / `E7` after or before upload.
- No `SetDisplay(0, interval)` / `E1` clear overlay.
- No `SetLoop([mode], interval)` / `F3` loop lock.
- No `SetMode(mode)` / `E5` force mode.
- No `Save()` / `AA` in the upload callback.
- No second `SetMode + Save`.

This matches our runtime logs: `SendImage` finishes successfully, then the only later `AA` appears when the user presses Apply in `LcdControl.OnClickApply`.

## Apply Button Path

Method:

`ucVga.Views.LcdControl::OnClickApply(...)`

Behavior:

- If selected mode is text mode 4, it opens `GTextUpload`; after dialog success it calls `Save`.
- Otherwise it shows loading and calls only `GvLcdApi.Save(gpu)`.
- It does not call `SetDisplay`, `SetLoop`, or `SetMode` before saving.
- It does not repeat `SetMode + Save`.

So for static image mode, the practical post-upload path is:

`SendImage -> restart AorusLcdService -> user Apply -> AA`

It is not:

`SendImage -> E1 -> F3 -> E5 -> AA -> E5 -> AA`

## Contrast: Text Upload

`GTextUpload/<>c__DisplayClass7_0::<OnClick_OK>b__0()` is different:

- It stops the LCD service.
- Calls `SendImage`.
- Then calls `GvLcdApi.SetMode(gpu, 4)`.
- Restarts service.
- Saves font settings.

Text upload still does not perform the full clean sequence, but it proves the vendor code already has a pattern of forcing mode after an upload in at least one upload path. `GCutImage` lacks the analogous `SetMode(3)` for static and `SetMode(5)` for GIF.

## Where E1/F3/E5 Are Used Elsewhere

These methods are not dead code. They are called by UI state-change flows:

- `SetMode` is called by display-mode dropdown changes, LCD switch handling, first-use init, and profile message handling.
- `SetDisplay` is called by display/carousel overlay UI changes, first-use init, and profile message handling.
- `SetLoop` is called from carousel/list UI flows, not from static/GIF upload.
- `Save` is called by Apply/LCD switch flows.
- `OpenLcd` is called when toggling the LCD switch.

This means GCC has all building blocks needed for the public clean sequence, but the static/GIF upload callback does not compose them after upload.

## Diagnosis

The new public protocol aligns strongly with the current local evidence:

- Static `pData` is correct.
- Static `SendImage` metadata is correct.
- Static upload writes `426/426` chunks successfully.
- `F2 finalize` succeeds.
- `SendImage` returns true.
- The panel can still show stale/broken output.

The missing piece is no longer the payload path. It is the missing post-upload display commit/state-clean sequence.

Most likely failure mode:

1. GCC writes the new static payload to `0x01300000`.
2. GCC does not clear metric overlay state (`E1 flags=0`).
3. GCC does not lock carousel/loop to the intended mode (`F3 [3]` for static, `[5]` for GIF).
4. GCC does not force intended mode immediately after upload (`E5 3` / `E5 5`).
5. GCC only sends `AA` later, and only when Apply is pressed.
6. Since the display engine state is stale, the panel may keep reading old visual mode/loop/overlay/slot state even though the uploaded data is valid.

This explains why:

- Apply appears ineffective: GCC's Apply is only `AA`, not the full display-state sequence.
- Firmware rollback/reinstall can temporarily help: firmware update likely reinitializes display state/cache/slot semantics.
- The issue can return over time: GCC keeps using an incomplete apply path after uploads.
