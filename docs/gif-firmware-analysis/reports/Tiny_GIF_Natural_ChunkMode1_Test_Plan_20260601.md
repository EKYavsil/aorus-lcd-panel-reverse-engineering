# Tiny GIF Natural ChunkMode1 Test Plan

Date: 2026-06-01

Scope: prepare the next test without changing GIF F1 metadata manually.

## Purpose

The previous GIF state-prepare test proved that normal state setup alone is not enough for the tested large GIF:

```text
payload size = 1,599,816 bytes
frame count  = 21
delay        = 60 ms
frame size   = 320x170
```

That large GIF naturally uses `F1 chunk_mode = 2`. The next test should check whether the smaller `chunk_mode = 1` path behaves better, but without forcing an invalid header on a large payload.

## Test GIFs Generated

Generated under:

```text
<local-gif-investigation-dir>\test_gifs\
```

Files:

```text
tiny_bw_2frame_320x170_150ms.gif
tiny_solid_3frame_320x170_150ms.gif
tiny_safe_3frame_320x170_150ms.gif
```

Recommended first test:

```text
<local-gif-investigation-dir>\test_gifs\tiny_bw_2frame_320x170_150ms.gif
```

Reason:

- smallest source GIF;
- 2 frames only;
- 150 ms delay;
- solid black/white frames, expected to RLE-compress very small;
- best chance for GCC-generated `animation.bin < 20480 bytes`;
- therefore best chance for GCC to naturally select `F1[0x11] == 1`.

## Existing Patch To Reuse

Reuse the existing GIF state-prepare test patch:

```text
<local-gif-investigation-dir>\run_gif_state_prepare_test_admin.ps1
```

This patch:

```text
before SendImage if GIF:
  OpenLcd(true)
  SetDisplay(0,1)
  SetMode(5)
  SetImageTpl(GIF,true)

after SendImage true if GIF:
  Sleep 5000 ms
  SetDisplay(0,1)
  SetMode(5)
```

It does not change:

```text
F1 chunk_mode
target
storage
nType
payload
SetLoop/F3
Save/AA
raw I2C
firmware
```

## Test Command

Run from elevated/admin PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File "<local-gif-investigation-dir>\run_gif_state_prepare_test_admin.ps1" -ConfirmDeploy
```

During GCC upload, choose:

```text
<local-gif-investigation-dir>\test_gifs\tiny_bw_2frame_320x170_150ms.gif
```

After the test, return to PowerShell and press Enter so the script restores the previous DLL.

## After Upload Check

Run this read-only checker after GCC creates `Assets\animation.bin`:

```powershell
powershell -ExecutionPolicy Bypass -File "<local-gif-investigation-dir>\check_gcc_animation_bin_after_tiny_test.ps1"
```

Expected good condition:

```text
animation.bin length < 20480
natural_chunk_mode_expected = 1
frame_count = 2
frame delay from animation.ini = 150
```

## Interpretation

If this tiny GIF works:

- the panel likely struggles with the large `chunk_mode=2` GIF path, large payload timing, or large animation finalization;
- next step would be finding practical safe GIF constraints or controlled chunk-mode handling.

If this tiny GIF still corrupts:

- the issue is not only payload size/chunk_mode=2;
- focus should shift to AP GIF playback state, `state+0x47`, frame table interpretation, or mode 5 custom animation gate.

If `animation.bin` is still >= 20480:

- this test did not actually reach natural `chunk_mode=1`;
- use an even simpler/smaller GIF or fewer frames.


