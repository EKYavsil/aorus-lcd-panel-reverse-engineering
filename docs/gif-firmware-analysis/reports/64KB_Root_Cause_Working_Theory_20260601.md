# 64 KB Root-Cause Working Theory

Date: 2026-06-01

Scope: offline analysis and previously confirmed live observations only. No live DLL, service, firmware, panel, raw I2C, or upload test was performed for this note.

## Current Priority

The active investigation is:

```text
Why is F1[0x11] == 0x02, the AP 64 KB erase/program path, unreliable?
```

The confirmed GIF frame-0 skip bug is archived separately and is not being pursued right now.

## Important Correction To Older Notes

Some older intermediate notes suggested static upload was "consistent with the 4 KB sector path."

That interpretation is now superseded by the final successful static patch evidence.

The decisive evidence is:

```text
Original static F1 metadata used F1[0x11] = 0x02.
Changing only F1[0x11] from 0x02 to 0x01 fixed the full-screen static write.
```

Therefore, for the original broken static path:

```text
F1[0x11] = 0x02 -> AP selects 64 KB block erase.
```

For the fixed static path:

```text
F1[0x11] = 0x01 -> AP selects 4 KB sector erase.
```

## Confirmed Static Geometry

Static custom image:

```text
slot base             = 0x01300000
visible data start    ~= 0x0130000C
image payload size    = 320 * 170 * 2 = 108800 bytes = 0x1A900
host chunks           = 426
programmed bytes      = 426 * 256 = 109056 bytes = 0x1AA00
first 64 KB boundary  = 0x01310000
```

Boundary math:

```text
0x01310000 - 0x0130000C = 65524 bytes
65524 / 640 bytes per row ~= 102.38 rows
102.38 / 170 ~= 60.2%
```

This matches the visual failure:

```text
upper roughly 60% changes
lower roughly 40% stays black/stale
```

The display/read path recognizes the normal contiguous static slot at `0x0130000C`. There is no evidence that `0x01310000` is intended to be a display-layout boundary.

## AP F1[0x11] Decode

AP parser:

```c
bVar30 = pbVar7[0x11];
*DAT_00008208 = bVar30;

if (bVar30 == 3) {
  erase_unit = 1;
}
else if (bVar30 == 2) {
  erase_unit = 0x10000;
}
else {
  erase_unit = 0x1000;
}
```

So:

| F1[0x11] | AP erase unit |
|---:|---:|
| `0x02` | `0x10000`, 64 KB block erase |
| `0x01` | `0x1000`, 4 KB sector erase |
| other non-2/non-3 | `0x1000`, fallback sector erase |
| `0x03` | special `1` mode, not a normal media fix path |

## AP 64 KB vs 4 KB Helper Difference

64 KB helper:

```c
FUN_0000b4d0(address) {
  WREN();
  send 0xD8 + 4 address bytes;
  FUN_0000ba44();   // status poll
  return 1;         // poll result ignored
}
```

4 KB helper:

```c
FUN_0000b910(address) {
  WREN();
  send 0x20 + 4 address bytes;
  ok = FUN_0000ba44();  // status poll
  return ok != 0;
}
```

Page program helper:

```c
FUN_0000b6cc(buf, address, 0x100) {
  WREN();
  send 0x02 + 4 address bytes + 256 bytes payload;
  FUN_0000ba44();   // status poll
  return 1;         // poll result ignored
}
```

Status poll helper:

```c
FUN_0000ba44() {
  timeout = 300;
  send 0x05;        // Read Status Register
  while (status & 1) {
    tick();
    if (timeout < 1) return 0;
  }
  return 1;
}
```

Critical asymmetry:

```text
4 KB erase propagates AP status-poll failure.
64 KB erase hides AP status-poll failure.
Page program also hides AP status-poll failure.
```

## Why This Can Produce "Success" With Bad Panel Contents

F1.4 high-level upload loop checks helper return values:

```c
if (mode == 0x02) {
  ok = block_erase_64k(base + offset);
  if (ok == 0) return;
  offset += 0x10000;
}
else {
  ok = sector_erase_4k(base + offset);
  if (ok == 0) return;
  offset += 0x1000;
}

ok = page_program(base + offset, 0x100);
if (ok == 0) return;
offset += 0x100;
remaining_pages--;
```

But in the 64 KB path:

