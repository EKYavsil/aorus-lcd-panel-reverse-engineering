# Tiny GIF Test Result - 2026-06-01

Scope: GIF-side investigation only. No repository commit/push. No live DLL changes were made by this report.

## Current Live DLL Check

Live file:

`C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll`

MD5:

`B383C6BE8AC34F35C6C729D548B83777`

This is the clean/original DLL, not the GIF state-prepare test DLL.

## Tests Observed

### Test 1 - GIF state-prepare patch + tiny GIF

Input payload according to the patch log:

- `nType=0`
- `size=1390`
- `frameCount=2`
- `delay=150`

Patch log:

```text
2026-06-01 01:17:12.293 PRE begin gpu=0 nType=0 size=1390 frameCount=2 delay=150
2026-06-01 01:17:12.302 PRE OpenLcd(true)=True
2026-06-01 01:17:12.308 PRE SetDisplay(0,1)=True
2026-06-01 01:17:12.316 PRE SetMode(5)=True
2026-06-01 01:17:12.323 PRE SetImageTpl(GIF,true)=True clr=0xFFFFFF imgPos=(0,320) dataPos=(146,64)
2026-06-01 01:17:12.323 PRE end
2026-06-01 01:17:16.807 POST begin sendImage=True gpu=0 nType=0 size=1390 frameCount=2 delay=150
2026-06-01 01:17:21.808 POST Sleep(5000) done
2026-06-01 01:17:21.816 POST SetDisplay(0,1)=True
2026-06-01 01:17:21.822 POST SetMode(5)=True
2026-06-01 01:17:21.823 POST end
```

Result reported by user:

- Panel corrupted/locked again.
- Firmware updater was needed to recover.

Conclusion:

The GIF state-prepare patch is unsafe for this panel state. Even with a tiny payload that naturally uses chunk mode `1`, the pre-upload GIF mode/template manipulation can still destabilize the panel.

Do not repeat this patch as-is.

### Test 2 - Original DLL, no script, same generated tiny GIF

User result:

- Panel did not lock.
- Panel stayed black.
- It did not alternate black/white.

GCC log around the original-DLL tiny upload shows no `SendImage SendData Image Byte fail` near `01:20-01:21`. The earlier write failure at `01:07:18` belongs to the large GIF/state-prepare test.

Current converted GCC assets:

```text
animation.gif length=1823 md5=EC2D33B5334D08C6C357505BD3B52A69
animation.ini length=289  md5=1EFC4867AD2E7860F3DAC08765B24708
animation.bin length=1390 md5=AE602675E0E0ACD596165C202D7C9A0A
```

`animation.ini`:

```ini
[Info]
Depth=<local-gif-investigation-dir>\test_gifs\tiny_bw_2frame_320x170_150ms.gif
Source=<local-gif-investigation-dir>\test_gifs\tiny_bw_2frame_320x170_150ms.gif
Count=2
Compress=RLE
Depth=0
Delay0=150
Delay1=150
```

Parsed `animation.bin`:

```text
length=1390
frame_count=2
natural_chunk_mode_expected=1 (<20480 bytes)
frame[0] end_offset=1381 width=320 height=170 format=3
frame[1] end_offset=1389 width=320 height=170 format=3
```

Offline RLE decode:

```text
frame 0: stream=1360 dec=108800 expected=108800 w=320 h=170 fmt=3 unique_count=4
frame 1: stream=8    dec=108800 expected=108800 w=320 h=170 fmt=3 unique_count=1 unique_sample=0x0000
```

Decoded frame files:

```text
<local-gif-investigation-dir>\offline_decoded_current_animation_bin\frame_00.png
<local-gif-investigation-dir>\offline_decoded_current_animation_bin\frame_01.png
```

Conclusion:

The tiny `animation.bin` is structurally valid and decodes offline to full-size frames. Frame 0 is white/light, frame 1 is black. Since the panel stayed black, the likely failure is not transport or payload size. It is more likely one of:

- GIF playback timer is not starting.
- Panel is selecting/sticking on one frame, likely the last/black frame.
- Mode/template/display state after upload is incomplete or wrong.
- A required GIF startup/reset state is missing before GIF upload.

## Important Public-Repo Alignment

Public driver notes do not describe the tested state-prepare patch as complete. Their safe GIF startup profile includes an extra reset stage:

```text
upload static reset image first
apply image mode/template after reset
set GIF mode before GIF upload
enable GIF template before GIF upload
normalized content height <= 150 px
frame delay >= 100 ms
frame budget <= 24 source frames
post-GIF-upload delay = 5 seconds
```

Their state machine is:

```text
Disabled/unknown
  -> enable LCD
  -> upload static reset image
  -> apply image mode/template
  -> clear metric overlay
  -> set GIF mode
  -> enable GIF template
  -> upload GIF payload
  -> wait 5 seconds
  -> clear metric overlay
  -> set GIF mode
  -> set metric overlay flags
  -> steady metric refresh
```

This is materially different from the attempted patch, which directly placed the panel into GIF mode/template before GIF upload without first pushing a static reset image and applying static mode/template.

## Updated Diagnosis

The original DLL + tiny GIF result weakens the theory that GIF is only broken because large payloads use `F1[0x11]=0x02`.

Known now:

- Tiny GIF naturally uses chunk mode `1`.
- Tiny GIF transport did not log write failures.
- Tiny GIF did not lock the panel with the original DLL.
- Tiny GIF decoded offline correctly.
- Panel still displayed only black.

Therefore, the GIF issue is likely at the GIF playback/display-state layer, not only at the erase granularity layer.

## Next Safe Direction

Do not test another live DLL immediately.

Recommended offline/read-only next steps:

1. Compare GCC's converted `animation.bin` against public-driver generated animation containers for the same frames.
2. Generate a non-black tiny GIF where every frame has visible non-black content, so a stuck last frame is obvious and not confused with panel black.
3. Design the next live test around the public safe sequence, not the previous state-prepare patch:
   - upload a known-good static reset image first,
   - apply static mode/template,
   - then set GIF mode/template,
   - then upload tiny GIF,
   - then post-delay and force GIF mode.
4. Keep the next live test tiny-payload only and keep original static fix untouched.

No production GIF fix is justified yet.


