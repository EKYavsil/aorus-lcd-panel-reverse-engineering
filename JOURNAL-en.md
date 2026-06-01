# AORUS LCD Panel Reverse Engineering Journal

This journal summarizes the investigation process into the static custom image corruption seen on the GIGABYTE AORUS RTX 5080 ICE LCD panel.

## Problem

A custom image could be uploaded, but only the upper region of the panel changed; the lower section would become black or remain stale over time. In the first stage, small black spots were visible, while in later uploads the black area grew, and in the final state approximately the bottom 40 percent of the image remained completely black.

Because the Default GIF, Chibi, and firmware-internal modes continued to work full screen, it became clear that the LCD panel was physically capable of drawing the entire area.

## Initial Observations

- The custom image upload flow was not completely failing; the upper section was being updated.
- The lower section remained black.
- The Default GIF and Chibi worked full screen.
- The panel was physically capable of displaying an image.

Therefore, the first important conclusion was:

> The LCD panel hardware was most likely not physically faulty.

## First Hypotheses Eliminated

In the early phase, the following possibilities were considered:

- Failure of the lower LCD rows
- LCD ribbon or controller failure
- Stale cache on the Windows or GCC side
- Corruption of panel display state files
- Custom image storage residue that could be cleared with a firmware reinstall

A significant portion of these hypotheses later weakened or was eliminated.

## Windows Cache and GCC State Investigation

`GigabyteDownloadAssistant`, `GigabyteUpdateService`, GIGABYTE components under System32, and GCC state files were examined.

The prominent file:

```text
418C_0_LcdSetting.dat
```

This file was seen to contain panel state information such as mode, display, image config, and GIF config. For a while, it was considered possible that the problem might be corrupted Windows-side state or cache rather than a corrupted panel. However, later the static upload data path and the panel flash behavior made this explanation insufficient on its own.

## ExApi and Reset Line

During firmware and DLL analysis, the following function families were found:

- `GvLcdExApi`
- `LcdControlEx`
- `GetFWVersionEx`
- `SetReset`

At this stage, it was thought that the real panel reset command might be on the ExApi line. However, the ExApi path was working through `0xEC`, the shifted equivalent of the `0x76` slave address, and the device was not responding on this line. Therefore, although ExApi theoretically existed, it ceased to be a practical solution path.

## Firmware Reinstall Theory

Users experiencing a similar problem were reported to have fixed the panel with a firmware reinstall. For this reason, LCD firmware versions were tested:

- F1.2
- F1.3
- F1.4

In the end, the firmware reinstall did not reliably erase the custom image area. The corrupted custom image continued to remain.

Important result:

> Firmware reinstall does not behave like a real factory reset for custom image storage.

## Display Reader and State Analysis

During the firmware reverse engineering process, the following address was identified as the start of the custom static image frame:

```text
0x0130000C
```

This region corresponded to 320x170 RGB565 frame data. On the reader side, reads were observed to be performed in blocks of approximately `0x500` bytes.

At this stage, firmware state fields were examined:

- `state+0x43`
- `state+0x48`
- `state+0x4C`
- `state+0x78`

For a while, the `state+0x43` field was thought to behave like a static image enable or validity gate. The hypothesis that it might be closing during upload and not reopening after finalize was tested. However, the fact that the upper part of the image still changed removed this from being the main root cause.

## Erase Timing Hypothesis

Many erase operations were found inside the AP firmware. On the GCC side, there was an approximately 5-second wait in the upload flow. For this reason, the hypothesis was formed that payload writing might begin before erase completion.

Tested variants:

- Adding an extra 30-second wait to the upload flow
- Increasing the existing 5-second wait to 10 seconds
- More natural retry and pacing attempts

Result:

- Panel corruption continued.
- The wait time was not confirmed as the root cause.
- In some variants, crashes and inconsistent state were observed.

The first concrete write error was seen in the following region:

```text
idx=312/426 GvWriteI2C fail
```

This roughly matched the region that remained black on the panel.

## 0x01310000 Boundary

As the investigation progressed, the following address stood out:

```text
0x01310000
```

This boundary corresponded to a point close to the division between the upper 60 percent and lower 40 percent of the image. The first interpretation was that there might be a special boundary or bank transition inside the firmware at this point. Later analyses showed that this was a symptom boundary, and that the real cause was related to the erase-mode selection inside the upload header.

## SPI Flash Findings

The following SPI flash commands were observed inside the firmware:

```text
0x06 Write Enable
0x05 Read Status
0x02 Page Program
0x20 Sector Erase
0xD8 Block Erase
0x03 Read
```

In contrast, status register write/protection maintenance commands were not clearly visible. This gave rise to the hypothesis that flash status/protection management might be missing or weak.

One of the critical firmware findings was seen in the page-program helper function:

- The SPI status poll result could be ignored in some paths.
- Even if the flash write actually failed, the upper-level function could return success.

This finding explained the following observation:

```text
426/426 chunk success
F2 finalize success
SendImage true
```

but the image on the panel could still remain corrupted.

## 4-Byte Address Mode

The `0xB7` command was found inside the firmware, and this was interpreted as 4-byte address mode for SPI flash. Because custom slot addresses such as `0x01300000` are above the 24-bit boundary, address mode, bank transitions, and flash write path behavior were examined especially closely.

## Final Discovery

After a long analysis process, the critical field inside the F1 header generated during static image upload was found:

```text
F1[0x11]
```

Original GCC/ucVga behavior:

```text
F1[0x11] = 0x02
```

Experimental patch:

```text
F1[0x11] = 0x01
```

Result:

- The panel was fixed full screen.
- The upper section updated correctly.
- The lower section updated correctly.
- The static custom image was written again as a full frame.

## Technical Result

Original static upload packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Working packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

This one-byte change makes the AP firmware side select a different erase/write path for static custom image upload.

Interpretation:

- The `0x02` value most likely selects the problematic 64 KB block erase path.
- The `0x01` value selects the 4 KB sector erase path.
- The bottom 40 percent corruption in the static image is consistent with the second erase/write region remaining stale.

## Final Verdict

Primary bug:

```text
GCC / ucVga static image upload header generation
```

Secondary firmware weakness:

```text
AP firmware can accept write path failures as success without sufficiently verifying them.
```

Practical solution:

```text
During static custom image upload, the F1[0x11] value should be 0x01 instead of 0x02.
```

As a result of this work, the problem was reduced from the level of "the bottom 40 percent remains black when a custom image is uploaded" to the level of "the value inside F1 upload header byte 0x11 selects the wrong erase/write path" and was verified with local tests.

## Remaining Work

The static custom image problem has been solved. The corruption on the custom GIF side remains a separate research topic; the GIF path was intentionally not changed by this patch.
