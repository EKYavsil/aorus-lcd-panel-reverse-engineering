# Static Clean Apply Test Patch Plan

Date: 2026-05-27

Status: design only. No live deploy. No DLL swap. No payload change.

## Goal

Create a minimal, reversible, static-image-only test patch for `ucVga.dll`.

The patch would run a clean display-state commit sequence only after `GvLcdApi.SendImage(...)` returns true for static image upload. It must not touch GIF upload, `SendImage` internals, payload bytes, destination, `nType`, firmware, or reset paths.

## Target Method

Managed method:

`ucVga.UserControls.CombineUC.GCutImage/<>c__DisplayClass15_0::<OnClick_OK>b__0()`

This is the static/GIF image upload worker created by `GCutImage.OnClick_OK`.

Current important IL flow:

```text
IL_0131 load _GPUIndex
IL_013C load IMG_DATA
IL_013E load _device_led_Id_simple
IL_0148 call GvLcdApi.SendImage(int, IMG_DATA, int)
IL_014D brfalse.s IL_017A

IL_014F ServiceUtil.Run("AorusLcdService")
IL_0157 Dispatcher.Invoke(dialog success)
...

IL_017A ServiceUtil.Run("AorusLcdService")   // failure path
```

Patch insertion point:

Immediately after `IL_014D brfalse.s IL_017A`, before the success-path `ServiceUtil.Run("AorusLcdService")`.

Reason:

- `SendImage` has already succeeded.
- `AorusLcdService` is still stopped.
- Failure path remains unchanged.
- Dialog success flow remains unchanged.

## Static-Only Guard

`GCutImage` handles both static image and GIF. The patch must guard on the already-built `IMG_DATA.nType`.

Relevant current IL:

```text
IL_00B9 ldloc.0
IL_00BA load this.m_nMode
IL_00C5 ldc.i4.3
IL_00C6 beq.s static
IL_00C8 ldc.i4.0       // GIF
IL_00CB ldc.i4.1       // IMG/static
IL_00CC stfld IMG_DATA::nType
```

Static guard:

```csharp
if (data.nType == 1) {
    // run clean apply sequence
}
```

This prevents any GIF behavior change.

## Intended Static Sequence

Use only existing `ucVga.Api.GvLcdApi` methods. Do not emit raw I2C packets manually.

For static image mode:

```csharp
bool sendOk = GvLcdApi.SendImage(gpu, data, deviceLedIdSimple);
Log("SendImage", sendOk);

if (sendOk && data.nType == 1) {
    bool displayOk = GvLcdApi.SetDisplay(gpu, 0, 1);
    bool loopOk = GvLcdApi.SetLoop(gpu, new List<int> { 3 }, 1);
    bool mode1Ok = GvLcdApi.SetMode(gpu, 3);
    bool save1Ok = GvLcdApi.Save(gpu);
    Thread.Sleep(shortDelay);
    bool mode2Ok = GvLcdApi.SetMode(gpu, 3);
    bool save2Ok = GvLcdApi.Save(gpu);
}
```

Then continue the original success path:

```csharp
service.Run("AorusLcdService");
Dispatcher.Invoke(dialog success);
```

## Method To Opcode Mapping

| Step | Method call | Protocol |
|---|---|---|
| clear metric overlay | `GvLcdApi.SetDisplay(gpu, 0, 1)` | `E1 CB 55 AC 38 ...` |
| lock loop to static mode | `GvLcdApi.SetLoop(gpu, new List<int>{3}, 1)` | `F3 CB 55 AC 38 ...` |
| force static mode | `GvLcdApi.SetMode(gpu, 3)` | `E5 CB 55 AC 38 ...`, byte 5 = `04` |
| save | `GvLcdApi.Save(gpu)` | `AA CB 55 AC 38 ...` |
| force static mode again | `GvLcdApi.SetMode(gpu, 3)` | `E5 ...` |
| save again | `GvLcdApi.Save(gpu)` | `AA ...` |

`SetLoop` stores modes as `mode + 1`, so list `[3]` writes static mode as byte `04`, matching `SetMode(3)`.

## OpenLcd / E7 Decision

Public clean sequence begins with `E7 enable`.

Existing method:

`GvLcdApi.OpenLcd(gpu, true)` sends `E7 CB 55 AC 38 ...`, byte 5 = `01`.
