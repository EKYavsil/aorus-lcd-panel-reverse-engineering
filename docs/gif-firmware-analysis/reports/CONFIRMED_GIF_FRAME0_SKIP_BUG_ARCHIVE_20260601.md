# Confirmed GIF Frame-0 Skip Bug Archive

Date: 2026-06-01

Status: confirmed, archived, not the current active research direction.

This document preserves the GIF frame-index discovery so it is not lost while the investigation returns to the more important 64 KB erase/program path.

## Scope

The tests below used the normal GCC UI and a clean/original live DLL.

No DLL patch, service script, firmware updater, raw I2C command, state-prepare sequence, or AP reset flow was involved.

Live DLL before the test:

```text
C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll
MD5 = B383C6BE8AC34F35C6C729D548B83777
```

This matched the known clean/original DLL.

## Test Evidence

### Test 1

Payload:

```text
<local-gif-investigation-dir>\diagnostic_payloads\diag_2frame_frame1_white.gif
```

Frame design:

```text
frame 0 = black dummy
frame 1 = white visible frame with black box and "1 white visible" label
```

Observed panel result:

```text
The panel showed a static white image.
The top-right area contained a black box.
Inside the box, the text "1 white visible" appeared.
```

Interpretation:

```text
The panel displayed frame index 1.
```

### Test 2

Payload:

```text
<local-gif-investigation-dir>\diagnostic_payloads\diag_4frame_frame0_dummy_visible_1_2_3.gif
```

Frame design:

```text
frame 0 = black dummy
frame 1 = white
frame 2 = red
frame 3 = green
```

Observed panel result:

```text
The panel displayed a white -> red -> green loop.
```

Interpretation:

```text
Persistent custom GIF playback loops frames 1..N-1.
Frame 0 is skipped in the normal playback loop.
```

## Confirmed Behavior

The AP custom GIF playback path starts from frame index 1 and wraps back to frame index 1.

For a 2-frame GIF:

```text
frame_count = 2
display frame index 1
advance to 2
2 <= frame_count, so wrap to 1
visible loop = frame 1 only
```

For an N-frame GIF:

```text
visible loop = frames 1..N-1
skipped frame = frame 0
```

## Why This Matters

This explains the earlier tiny-GIF black-screen behavior:

```text
frame 0 = white/light frame
frame 1 = black frame
panel result = black
```

That result can be explained by AP playback selecting frame 1, not necessarily by failed GIF upload or failed compression.

## Relationship To Current Investigation

This is a real GIF bug, but it is not the current priority.

Current priority is the 64 KB erase/program path because:

1. Static image corruption was fixed by changing only the AP erase selector from 64 KB block erase to 4 KB sector erase.
2. The static pData was already proven correct offline.
3. Display/apply/loop sequencing was not enough to fix the lower black region.
4. Large GIFs still naturally enter the same risky 64 KB path.

The GIF issue currently appears layered:

| Layer | Status |
|---|---|
| Frame-index/playback bug | Confirmed: AP skips frame 0 |
| Large payload storage reliability | Still under investigation: 64 KB path is suspect |

## Deferred Fix Direction

If GIF playback is revisited later, the lowest-risk direction is likely host-side payload adjustment:

```text
Insert or duplicate a dummy frame at index 0 so AP playback from index 1 shows the intended first visible frame.
```

Potential variants:

| Variant | Expected effect | Risk |
|---|---|---|
| Duplicate first real frame into frame 0 and frame 1 | User sees first real frame even if frame 0 is skipped | Slight payload growth |
| Insert black dummy at frame 0 and shift real frames by +1 | AP skips dummy and starts at original first frame | Slight payload growth |
| Change frame count without shifting payload | Unsafe | Metadata/payload mismatch |

This fix is intentionally deferred. The active investigation is now the 64 KB erase/program failure.



