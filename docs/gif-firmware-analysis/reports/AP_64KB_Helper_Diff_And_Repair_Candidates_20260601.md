# AP 64 KB Helper Diff and Repair Candidates

Date: 2026-06-01

Scope: offline firmware analysis only. This is not a live patch plan and does not recommend flashing anything yet.

## Question

We need to know whether the 64 KB upload path is only "reported badly" or actually defective, and whether it can be repaired.

The relevant helpers in AP F1.4 are:

| Helper | Address | SPI operation |
|---|---:|---|
| `FUN_0000B4D0` | `0x0000B4D0` | `0xD8` 64 KB block erase |
| `FUN_0000B910` | `0x0000B910` | `0x20` 4 KB sector erase |
| `FUN_0000B6CC` | `0x0000B6CC` | `0x02` 256-byte page program |
| `FUN_0000BA44` | `0x0000BA44` | `0x05` status poll / WIP wait |

Raw source:

```text
<local-gif-investigation-dir>\ghidra_64kb_deep_outputs\ap_page_program_pattern_search_AP_F14_pkg_deep.txt
```

## 4 KB Sector Erase: Correct Return Flow

`FUN_0000B910(address)` sends `0x20 + address`, then waits on `FUN_0000BA44()`.

Important decompile shape:

```c
FUN_0000b990(0x20);
FUN_0000b990(addr >> 24);
FUN_0000b990(addr >> 16);
FUN_0000b990(addr >> 8);
FUN_0000b990(addr & 0xff);

FUN_000038a8(...);

iVar4 = FUN_0000ba44();
uVar2 = 0;
if (iVar4 != 0) {
    uVar2 = 1;
}
return uVar2;
```

Meaning:

```text
status poll success -> helper returns 1
status poll timeout/failure -> helper returns 0
```

The AP main upload loop checks this return. If it is `0`, the erase phase stops instead of pretending the slot is ready.

## 64 KB Block Erase: Broken Return Flow

`FUN_0000B4D0(address)` sends `0xD8 + address`, then calls the same status poll helper.

Important decompile shape:

```c
FUN_0000b990(0xd8);
FUN_0000b990(addr >> 24);
FUN_0000b990(addr >> 16);
FUN_0000b990(addr >> 8);
FUN_0000b990(addr & 0xff);

FUN_000038a8(...);

FUN_0000ba44();
uVar3 = 1;
return uVar3;
```

Meaning:

```text
status poll success -> helper returns 1
status poll timeout/failure -> helper still returns 1
```

This is not just bad logging. It changes behavior: the main upload loop advances to the next erase block or page programming even if the flash is not actually ready.

## Page Program Also Hides Failure

`FUN_0000B6CC(buffer, address, 0x100)` sends `0x02 + address + 256 bytes`, then calls `FUN_0000BA44()`, but also returns success unconditionally after the poll call.

This means the AP has two independent ways to hide a bad write:

1. A 64 KB erase can fail or time out and still be treated as done.
2. A page program can fail or time out and still be treated as done.

For static image, the visible split at the first 64 KB boundary points most directly to the erase path. Page-program hiding is still an additional integrity risk.

## Why Host Wait Did Not Fix 64 KB Mode

Old tests already tried longer waits after F1 before the first payload chunk:

| Test | Effect |
|---|---|
| Existing wait expanded to about 10 seconds | still lower black |
| Extra 30 second post-F1 wait, about 35 seconds total | still lower black |

Those tests do not contradict the 64 KB erase bug. They narrow it:

```text
The problem is not just "host sends chunks too soon after F1".
```

The AP internally performs two 64 KB erase operations for the static slot:

```text
0x01300000
0x01310000
```

If the AP helper already timed out, skipped, lost, or mis-completed the second erase while hiding the failure, waiting later on the host cannot force the AP to retry that second `0xD8`.

## Is The 64 KB Path Repairable?

There are three possible repair layers.

### Repair Layer 1: Host DLL Avoids 64 KB Mode

This is what fixed static:

```text
F1[0x11]: 0x02 -> 0x01
```

Pros:

- Does not modify AP firmware.
- Uses the AP's known-good 4 KB sector erase helper.
- Already proven for static image.

Cons:

- GIF is not equivalent to static.
- For GIF, `F1[0x11] == 0x02` also sets the AP finalization/timing threshold `DAT_0000D168 = erase_count * 3000 + 3`.
- Direct GIF `0x02 -> 0x01` loses that side effect and can destabilize the AP state machine.

Conclusion:

```text
Best host-side direction, but GIF needs a way to preserve the 0x02 timing semantics while using sector erase.
```

### Repair Layer 2: AP Firmware Fixes `FUN_0000B4D0`

Minimal conceptual AP-side fix:

```c
return FUN_0000BA44();
```

instead of:

```c
FUN_0000BA44();
return 1;
```

This would not make 64 KB erase complete by itself, but it would stop silent corruption. The host/AP upload flow would fail rather than write into a partially erased slot.

Better AP-side fix:

```text
increase the 64 KB erase poll timeout, then propagate the result
```

or:

```text
replace media-slot 64 KB erases with repeated 4 KB sector erases while preserving the GIF timing threshold
```

Risk:

- Requires AP firmware patching/flashing.
- That is much higher risk than a host DLL patch.
- Not suitable until the firmware package format, checksum, updater behavior, and recovery path are fully understood.

Conclusion:

```text
Technically repairable in AP firmware, but not the next live step.
```

### Repair Layer 3: Host Performs Verification/Retry

Ideal host-side robust fix:

```text
upload -> read back media flash -> compare -> retry failed sector/block
```

Current blocker:

```text
No confirmed host-exposed arbitrary media flash readback command has been found.
```

Parser-visible read commands return small AP state/config values, not caller-controlled flash bytes.

Conclusion:

```text
Good engineering design, but blocked unless a hidden readback command is found or AP firmware is patched.
```

## GIF-Specific Repair Implication

GIF likely needs a hybrid fix:

```text
avoid 64 KB erase for actual storage writes
preserve the AP timing/finalization state normally created by F1[0x11] == 0x02
preserve destination=0 / storage=2 / mode=5 semantics
```

The current direct GIF `0x02 -> 0x01` test was too blunt because it changed both:

| Field effect | Static impact | GIF impact |
|---|---|---|
| erase unit 64 KB -> 4 KB | good | likely good |
| no `DAT_0000D168 = erase_count * 3000 + 3` | irrelevant or low impact | dangerous |

That is why static succeeded and GIF did not.

## Current Best Hypothesis

The 64 KB path is genuinely defective, not merely cosmetically misreported:

```text
F1[0x11] == 0x02 selects AP 64 KB block erase.
The AP sends SPI 0xD8 and polls status.
The poll can fail or time out.
The helper discards that result and reports success.
The upload state machine advances.
The second 64 KB region can remain stale or partially unprogrammable.
Static lower ~40% maps exactly to the second 64 KB region.
GIF corruption is the same storage integrity problem expressed through structured animation data.
```

## Next Offline Work

1. Map `DAT_0000D168` completely: all writes, all reads, and the exact finalization gate it controls.
2. Determine whether any parser command can set the same timing/finalization state without selecting 64 KB erase.
3. If not, design a host-only GIF experiment that preserves `DAT_0000D168` indirectly without performing live 64 KB media erase. This is only a design step, not a live test yet.
4. Separately, produce an AP firmware patch feasibility note for `FUN_0000B4D0` return propagation and timeout increase, without flashing.


