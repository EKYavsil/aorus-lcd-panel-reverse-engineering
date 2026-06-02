# AP Region And Erase Alignment Proof - 2026-06-01

Scope: offline proof only. No updater execution, no DLL loading, no I2C, no live file modification.

## Question

Is changing the firmware updater erase end from `0xEFFF` to `0xFFFF` a reckless range expansion, or is it the correct sector-aligned coverage for the F1.4 AP image?

Current answer: `0xFFFF` is the correct sector-aligned end for the F1.4 AP image tail, based on the updater protocol, AP vector table, AP size, and the already-confirmed 4KB sector erase behavior.

## Evidence 1: AP Starts At `0x1000`

Three independent offline signals point to AP base/start = `0x1000`.

### Config

`config.db` contains:

```text
IAP_CODE_SIZE = 4096 = 0x1000
```

This strongly suggests the lower `0x1000` bytes are bootloader/IAP area and the AP begins after that.

### Native Updater Erase Command

Native `I2CIAPChangeToErase12ByteMode` builds command `0x8203` with:

```text
start = 0x1000
end   = 0xEFFF
```

So the vendor updater itself treats `0x1000` as the AP erase start.

### Native Updater Program Command

Native `I2CIAPFlashAP12ByteMode` builds command `0x8204` with:

```text
start = 0x1000
end   = 0x1000 + AP_size - 1
```

Again, the vendor updater uses `0x1000` as AP programming start.

## Evidence 2: AP Vector Table Is Linked For `0x1000+`

The first AP words are a Cortex-M style vector table:

```text
vec[00] = 0x20003B10   stack pointer
vec[01] = 0x00001155   reset vector
vec[02] = 0x000011C9
vec[03] = 0x00007C61
vec[04] = 0x000011CD
vec[05] = 0x000011CF
vec[06] = 0x000011D1
...
```

The reset vector is `0x1155`, not `0x0155`.

That means the AP image is linked to execute from the `0x1000+` address range. This independently matches the updater's `0x1000` start.

## Evidence 3: F1.4 AP Extends Past The Original Erase Window

F1.4 AP size:

```text
0xE3D8 bytes
```

With AP base `0x1000`, the programmed address range is:

```text
0x1000 .. 0xF3D7
```

Original fixed erase range:

```text
0x1000 .. 0xEFFF
```

Therefore F1.4 writes this tail without the original fixed erase covering it:

```text
0xF000 .. 0xF3D7
```

Tail size:

```text
984 bytes
```

This is not theoretical; it follows directly from:

```text
0x1000 + 0xE3D8 - 1 = 0xF3D7
```

## Evidence 4: Sector Alignment Requires `0xFFFF`

The AP code path already distinguishes:

```text
0x1000  = 4KB sector erase unit
0x10000 = 64KB block erase unit
```

The sector containing the F1.4 AP tail is:

```text
0xF000 .. 0xFFFF
```

If bytes through `0xF3D7` must be programmed, then a sector-erase device cannot correctly erase only:

```text
0xF000 .. 0xF3D7
```

It must erase the containing sector:

```text
0xF000 .. 0xFFFF
```

Therefore changing the native erase end to:

```text
0xFFFF
```

is not a random expansion. It is the sector-aligned erase coverage for the F1.4 AP image.

## Why `0xF3D7` Is Less Clean

`0xF3D7` is the exact final programmed byte.

That makes it narrower, but not sector-aligned.

If the bootloader internally rounds erase ranges to sectors, `0xF3D7` and `0xFFFF` may become equivalent.

If the bootloader does not round cleanly, `0xF3D7` is more ambiguous than `0xFFFF`.

The original vendor range ended at:

```text
0xEFFF
```

That is also sector-aligned. The natural corrected equivalent is:

```text
0xFFFF
```

## Does `0xFFFF` Overlap Bootloader?

No evidence suggests bootloader overlap.

The bootloader/IAP area is below AP start:

```text
0x0000 .. 0x0FFF
```

The proposed corrected erase range is:

```text
0x1000 .. 0xFFFF
```

So it does not erase below `0x1000`.

## Does `0xFFFF` Risk Hidden Config In The Tail?

This cannot be proven absolutely from the AP file alone, but the risk is logically constrained.

The AP image itself already requires programming bytes in:

```text
0xF000 .. 0xF3D7
```

On a 4KB-sector erase device, updating those bytes correctly requires erasing the whole containing sector:

```text
0xF000 .. 0xFFFF
```

Therefore, if critical hidden config lived in:

```text
0xF3D8 .. 0xFFFF
```

then any correct F1.4 AP update would already need a preserve-and-restore mechanism for that sector. The original updater does not show such a mechanism in the analyzed path; it simply has a too-short fixed erase range.

So the more likely vendor bug is:

```text
F1.4 AP grew into the next sector, but the fixed erase end stayed at the old F1.2/F1.3 sector boundary.
```

## Confidence Estimate

| Claim | Confidence |
|---|---:|
| AP starts at `0x1000` | Very high |
| Bootloader/IAP is below `0x1000` | High |
| F1.4 programs through `0xF3D7` | Very high |
| Original erase misses `0xF000..0xF3D7` | Very high |
| `0xFFFF` is the correct sector-aligned erase end for F1.4 AP delivery | High |
| No hidden metadata exists in `0xF3D8..0xFFFF` | Medium |

## Practical Conclusion

For any future controlled firmware delivery test, the native updater erase-end patch should prefer:

```text
0xEFFF -> 0xFFFF
```

over:

```text
0xEFFF -> 0xF3D7
```

because `0xFFFF` is sector-aligned and matches the AP image crossing into the `0xF000..0xFFFF` sector.

This does not remove all live-flash risk. It only proves that the `0xFFFF` range is technically justified and not arbitrary.


