# GIF Frame Index Live Diagnostic Result

Date: 2026-06-01

Scope: normal GCC UI test with original/clean DLL. No DLL patch, no service script, no firmware updater, no raw I2C, no state-prepare sequence.

## Preflight

Live DLL before test:

```text
C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll
MD5 = B383C6BE8AC34F35C6C729D548B83777
```

This matched the known clean/original DLL.

## Test 1

File:

```text
<local-gif-investigation-dir>\diagnostic_payloads\diag_2frame_frame1_white.gif
```

Payload design:

```text
frame 0 = black dummy
frame 1 = white visible
```

Observed result:

```text
Panel showed a static white image.
Top-right area contained a black box.
Inside the box, the text "1 white visible" appeared.
```

Interpretation:

```text
AP displayed frame index 1.
The earlier tiny GIF black result was not necessarily upload failure; it matched frame 1 being black.
```

## Test 2

File:

```text
<local-gif-investigation-dir>\diagnostic_payloads\diag_4frame_frame0_dummy_visible_1_2_3.gif
```

Payload design:

```text
frame 0 = black dummy
frame 1 = white
frame 2 = red
frame 3 = green
```

Observed result:

```text
Panel displayed a white -> red -> green loop.
```

Interpretation:

```text
AP persistent custom GIF playback loops frames 1..N-1.
Frame 0 is skipped during the normal loop.
```

## Confirmed Finding

The frame-index hypothesis is confirmed by live behavior:

```text
AP custom GIF playback starts at frame index 1 and wraps back to 1.
```

This means host-side GIF payloads that put the first real frame at index 0 will have that frame skipped.

For two-frame GIFs:

```text
frame_count = 2
display frame index 1
advance to 2
2 <= frame_count -> wrap to 1
```

So only frame 1 is displayed.

For N-frame GIFs:

```text
displayed frames = 1..N-1
skipped frame = 0
```

## Relationship To Previous Failures

Previous tiny GIF:

```text
frame 0 = white/light
frame 1 = black
panel result = black
```

Now explained:

```text
AP selected frame 1, which was black.
```

This weakens the idea that tiny GIF failure was transport/RLE/upload failure.

It does not fully solve large GIF corruption yet, because large payloads can still hit the AP 64 KB erase/program problem. GIF now has two confirmed/likely layers:

1. Frame-index/playback behavior:
   - AP skips frame 0.
   - Host/GCC does not appear to insert a dummy frame.
2. Large-payload storage reliability:
   - `F1[0x11] == 0x02` uses the risky 64 KB erase path.
   - That path still matters for large GIFs.

## Likely Fix Direction For GIF

The lowest-risk GIF-side fix is no longer "change GIF F1[0x11] to 0x01" as the first move.

The first fix candidate should be payload-level and host-side:

```text
For custom GIF upload, insert/duplicate a dummy frame at index 0.
```

Possible variants:

| Variant | Behavior |
|---|---|
| Duplicate first real frame as frame 0 and frame 1 | AP starts at frame 1, user still sees first real frame |
| Insert black dummy as frame 0 | AP skips dummy and starts at original frame 0 shifted to index 1 |
| Increment frame count without payload shift | unsafe; likely metadata mismatch |

Best candidate:

```text
prepend a dummy/control frame at index 0 and shift all real frames by +1
```

But this increases payload size slightly and does not address large-GIF 64 KB erase risk by itself.

## Next Research Step

Before patching:

1. Locate the exact host converter point where `List<Bitmap>` is passed to `SaveZipData`.
2. Design a GIF-only, converter-level test patch that prepends one dummy frame before RLE compression.
3. Keep SendImage, F1, F2, target address, storage type, mode, destination, and payload transmission unchanged.
4. Test first with tiny/low-size GIFs.
5. Only after the frame-index fix is stable, revisit large-GIF 64 KB erase strategy.



