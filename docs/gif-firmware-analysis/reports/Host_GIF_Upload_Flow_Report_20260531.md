# Host GIF Upload Flow Report

Date: 2026-05-31

Scope: offline IL review only. No live DLL deploy, no GCC service action, no panel command.

## Source Files Read

Read-only source artifacts:

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ucvga_clean_DDD_GCutImage_IL.txt
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ucvga_clean_DDD_SendImage_IL.txt
```

## Legacy GCutImage Upload Path

`GCutImage/<>c__DisplayClass15_0::<OnClick_OK>b__0()` builds `IMG_DATA` and then uploads it.

Important IL flow:

```text
IMG_DATA data = new IMG_DATA();
data.pData = fileBytes;
data.uSize = fileBytes.Length;

if (m_nMode == 3)
    data.nType = 1;   // static image
else
    data.nType = 0;   // GIF/animation

if (GifInfo exists) {
    data.nCount = info.nCount;
    data.nDelay = info.lstDelay[0];
}

ServiceUtil.Stop("AorusLcdService");
SendImage(_GPUIndex, data, _device_led_Id_simple);
ServiceUtil.Run("AorusLcdService");
```

Observed host behavior:

- It stops `AorusLcdService`.
- It starts a progress thread.
- It calls `GvLcdApi.SendImage`.
- If `SendImage` returns true, it restarts `AorusLcdService`.
- It does not call `SetMode`.
- It does not call `SetLoop`.
- It does not call `SetDisplay`.
- It does not call `Save`.
- It does not run the public clean apply sequence for GIF.

## SendImage GIF Metadata Path

`GvLcdApi.SendImage()` starts with:

```text
V_5 = 0
V_6 = 2
V_2 = 1 or 2 depending on size:
  if uSize >= 20480:
      V_2 = 2
      wait = 2000
  else:
      V_2 = 1
```

Then:

```text
if nType == 1:
    destination = 0x01300000 or 0x01F24000
    storage/type byte = 1

else if nType == 2:
    destination = 0x01320000 or 0x01F00000
    storage/type byte = 1

else:
    destination remains 0
    storage/type byte remains 2
```

So normal GIF (`nType == 0`) produces:

```text
F1 destination = 0x00000000
F1 storage/type = 0x02
F1 byte[0x11] = V_2
```

For any normal-sized GIF above 20 KB, `F1[0x11]` is `0x02`.

## Why This Matters

AP firmware treats GIF target `0` specially:

```c
if (target == 0) {
    slot = 1;
}

if ((current_panel_side == 1) && (target == 0)) {
    target = 0x01000000;
}
```

AP custom animation helper also uses:

```c
if (state+0x19 == 2) {
    *0x200001B0 = 0x01000000;
}
```

So host GIF upload is intentionally using a separate AP animation storage path, not the static `0x01300000` slot.

## Key Finding

The host GIF upload path has no explicit post-upload display-state commit.

Static image was fixed at the erase granularity level because its display slot is simple and directly tied to `0x01300000`.

GIF is different:

- The upload metadata targets AP's special animation slot (`target 0`, internally `0x01000000`).
- AP playback depends on state fields like `state+0x19`, `state+0x47`, `state+0x66`, `state+0x18`.
- The host path does not explicitly force the matching GIF/custom animation mode after upload.
- The host path does not save or lock loop/mode after GIF upload.

## Current Interpretation

The failed GIF patch is now explainable:

Changing GIF `F1[0x11]` from `0x02` to `0x01` was not equivalent to the static fix. In AP firmware, `0x02` also feeds the upload timing/finalization threshold:

```c
DAT_0000d168 = erase_count * 3000 + 3;
```

If that timing/commit threshold is missing or too short for the large GIF animation path, AP can report upload progress while the playback state remains stale or invalid.

## Next Best Offline Step

Disassemble and map the exact packet for `GvLcdApi.SetMode`, `SetLoop`, and `Save` for legacy `GvLcdApi` mode values, then identify the least risky GIF-only post-upload sequence to test later.

Do not mutate GIF payload or F1 byte yet.



