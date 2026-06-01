# SendImage Trace Analysis

Date: 2026-05-26

Status: historical passive-trace analysis, public-sanitized for repository release.

Scope: this report summarizes the SendImage tracing phase used to determine whether the static LCD corruption was caused by missing payload data, wrong chunking, bad SendImage destination selection, or a failed host-side transport call. No vendor binaries, traced DLLs, patched DLLs, raw dumps, trace logs, deploy scripts, restore scripts, or machine-specific paths are included in this repository.

## Purpose

The SendImage trace phase answered four practical questions:

1. Does `GvLcdApi.SendImage` receive a full static image payload?
2. Does it compute and send the expected 256-byte chunk count?
3. Does the static image branch target the expected custom-image destination?
4. Does the GIF path use the same branch and destination behavior as static images?

The main value of this phase was narrowing the bug away from simple host-side payload loss and toward panel/AP-side write, erase, or storage semantics.

## Static pData Observation

The passive trace captured full static image `pData` at `SendImage` entry for `nType=1`.

Captured static payload characteristics:

```text
pDataLen = 108812
uSize    = 108812
```

The exact 320x170 RGB565 frame size is:

```text
320 * 170 * 2 = 108800 bytes
```

The extra 12 bytes at the beginning appear to be an internal static-image header:

```text
01 00 0B A9 01 00 40 01 AA 00 01 00
```

Therefore, the static payload entering `SendImage` was the expected full-frame image data plus a small internal header. This made a simple upstream image-conversion or missing-payload explanation unlikely.

A retained helper script can render captured RGB565 static pData for local inspection:

```text
experiments/sendimage-tracing/render_static_pdata_rgb565.ps1
```

Raw pData dumps and rendered preview images are intentionally not included in the public repository.

## SendImage Signature

```text
System.Boolean ucVga.Api.GvLcdApi::SendImage(
    System.Int32 nGPUIndex,
    ucVga.Models.GvLcd.IMG_DATA data,
    System.Int32 device_led_Id_simple
)
```

`IMG_DATA` fields observed by IL/static analysis:

- `byte[] pData`
- `ulong uSize`
- `int nType`
- `int nCount`
- `int nDelay`
- `bool bTemplate`

`IMG_TYPE` enum values:

- `GIF = 0`
- `IMG = 1`
- `TXT = 2`
- `TPL = 3`

## IL Findings

`SendImage` does not know image width, height, stride, row pitch, or source file path. It consumes only `pData`, `uSize`, `nType`, `nCount`, `nDelay`, `bTemplate`, and `device_led_Id_simple`.

Width, height, stride, or source-file decoding problems would have to occur upstream of `SendImage`. They are not represented as local variables inside this method.

Branch behavior:

- `GIF=0`: no explicit branch body; falls through with default `V_5 = 0x00000000`, `V_6 = 2`.
- `IMG=1`: sets `V_5` to an image destination and `V_6 = 1`.
- `TXT=2`: sets `V_5` to a text destination and `V_6 = 1`.
- `TPL=3` or other values: falls through with default `V_5 = 0x00000000`, `V_6 = 2`.

Destination mapping:

- `IMG=1`, `device_led_Id_simple` in `{24,25,32,33,34}`: `V_5 = 0x01300000`
- `IMG=1`, other LED id: `V_5 = 0x01F25800`
- `TXT=2`, `device_led_Id_simple` in `{24,25,32,33,34}`: `V_5 = 0x01320000`
- `TXT=2`, other LED id: `V_5 = 0x01F00000`
- `GIF=0`: `V_5 = 0x00000000`

This confirms that static custom images and GIF uploads do not use the same SendImage branch.

## Protocol Sequence In SendImage

Observed SendImage sequence:

1. Sleep 2000 ms.
2. Send 12-byte `F2 ... 01` init packet through `I2CApi.SendData(..., size=256, slave=0xC2)`.
3. Compute `V_8 = (uSize / 256) + 1`, using integer division.
4. Build 19-byte `F1` metadata packet.
5. Send `F1`.
6. Sleep/progress before the payload loop.
7. Send `V_8` chunks, each exactly 256 bytes.
8. Zero-pad the final chunk if the real payload tail is shorter than 256 bytes.
9. Sleep 500 ms.
10. Send 12-byte `F2 ... 02` finalize packet.
11. Return `true` if all required sends succeed.

Important correction:

```text
F1 bytes 10-13 store V_8, a 256-byte chunk count, not a raw byte size.
```

For example:

```text
00 00 18 AF = 6319 chunks
6319 * 256 = 1,617,664 bytes implied transfer span
```

The field itself is still a chunk count, not a byte count.

