# Static Sector Erase Success - Final Notes

Date: 2026-05-31

Scope of this note: document exactly what was changed in the successful diagnostic patch, what the result proves, and what critical faults were found. This file is documentation only.

## Result

The static-only sector erase test succeeded.

User-observed result:

```text
Full screen static image was written correctly.
The previous lower black/stale region disappeared.
Repeated test also worked.
```

This is the first test that fixed the actual static image corruption symptom.

## Exact Change Made

Only one byte in the static image F1 metadata packet was changed.

Original static F1 packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Patched static F1 packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

Exact byte:

```text
F1[0x11]: 0x02 -> 0x01
```

Nothing else was changed:

- destination stayed `0x01300000`
- nType stayed `1`
- chunk count stayed `426`
- payload/pData stayed unchanged
- F2 start/finalize unchanged
- AA/Save unchanged
- display/apply sequence unchanged
- GIF branch not targeted
- no raw I2C
- no firmware change
- no AP firmware patch

## Guard Used

The patch applies only when all of these are true:

```text
nType == 1
local destination == 0x01300000
packet length >= 0x13
packet[0..4] == F1 CB 55 AC 38
packet destination == 0x01300000
packet[0x11] == 0x02
```

If any guard condition fails, the packet is left untouched.

## Log Evidence

Successful test log excerpt:

```text
PATCH_F1 guard=True nType=1 localDest=0x01300000 packetDest=0x01300000 len=19 originalEraseMode=0x02 rawBefore=F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
PATCH_F1_APPLIED nType=1 dest=0x01300000 eraseMode 0x02->0x01 expectedErase=0x1000 expectedEraseCount=27 rawAfter=F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
PACKET label=F1_METADATA result=True nType=1 len=19 raw=F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00 decoded=f1.dest=0x01300000 staticDest=True modeByte=0x01 chunks=426 eraseModeByte=0x01
PACKET label=F2_FINALIZE result=True nType=1 len=12 raw=F2 CB 55 AC 38 02 00 00 00 00 00 00 decoded=opcode=0xF2 magic=F2 CB 55 AC 38 ...
RETURN result=True nType=1
```

The live DLL was restored after the diagnostic test. The public repository does not include the live DLL, the patched DLL, or local restore artifacts.

## What The Byte Means

AP firmware F1 parser decodes `F1[0x11]` into the upload erase mode field.

AP-side logic:

```c
bVar30 = F1[0x11];

if (bVar30 == 3) {
    erase_granularity = 1;
}
else if (bVar30 == 2) {
    erase_granularity = 0x10000;
}
else {
    erase_granularity = 0x1000;
}
```

So:

```text
0x02 -> 64 KB block erase
0x01 -> 4 KB sector erase
```

## Why It Fixed The Symptom

Original GCC static upload:

```text
destination = 0x01300000
chunks = 426
chunk size = 0x100
total programmed size = 426 * 0x100 = 0x1AA00
erase mode = 0x02 -> 64 KB block erase
```

AP expected 64 KB block erase addresses:

```text
0x01300000
0x01310000
```

The failure boundary was visually and mathematically aligned with:

```text
0x01310000 - 0x0130000C = 65524 bytes
```

That is almost exactly the first 64 KB block boundary from the visible payload start.

Patched upload:

```text
erase mode = 0x01 -> 4 KB sector erase
erase count = 27
erase addresses = 0x01300000, 0x01301000, ..., 0x0131A000
```

The full image wrote correctly. That strongly proves the 64 KB block erase path was the problem for static custom image upload.

## Critical Bugs / Faults Found

### 1. GCC selects a risky erase mode for static image upload

GCC/ucVga emits:

```text
F1[0x11] = 0x02
```

For static image at `0x01300000`, this selects 64 KB block erase.

On this panel/card state, the second 64 KB block around `0x01310000` does not refresh correctly, causing the lower part of the image to stay black/stale.

### 2. AP firmware can report success despite storage corruption

Earlier AP firmware analysis showed:

- page program status propagation is weak
- F1.2/F1.3 helpers often do not propagate erase/program failure
- F1.4 improves erase return but page-program still ignores internal status-poll result
- F2 finalize does not validate full flash content/readback/CRC

This explains why host logs can show:

```text
F1 success
all chunks success
F2 finalize success
SendImage True
```

while the panel display is still partially corrupted.

### 3. Firmware reinstall does not reliably erase custom media storage

Firmware update attempts did not clear the broken custom image. Offline analysis indicated updater/IAP erase is AP firmware-oriented, not a guaranteed wipe of custom media slot:

```text
static media slot = 0x01300000
```

So firmware reinstall can reset panel behavior or AP code, but it is not a reliable custom slot storage wipe.

### 4. The issue is not payload conversion

Static pData was dumped and rendered offline correctly.

Therefore the previous lower black region was not caused by:

- bad source image conversion
- bad RGB565 packing
- missing lower image data
- wrong host-side buffer

The corruption was panel/storage erase-program behavior.

### 5. The issue is not display apply sequence

The public clean apply sequence was tested:

```text
OpenLcd
SendImage
SetDisplay
SetLoop
SetMode
Save
SetMode
Save
```

It did not fix the lower black region.

The successful fix was changing erase granularity before upload, not changing post-upload display state.

## Current Root-Cause Statement

Most precise current diagnosis:

```text
For static image uploads, GCC sends F1 erase mode 0x02, causing AP firmware to use 64 KB block erase for the custom static slot at 0x01300000.

The panel/card fails to correctly refresh the second 64 KB block around 0x01310000, but the host/AP success path does not catch the failure.

Changing only the static F1 erase mode from 0x02 to 0x01 makes AP use 4 KB sector erase instead, and the full static image writes correctly.
```
