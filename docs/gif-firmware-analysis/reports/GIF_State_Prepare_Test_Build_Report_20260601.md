# GIF State Prepare Test Build Report

Date: 2026-06-01

Scope: reversible live-test package prepared, but not executed by Codex.

## Goal

Test the current strongest GIF hypothesis without touching GIF payload metadata:

> The GIF upload path may fail because GCC uploads the GIF without first selecting GIF mode and enabling the GIF template state.

This test creates the intended state before `SendImage`, then lets the original GIF upload run unchanged.

## Base DLL

The test DLL was built from the known-good static fix DLL:

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\ucVga_final_static_sector_patch.dll
MD5: 4052195ACE6E0196852CCF9A53BBFD96
```

So the static image fix is preserved inside this test DLL.

Current live DLL at build time:

```text
C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll
MD5: B383C6BE8AC34F35C6C729D548B83777
```

The deploy script backs up and restores whatever live DLL is present at test start.

## Produced Files

```text
<local-gif-investigation-dir>\ucVga_gif_state_prepare_test.dll
MD5: 16CA866ECB7903BC45DBAAF7A210A0AB

<local-gif-investigation-dir>\AorusGifStatePrepareTest.dll
MD5: FD44F3C84F885F26913173FC137473E9

<local-gif-investigation-dir>\run_gif_state_prepare_test_admin.ps1

<local-gif-investigation-dir>\emergency_restore_gif_state_prepare.ps1
```

## Patch Location

Patched method:

```text
ucVga.UserControls.CombineUC.GCutImage/<>c__DisplayClass15_0::<OnClick_OK>b__0()
```

This is the legacy upload worker where GCC builds `IMG_DATA`, stops `AorusLcdService`, and calls:

```text
GvLcdApi.SendImage(gpu, data, deviceLedIdSimple)
```

## Injected Flow

Before original `SendImage`:

```text
if data.nType == 0:
    OpenLcd(gpu, true)
    SetDisplay(gpu, 0, 1)
    SetMode(gpu, 5)
    SetImageTpl(gpu, GIF template enabled)
```

After original `SendImage` returns:

```text
if data.nType == 0 and SendImage == true:
    Sleep 5000 ms
    SetDisplay(gpu, 0, 1)
    SetMode(gpu, 5)
```

Original branch behavior is preserved:

```text
SendImage result still controls original success/failure branch.
AorusLcdService restart still happens through the original code path.
```

## Explicit Non-Changes

This test does not change:

```text
GIF F1 header
GIF target address
GIF storage byte
GIF chunk_mode byte
GIF pData payload
GIF nType
SendImage return value
F2 start/finalize
raw I2C
SetLoop/F3
Save/AA
SetReset
firmware
```

## Verification

Patched IL was dumped to:

```text
<local-gif-investigation-dir>\exports\gif_state_prepare_patched_gcutimage_worker_il.txt
```

Relevant verified calls:

```text
0158: call AorusGifStatePrepareTest::PreUpload(int, IMG_DATA)
0174: call GvLcdApi::SendImage(int, IMG_DATA, int)
0186: call AorusGifStatePrepareTest::PostUpload(bool, int, IMG_DATA)
```

PowerShell script syntax was checked. The deploy script also correctly refuses to run without:

```text
-ConfirmDeploy
```

## Test Command

Run from elevated/admin PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File "<local-gif-investigation-dir>\run_gif_state_prepare_test_admin.ps1" -ConfirmDeploy
```

During the test:

1. Script backs up the current live `ucVga.dll`.
2. Script deploys the test DLL and helper DLL.
3. Open GCC.
4. Upload one GIF only.
5. Return to PowerShell and press Enter.
6. Script restores the previous live DLL and removes the helper DLL.

Log path:

```text
<local-gif-investigation-dir>\gif_state_prepare_test.log
```

Emergency restore:

```powershell
powershell -ExecutionPolicy Bypass -File "<local-gif-investigation-dir>\emergency_restore_gif_state_prepare.ps1"
```

## If This Fails

Do not immediately rerun the old `0x02 -> 0x01` GIF patch.

The second hypothesis remains valid but should be designed separately:

> If the `chunk_mode = 2` GIF path itself is broken on this panel state, then a `chunk_mode = 1` GIF attempt might require much better state preparation and likely stricter payload constraints.

Possible second-stage constraints to research before building that DLL:

```text
small GIF payload if possible
reduced frame count
frame delay >= 100 ms
content height <= 150 px
pre-enabled GIF template
mode 5 before upload
post-upload settle
no target/storage mutation
no raw I2C
```

That second-stage DLL should be a separate patch, with separate logs and restore script.


