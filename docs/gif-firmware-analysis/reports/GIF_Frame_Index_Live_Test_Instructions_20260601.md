# GIF Frame Index Live Diagnostic Test

Date: 2026-06-01

Purpose: confirm whether AP custom GIF playback starts from frame index 1 and skips frame 0.

## Safety State

Preflight checked:

```text
Live ucVga.dll MD5 = B383C6BE8AC34F35C6C729D548B83777
Expected clean/original MD5 = B383C6BE8AC34F35C6C729D548B83777
```

This test uses:

- original DLL
- no patch
- no service script
- no firmware updater
- no raw I2C
- no state-prepare sequence
- only normal GCC custom GIF upload UI

## Test Files

Directory:

```text
<local-gif-investigation-dir>\diagnostic_payloads
```

### Test 1

```text
diag_2frame_frame1_white.gif
```

Content:

```text
frame 0 = black dummy
frame 1 = white visible
```

Expected if AP starts at frame 1:

```text
panel should show white / visible frame 1
```

### Test 2

```text
diag_4frame_frame0_dummy_visible_1_2_3.gif
```

Content:

```text
frame 0 = black dummy
frame 1 = white
frame 2 = red
frame 3 = green
```

Expected if AP loops frame indices 1..N-1:

```text
panel should animate white -> red -> green
frame 0 black dummy should not dominate
```

### Control

```text
control_2frame_frame1_black.gif
```

Content:

```text
frame 0 = white
frame 1 = black
```

Expected if AP starts at frame 1:

```text
panel should stay black
```

This mirrors the previous tiny GIF result and should be used only if Test 1 or Test 2 needs confirmation.

## Recommended Test Order

1. Upload `diag_2frame_frame1_white.gif` through normal GCC custom GIF UI.
2. Observe the panel after GCC finishes loading.
3. If it shows white/visible output, the frame-index hypothesis is strongly supported.
4. Then upload `diag_4frame_frame0_dummy_visible_1_2_3.gif`.
5. Observe whether it cycles white/red/green.
6. Do not use patch scripts during this test.

## Result Interpretation

| Result | Meaning |
|---|---|
| Test 1 shows white | AP starts at frame 1; previous black tiny GIF was likely frame-index behavior |
| Test 2 cycles white/red/green | AP loops frames 1..N-1 and skips frame 0 |
| Test 1 and Test 2 both stay black | playback state/fail latch/template issue still blocks GIF |
| panel locks or corrupts | stop GIF live testing and recover with known firmware path |

## Notes To Fill After Test

```text
Test 1 result:

Test 2 result:

Control result if used:

Panel recovery needed? yes/no

Observed GCC behavior:

Observed panel behavior:
```



