# Host GIF Converter Dummy Frame Check

Date: 2026-06-01

Scope: offline host-code and public-code review only. No live DLL deploy, no service control, no panel command, no firmware updater execution.

## Question

AP custom GIF playback appears to start at frame index `1` and wrap back to `1`, which skips frame index `0` in the persistent animation loop.

The key host-side question:

```text
Does GCC intentionally put a dummy/control frame at index 0?
```

If yes, AP skipping frame 0 may be intended.

If no, AP skipping frame 0 is likely a firmware playback quirk/bug that host code does not compensate for.

## GCC / ucVga IL Evidence

The recovered `ImageMaker.ChangeGif(...)` IL shows normal GIF frame enumeration:

```text
Image.GetFrameCount(FrameDimension.Time)
GifInfo.nCount = frameCount
for each frame index:
    Image.SelectActiveFrame(time, index)
    GifInfo.lstDelay.Add(GetGifDelay(img, index))
    save/resize frame
SaveZipData(savePath, compress, bitmapList)
SaveGifInfo(gifInfo, ...)
```

Relevant IL hit summary:

```text
GetFrameCount(...)
GifInfo.nCount
SelectActiveFrame(..., index)
lstDelay.Add(...)
SaveZipData(..., List<Bitmap>)
```

No host-side evidence was found for:

- adding an extra dummy frame at index 0
- increasing `nCount` by one
- shifting user frames by one
- writing a special control frame before the first user frame

## GCC SaveZipData / RLE Evidence

`SaveZipData(...)` creates a compressor and compresses the `List<Bitmap>` produced by `ChangeGif`/`Mark`.

Relevant IL hit summary:

```text
new CompressFactory()
CreateCompress("RLE")
BitmapData.Stride
ICompress.compress(byte[])
ICompress.imageFormat
```

The visible evidence points to normal frame-by-frame compression, not dummy insertion.

## Captured `animation.bin` Evidence

Previously captured GCC-style payload:

```text
gcc_captured.bin
frame_count = 4
frame 0: valid 320x170 RLE
frame 1: valid 320x170 RLE
frame 2: valid 320x170 RLE
frame 3: valid 320x170 RLE
```

There is no structural marker suggesting frame 0 is a dummy/control record. It has normal width, height, format, and RLE data like every other frame.

## Public Repo Evidence

Reviewed public implementations:

- `aorus-lcd-imger`
- `gigabyte-gpu-lcd`
- `Gigabyte-Aorus-LCD-Driver`

All reviewed container builders use the normal layout:

```text
u16 frame_count = actual frame count
for frame in frames:
    write frame header
write frame streams in the same order
```

No public implementation reviewed here inserts a dummy frame at index 0 by default.

Examples:

```csharp
for (var index1 = 0; index1 < bmps.Count; ++index1) {
    stream = RLECompress.Compress(frame[index1]);
    write header for frame index1;
}
```

```rust
output.extend_from_slice(&(streams.len() as u16).to_le_bytes());
for stream in streams {
    write frame header;
}
for stream in streams {
    write stream;
}
```

```python
return frame_count + headers + streams
```

## Conclusion

Current evidence says:

```text
GCC/public host payloads treat frame 0 as the first real animation frame.
AP persistent custom-animation playback appears to start at frame 1.
```

That is a real mismatch unless another AP initialization path draws frame 0 once before the loop.

This explains why:

- a tiny two-frame GIF where frame 1 is black can appear permanently black;
- a normal GIF may look like it is missing/ignoring the first frame;
- a diagnostic GIF with frame 0 dummy and visible frame 1 is the safest next payload-only test.

## Relation To GIF 64 KB Erase

This does not replace the 64 KB erase finding.

GIF likely has two separate risks:

1. Large payloads: AP `F1[0x11] == 0x02` selects the same risky 64 KB erase/program path that broke static images.
2. All payload sizes: AP custom animation playback may skip frame 0 and can latch off via `state+0x47` if selected frame metadata is invalid/stale.

## Next Step

Best next offline step:

```text
Compare current/previous GCC animation.bin frame 0 and frame 1 previews.
If frame 1 is black/stale while frame 0 is visible, AP frame-index behavior explains black output.
```

Best next optional live diagnostic, only if approved:

```text
original DLL only
no state patch
no F1 mutation
upload diag_2frame_frame1_white.gif
```

Expected if hypothesis is right:

```text
panel shows white / visible frame 1 instead of black
```



