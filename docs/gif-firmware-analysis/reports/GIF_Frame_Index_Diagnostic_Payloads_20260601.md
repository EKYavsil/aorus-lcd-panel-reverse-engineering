# GIF Frame Index Diagnostic Payloads

Date: 2026-06-01

Scope: offline artifact generation only. No live DLL deploy, no service control, no panel command, no firmware updater execution.

## Purpose

The AP F1.4 analysis shows that custom GIF playback likely starts at `state+0x11 == 1` and wraps back to `1`. The frame draw path confirms that `frame_index == 1` maps to the second RLE stream, not the first.

This explains the previous tiny GIF result:

```text
frame 0 = white/light
frame 1 = black
panel result = black
```

That result can happen if AP displays only frame 1.

The diagnostic payloads below are designed to test that hypothesis later, without using any state patch.

## Generated Files

Output directory:

```text
<local-gif-investigation-dir>\diagnostic_payloads
```

Generator:

```text
<local-gif-investigation-dir>\build_frame_index_diagnostic_payloads.py
```

The generator writes both:

- standard `.gif` files for possible future GCC upload tests
- matching `.bin` AP/GCC-style RLE containers for offline header/RLE validation
- `.json` metadata for each case

## Payload Summary

| Case | Standard GIF size | RLE bin size | MD5 | Frames | Natural F1[0x11] expectation |
|---|---:|---:|---|---:|---|
| `diag_2frame_frame1_white` | 3033 | 2678 | `E4C1D7763BE95F7D8DC92FC6BEBA0A8D` | 2 | `0x01` |
| `diag_4frame_frame0_dummy_visible_1_2_3` | 5812 | 5382 | `183E0FD6C0CFBE523F0BEDE8D052E414` | 4 | `0x01` |
| `control_2frame_frame1_black` | 2782 | 2692 | `262EC4FC8D13E686909926C6B1B6B930` | 2 | `0x01` |

All generated RLE bins are well below the 20 KB threshold, so GCC should naturally choose chunk/erase mode `F1[0x11] == 0x01` if it produces comparable payload sizes.

## Case Details

### `control_2frame_frame1_black`

This mirrors the previous tiny test shape:

```text
frame 0 = visible white
frame 1 = black
```

If AP starts playback at index 1 and wraps to 1:

```text
predicted stable output = black
```

This is the control case.

### `diag_2frame_frame1_white`

This reverses the control:

```text
frame 0 = black dummy
frame 1 = visible white
```

If AP starts playback at index 1 and wraps to 1:

```text
predicted stable output = white / visible frame 1
```

This is the smallest direct diagnostic for the frame-index hypothesis.

### `diag_4frame_frame0_dummy_visible_1_2_3`

This gives AP more than one visible post-dummy frame:

```text
frame 0 = black dummy
frame 1 = white
frame 2 = red
frame 3 = green
```

If AP loops indices `1..frame_count-1`:

```text
predicted stable animation = white -> red -> green
frame 0 remains skipped
```

This is a better diagnostic if two-frame GIFs have a separate edge case.

## Offline Validation

Every generated `.bin` has:

- width `320`
- height `170`
- format `3`
- RLE decode pixel count `54400`
- no RLE truncation
- no oversized RLE run

Frame metadata offsets match AP expectations:

```text
AP validator offset = frame_index * 10 + 6
frame 0 width/height offset = 6
frame 1 width/height offset = 16
frame 2 width/height offset = 26
frame 3 width/height offset = 36
```

The AP frame draw helper uses a 20-byte header read:

```text
frame_index == 1:
  read_address = base + 2
  reads frame 0 header + frame 1 header
  stream start = frame 0 end + 1
  stream end   = frame 1 end + 1
```

So the second frame stream is selected.

## Important Safety Note

These files are not a fix and were not deployed.

If used later, the safest live diagnostic would be:

```text
1. restore/confirm original DLL
2. no state-prepare patch
3. no F1 byte mutation
4. upload only diag_2frame_frame1_white.gif or diag_4frame_frame0_dummy_visible_1_2_3.gif through normal GCC UI
5. observe whether frame 1 / frames 1..N-1 appear
```

Expected interpretation:

| Observed result | Interpretation |
|---|---|
| `diag_2frame_frame1_white` shows white | AP frame index starts at 1; previous black tiny test was not upload failure |
| `diag_4frame...` animates white/red/green | AP loops `1..N-1`, skipping frame 0 |
| both stay black | GIF mode/template/fail-latch still blocks playback |
| panel locks/corrupts | avoid further live GIF tests; return to offline AP state/fail-latch analysis |

## Current Recommendation

Do not patch GIF yet.

The next research step should be one of:

1. Offline: map every write to `state+0x11`, `state+0x12`, `state+0x47`, and `state+0x66` across AP F1.4 and, if available, F1.2/F1.3.
2. Offline: inspect whether host/GCC ever intentionally inserts a dummy frame or expects frame 0 to be skipped.
3. Later, only if approved: run one original-DLL, payload-only diagnostic using the generated GIFs.



