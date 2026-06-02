# Public Repo Aligned GIF Apply Path Report

Date: 2026-05-31

Scope: offline analysis only. No live DLL deploy, no service action, no panel command, no GIF upload test.

## User-Provided Public Research File

Reviewed:

```text
<local-public-repo-notes>
```

The file is encoding-damaged, but the technical content is still readable. The important correction is that GIF should not inherit the static-image one-byte fix. Public reverse-engineered repos treat GIF as a separate path:

| Field | Static image | GIF / animation |
|---|---:|---:|
| SendImage nType | 1 | 0 |
| Target address | 0x01300000 | 0x00000000 |
| Storage byte | 1 | 2 |
| Display mode | 3 | 5 |
| Template type | 2 / IMG | 1 / GIF |
| Large payload F1 chunk mode | 2 when large | 2 when large |

Conclusion from the public research file:

- `F1[0x11] == 0x02` is not a GIF bug by itself.
- Public specs describe this byte as upload `chunk_mode`.
- For payloads >= 20480 bytes, public code chooses `chunk_mode = 2`.
- This matches our AP firmware finding where mode 2 drives 64 KB erase/prep behavior and a longer finalization/timing threshold.
- Therefore the failed GIF `0x02 -> 0x01` test was unsafe for the animation path and should not be repeated.

## Public Repo Alignment

Local public repos reviewed under:

```text
<local-gif-investigation-dir>\public_repos\
```

### Maheidem/gigabyte-gpu-lcd

Relevant files:

```text
public_repos\gigabyte-gpu-lcd\docs\protocol.md
public_repos\gigabyte-gpu-lcd\src\gigabyte_lcd\device.py
```

Important alignment:

- Static image: target `0x01300000`, storage `1`, mode `3`.
- GIF/RLE: target `0x00000000`, storage `2`, mode `5`.
- Header layout:

```text
F1 CB 55 AC 38
target_address u32 BE
storage_type u8
page_count u32 BE
frame_count u16 BE
delay_ms u8
chunk_mode u8
reserved 00
```

Clean apply sequence in that repo:

```text
E7 enable LCD
F2 01 start upload
F1 header
payload pages
F2 02 finish upload
E1 clear metric overlay
F3 lock loop to intended mode
E5 force intended mode
AA save
E5 force intended mode again
AA save again
```

This repo favors loop locking with `F3`.

### PrivateGER/Gigabyte-Aorus-LCD-Driver

Relevant files:

```text
public_repos\Gigabyte-Aorus-LCD-Driver\docs\protocol-spec.md
public_repos\Gigabyte-Aorus-LCD-Driver\src\protocol.rs
public_repos\Gigabyte-Aorus-LCD-Driver\src\service.rs
public_repos\Gigabyte-Aorus-LCD-Driver\tests\service.rs
```

Important alignment:

- RTX 5080 AORUS Master ICE profile is close to our target class.
- GIF is explicitly described as panel-sensitive.
- GIF target/storage/mode are the same as our findings: `0x00000000 / 2 / 5`.
- `TemplateKind::Gif = 1`, `TemplateKind::Image = 2`, `TemplateKind::Pet = 3`.
- `SetImageTpl` equivalent is opcode `0xEA`.
- A safer GIF flow is:

```text
enable LCD
optional static reset image
apply image mode/template
clear metric overlay
set GIF mode
enable GIF template
upload GIF
wait 5 seconds
clear metric overlay
set GIF mode
set metric overlay flags
steady metric refresh
```

Important conflict with Maheidem:

- PrivateGER tests assert that GCC-like GIF mode selection does not send an `F3` loop packet.
- Therefore an `F3` loop-lock GIF test is higher risk than a template/mode-only GIF test.

### SparkyTD/aorus-lcd-imger

Relevant files:

```text
public_repos\aorus-lcd-imger\README.md
public_repos\aorus-lcd-imger\imger\Program.cs
public_repos\aorus-lcd-imger\imger.extract\FrameInfo.cs
```

Important alignment:

- AORUS GIF payload is not a raw GIF.
- It is an animation container:

```text
offset 0          -> u16 frame_count
offset 2          -> 10 * frame_count FrameInfo records
offset 2+10*n     -> RLE frame data
```

FrameInfo contains:

```text
u32 end_offset
u16 width
u16 height
u16 image_format = 3
```

This supports treating GIF as a separate playback/state-machine path, not as a static image variant.

## Local ucVga IL Cross-Check

Offline IL artifacts reviewed from:

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\
```

New local extracts generated in the isolated GIF folder:

```text
<local-gif-investigation-dir>\exports\legacy_lcdcontrol_full_il_for_gif_followup.txt
<local-gif-investigation-dir>\exports\legacy_gvlcd_models_il_for_gif_followup.txt
```

### SetImageTpl Maps to Public 0xEA

`GvLcdApi.SetImageTpl(gpu, TPL_CFG)` builds an 18-byte packet:

```text
EA CB 55 AC 38
byte 5  = TPL_CFG.nType
byte 6  = R
byte 7  = G
byte 8  = B
bytes 9-12  = imgPos X/Y
bytes 13-16 = dataPos X/Y
byte 17 = bEnable ? 1 : 0
```

It sends over the legacy path:

```text
I2CApi.SendData(..., speed 256, slave 0xC2)
```

Default `TPL_CFG` constructor:

```text
clr     = 0xFFFFFF
nType   = 1
imgPos  = (0, 320)
dataPos = (146, 64)
bEnable = false
```

This exactly matches the public template packet structure.

### Local Template Type Values

From local IL and public alignment:

| TPL_CFG.nType | Meaning |
|---:|---|
| 1 | GIF |
| 2 | IMG |
| 3 | PET |

`LcdControl.ShowSetting()` confirms this:

```text
mode 3 -> _tplImg.nType = 2 -> GetImageTpl
mode 5 -> _tplGif.nType = 1 -> GetImageTpl
mode 6 -> _tplPet.nType = 3 -> GetImageTpl
```

### Local Display Mode Change Flow

`LcdControl.OnSelectIndexChange_DisplayMode()` does the normal state setup when the user changes display mode:

For mode 3 static image:

```text
SetMode(gpu, 3)
_tplImg.bEnable = btn_CarouselInfoSwitch.IsChecked
SetImageTpl(gpu, _tplImg)
if overlay switch is false:
    SetDisplay(gpu, 0, interval)