No `AA` final packet appears inside this clean `GvLcdApi.SendImage` method. If an `AA CB 55 AC 38 ...` packet appears in I2C logs, it likely originates from a caller or another API such as mode, save, display, or post-upload apply handling.

No clear, erase, reset, or slot-wipe command appears inside `SendImage`. The visible bracketing commands in this method are `F2 ... 01` and `F2 ... 02`.

## Passive Trace Methodology

The trace instrumentation used during this phase was passive. It was designed to observe the upload path without changing the data path.

Trace design constraints:

- no payload mutation
- no metadata mutation
- no destination mutation
- no return-code mutation
- send result booleans preserved unchanged
- logging errors isolated from the host path
- static pData dumps timestamped during local investigation only

Temporary trace binaries, helper libraries, logs, dumps, and deploy/restore scripts are excluded from the public repository. Only the conclusions and safe helper source/scripts that remain useful for offline inspection are kept.

## Caller-Level Finding

`GCutImage.<OnClick_OK>b__0` does not directly call `SetMode`, `SetImageTpl`, `Save`, or `SetDisplay` after `SendImage`.

The observed static success path is:

1. `AorusLcdService.Stop(...)` before `SendImage`.
2. `GvLcdApi.SendImage(...)`.
3. If `SendImage` returns true: `AorusLcdService.Run(...)`.
4. UI dispatcher success callback.
5. Final UI dispatcher callback.

This means any post-upload apply, display, or slot command for static images is likely inside the restarted `AorusLcdService`, inside dispatcher callbacks, or in lower-level transport/API calls reached indirectly.

## Runtime Static Trace Summary

A static image upload was captured with these relevant values:

- `nType=1`, branch `IMG`
- `gpu=0`, `ledId=32`
- `pDataLen=108812`, `uSize=108812`
- `F2_INIT result=True`, raw `F2 CB 55 AC 38 01 00 00 00 00 00 00`
- `F1_METADATA result=True`, raw `F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00`
- decoded destination `0x01300000`
- decoded mode/storage type `1`
- decoded chunk count `0x01AA = 426`
- actual chunks sent `426`
- first sampled chunks succeeded
- last sampled chunks succeeded
- `F2_FINALIZE result=True`, raw `F2 CB 55 AC 38 02 00 00 00 00 00 00`
- no SendImage-level chunk write failure was observed in this successful trace

Interpretation at this phase:

```text
The static corruption was not explained by missing SendImage chunks,
wrong SendImage chunk count, missing static pData, or a failed visible
SendImage transport result.
```

Later AP firmware analysis connected the remaining failure shape to the static `F1[0x11]` erase-mode byte and the 64 KB block-erase path. See:

```text
docs/analysis/f1-header-to-erase-mode.md
```

## Runtime Comparison Table

| Scenario | nType | SendImage branch | destination / V_5 | chunk basis | known conclusion |
|---|---:|---|---|---|---|
| Static image | `1` | explicit `IMG` branch | `0x01300000` for LED id 32 | `V_8 = (uSize / 256) + 1`; captured `426` chunks | Payload and SendImage chunking were complete; later root cause moved to AP erase/write semantics. |
| GIF | `0` | default/fallthrough | `0x00000000` | `V_8 = (uSize / 256) + 1` | GIF is a separate path from static image. It should not inherit the static patch without dedicated runtime evidence. |

## Conclusions

1. Static image and GIF do not use the same SendImage branch.
2. Static `IMG=1` uses an explicit branch and a non-zero destination.
3. GIF `nType=0` falls through the default path with `V_5 = 0x00000000`.
4. Static black-bottom corruption cannot be explained by width, height, stride, or row-pitch locals inside `SendImage`, because the method has none.
5. Static `pDataLen` and `uSize` matched the expected full-frame payload plus a 12-byte internal header.
6. `F1` stores a 256-byte chunk count, not a raw byte length.
7. The captured static upload sent the expected `426` chunks.
8. `F2 finalize` appears to be an upload-end bracket, not a full storage verification step.
9. Upload-before-clear, erase, or reset commands are absent inside `SendImage` itself.
10. The SendImage phase ruled out the simplest host-side payload/chunking explanations and justified deeper AP firmware erase/write analysis.

## Public Repository Notes

This report intentionally excludes:

- vendor DLLs
- traced DLLs
- patched DLLs
- helper DLLs
- compiled binaries
- raw pData dumps
- trace logs
- machine-specific paths
- admin deploy or restore scripts

The current public static-image workaround is documented separately in the root-level patch builder files:

```text
PATCHER.md
build_final_static_sector_patch.cs
StaticSectorPatcher.csproj
```