```text
block_erase_64k() can fail internally and still returns success.
page_program() can fail internally and still returns success.
```

Therefore:

```text
AP can advance offset/count state through all chunks.
F2 finalize can appear successful.
GCC can see SendImage=True.
Panel flash can still contain stale/black bytes after the 64 KB boundary.
```

This exactly matches the observed static behavior and the fact that "retry after true" was not trustworthy.

## Why Firmware Reinstall Did Not Necessarily Fix It

Firmware update/reinstall appears to restore AP firmware/runtime state but does not reliably wipe the custom media storage area.

Relevant observed updater error:

```text
Flash fail,I2CIAPChangeToErase12ByteMode fail!
```

This supports a broader pattern:

```text
The firmware/IAP erase path itself can fail before entering erase mode.
Even when firmware update later succeeds, it may not erase or verify the custom media slot.
```

So firmware reinstall can "wake up" or reinitialize display state without proving the custom slot was cleaned.

## Why A Large GIF Is Harder Than Static

Static was easy to fix because:

```text
F1[0x11] changed from 0x02 to 0x01.
No other static AP state seemed to depend on the 0x02 branch.
```

GIF is different because the same byte also affects a timing/state field:

```c
if (bVar30 == 0) {
  *puVar23 = 1;
  *pcVar11 = 0;
}
else if (bVar30 == 2) {
  *DAT_00008248 = erase_count * 3000 + 3;
}
```

This means:

```text
For GIF, 0x02 is both the risky 64 KB erase selector and a long-upload timing/finalization selector.
```

A naive GIF `0x02 -> 0x01` patch changes both:

1. erase granularity,
2. AP timing/state behavior.

That likely explains why earlier GIF 4 KB-path experiments destabilized the panel even though the same single-byte idea fixed static.

## Can A Big GIF Be Sent Through 4 KB Instead Of 64 KB?

Current answer:

```text
Possibly, but not safely by only changing F1[0x11] from 0x02 to 0x01.
```

Known barriers:

| Barrier | Reason |
|---|---|
| F1 byte coupling | Same byte controls erase unit and GIF timing/state |
| Large payload lifetime | GIF upload spans much more data and more AP state transitions than static |
| Target/storage path differs | GIF target=0/storage=2 path is not identical to static destination=0x01300000 |
| No readback verification | Host cannot currently prove all pages committed |
| Frame-0 bug is separate | Even a storage fix will not address AP skipping GIF frame 0 |

Potential offline design directions:

| Direction | Risk |
|---|---|
| Find another AP field/command that sets `DAT_00008248` while using 4 KB erase | Best theoretical path, not found yet |
| Keep `F1[0x11]=0x02` for timing but force erase helper to behave like 4 KB | Would require AP firmware patch or unknown protocol path |
| Split GIF into smaller uploads that naturally use 4 KB path | No evidence AP supports append/multipart same-object GIF safely |
| Force only small/optimized GIFs under GCC's natural 4 KB threshold | Practical workaround, not general fix |
| Patch host converter plus static-style 4 KB path | Still needs GIF state/timing solution |

## Strongest Current Root-Cause Statement

```text
GCC selects AP erase mode 0x02 for media payloads large enough to use the 64 KB path. In AP firmware, that path uses a 64 KB block erase helper whose status-poll result is ignored. The page-program helper also ignores its status-poll result. Around the first 64 KB boundary of the static media slot, erase/program failure can therefore be silently converted into success. This leaves lower media-slot flash stale/black while GCC reports a successful upload. The static fix worked because it changed only the erase selector to the 4 KB sector path, whose erase helper propagates status and avoids the fragile 64 KB block erase operation.
```

## Next Offline Research Direction

The next useful offline work is not another live GIF patch.

The next useful work is:

1. Map all AP writes and state transitions tied to `DAT_00008248` / `DAT_0000d168`.
2. Confirm whether any host command can set GIF timing/finalization independently of `F1[0x11] == 0x02`.
3. Search for hidden AP paths that perform:
   - sector erase with custom timing,
   - readback verify,
   - status register/protection reset,
   - chip/bank/window reinitialization.
4. Compare F1.2/F1.3/F1.4 around the 64 KB block helper and GIF timing branch.
5. Treat any future live test as high-risk unless it is read-only or uses the already proven static-only patch.