```

For mode 5 GIF:

```text
SetMode(gpu, 5)
_tplGif.bEnable = btn_CarouselInfoSwitch.IsChecked
SetImageTpl(gpu, _tplGif)
if overlay switch is false:
    SetDisplay(gpu, 0, interval)
```

This is highly important: legacy GCC already knows how to select GIF mode and enable GIF template, but the upload dialog does not reuse that sequence.

### Local Upload Dialog Flow Still Looks Incomplete

Existing host report remains valid:

```text
GCutImage upload:
  ServiceUtil.Stop("AorusLcdService")
  GvLcdApi.SendImage(...)
  ServiceUtil.Run("AorusLcdService")
```

It does not call:

```text
SetMode(5)
SetImageTpl(type GIF, enabled true)
SetDisplay(0, interval)
Save()
SetLoop()
```

So the public repo findings and the local IL agree on the most likely defect class:

> GIF payload upload can succeed while GIF playback/display state is stale, disabled, or not switched to the custom GIF template.

## Why the Static Fix Does Not Transfer to GIF

Static corruption was fixed by changing the static upload erase granularity byte:

```text
nType == 1
target = 0x01300000
storage = 1
F1[0x11] 0x02 -> 0x01
```

GIF differs:

```text
nType == 0
target = 0
storage = 2
mode = 5
template type = 1
large payload chunk mode = 2
```

AP firmware uses `F1[0x11] == 2` for large upload timing/finalization behavior. Public repos also choose mode 2 for large GIF payloads. Therefore the static fix should remain static-only.

## Current Best Hypothesis

The GIF bug is less likely to be a single F1 metadata byte and more likely to be a missing or wrongly ordered GIF apply/display-state sequence around upload.

Most likely missing pieces in legacy GCC upload dialog:

1. `SetMode(gpu, 5)` before GIF upload.
2. `SetImageTpl(gpu, GIF template enabled)` before GIF upload.
3. Optional `SetDisplay(gpu, 0, interval)` to clear metric overlay.
4. Post-upload settle delay, likely around 5 seconds for GIF.
5. Post-upload `SetDisplay(gpu, 0, interval)` and `SetMode(gpu, 5)`.
6. Possibly `Save(gpu)`, but public repos disagree on whether GIF needs save.
7. Avoid `F3 SetLoop` in the first GIF test because PrivateGER explicitly asserts GCC-like GIF mode selection does not send carousel loop packets.

## Recommended Research Direction

Do not run another live GIF patch yet.

Next offline work should determine the lowest-risk normal-API GIF sequence by comparing:

| Candidate | Risk | Reason |
|---|---|---|
| Pre-upload `SetMode(5)` + `SetImageTpl(GIF,true)` | Low | Matches GCC's own mode-change UI and PrivateGER |
| Post-upload 5s wait + `SetDisplay(0)` + `SetMode(5)` | Low/medium | Matches PrivateGER GIF settle behavior |
| Add `Save()` after GIF mode/template | Medium | Maheidem uses save; PrivateGER does not rely on it |
| Add `F3 SetLoop([5])` | Medium/high | Maheidem uses loop lock, but PrivateGER says GCC GIF selection does not send F3 |
| Modify GIF F1 chunk mode | High | Already failed; public protocol says mode 2 is expected for large payloads |
| Change GIF target/storage | High | Public repos confirm target 0 / storage 2 is intended |

## Candidate Test Design For Later Approval

If a live test is later approved, the first GIF patch should be normal-API only, payload-neutral, and no F1 changes:

```text
if data.nType == 0:
    OpenLcd(gpu, true)
    SetDisplay(gpu, 0, interval)
    SetMode(gpu, 5)
    SetImageTpl(gpu, tplGifEnabled)

SendImage(gpu, data, deviceLedIdSimple)

if SendImage true && data.nType == 0:
    Sleep 5000 ms
    SetDisplay(gpu, 0, interval)
    SetMode(gpu, 5)
```

First GIF test should not include:

```text
F1 byte mutation
target mutation
storage mutation
F3 SetLoop
raw I2C
firmware
SetReset
```

`Save()` should be a separate second variant only after the template/mode-only sequence is understood.

## Decision

The public-repo file changes the GIF direction clearly:

- The failed GIF one-byte patch was the wrong class of fix.
- GIF `target=0`, `storage=2`, `mode=5`, and large `chunk_mode=2` are expected.
- The stronger bug candidate is that `GCutImage` upload bypasses the same GIF mode/template state setup that `LcdControl.OnSelectIndexChange_DisplayMode()` performs.
- Continue offline toward a GIF-only normal-API apply sequence, beginning with `SetMode(5)` and `SetImageTpl(GIF,true)`, not another payload/header mutation.


