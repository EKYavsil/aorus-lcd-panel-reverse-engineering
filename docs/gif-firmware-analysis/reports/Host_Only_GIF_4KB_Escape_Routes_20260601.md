# Host-Only GIF 4 KB Escape Routes

Date: 2026-06-01

Scope: offline only. No live test, no DLL deploy, no service action, no firmware updater execution, no panel command.

## Goal

Find whether GIF upload can avoid the broken 64 KB erase path without AP firmware changes.

The desired host-only behavior would be:

```text
use reliable 4 KB erase/program
but keep the GIF finalization/commit state normally associated with mode 2
```

## Current Hard Constraint

AP firmware ties these two effects to one byte:

```text
F1[0x11]
```

Observed AP behavior:

| `F1[0x11]` | Erase unit | Timing threshold write | Resulting concern |
|---:|---:|---:|---|
| `0x01` | `0x1000` 4 KB | no confirmed write | good erase, incomplete GIF finalization setup |
| `0x02` | `0x10000` 64 KB | `timing_threshold = erase_count * 3000 + 3` | correct GIF timing setup, bad erase path |
| `0x03` | special unit `1` | no confirmed write | not a normal GIF solution |

## Candidate Escape Routes Checked

### 1. Directly force GIF to `F1[0x11]=1`

This is the static-image fix applied to GIF.

Status:

```text
Rejected as a general fix for now.
```

Why:

- it does select 4 KB erase;
- but AP does not refresh `0x20000308`;
- previous live forcing caused unstable/bad panel behavior;
- current AP write-map shows no compensating command in the normal finalize path.

### 2. Force mode1 and wait longer

Status:

```text
Weak and not mechanically justified.
```

Why:

- host wait does not write `0x20000308`;
- host wait does not copy pending frame count/delay to active frame count/delay;
- earlier wait experiments did not repair 64 KB path behavior.

### 3. Repeat known display/apply commands after mode1 GIF

Commands considered:

- `E1` / SetDisplay
- `F3` / SetLoop
- `E5` / SetMode
- `AA` / Save
- `F2` finish/finalize

Status:

```text
Unlikely to solve the core issue.
```

Why:

- these commands can change display, loop, dirty, mode, save, and some slot flags;
- current write-map does not show them setting `0x20000308`;
- current write-map does not show them performing the GIF pending-to-active metadata commit.

### 4. Use the `0x66 CB 55 AC 38 AB CD EF` command-like branch

This branch is interesting because it resembles a direct commit:

```c
if (cmd == 0x66 CB 55 AC 38 AB CD EF) {
    upload_elapsed = 0;
    state.active_frame_count = state.pending_frame_count;
    state.active_delay = state.pending_delay;
    state.upload_display_state = 2;
    state.upload_run_flag = 0;
    state.refresh_dirty = 1;
    state.display_dirty = 1;

    if (custom_slot_selector == 1) slot1_ready = 2;
    if (custom_slot_selector == 2) slot2_ready = 2;
}
```

This is the closest host-visible branch found so far to the missing GIF commit step.

Status:

```text
Interesting but not yet safe.
```

Reasons:

- It appears to bypass the timing gate and perform the pending-to-active copy.
- It does not itself prove that flash erase/program is complete.
- If sent too early after a forced 4 KB large GIF, it could commit partially written data.
- It is not known whether GCC/ucVga ever sends this command in a valid production flow.
- It uses a different magic suffix (`AB CD EF`) rather than the normal `CB 55 AC 38` command family alone.

Potential future use:

```text
Only after offline confirmation of command purpose and exact expected timing.
```

This is not ready for live testing.

### 5. Use F2 mode/state branch as commit substitute

F2 with packet byte 5 equal to `2` can touch:

- upload run flag
- current mode/state
- slot-ready fields
- display dirty flag
- GIF-related latch in one branch

Status:

```text
Not enough.
```

Why:

- no timing threshold write;
- no confirmed pending frame count/delay to active frame count/delay copy;
- likely display-state finalize, not full GIF metadata commit.

### 6. Use an undocumented command to set `0x20000308`

Status:

```text
Not found.
```

Evidence:

- Current xref/write-map finds `0x20000308` written only by the F1 mode2 branch.
- The main loop reads it for the upload pending gate.
- Known parser branches do not expose an obvious setter for this field.

## Current Best Host-Only Lead

The only host-side lead that could plausibly combine with `F1[0x11]=1` is the `0x66` branch, because it resembles the missing pending-to-active GIF commit.

But it is unsafe until these are answered offline:

1. Is `0x66` an intended command or an internal/debug/resume command?
2. Does `0x66` require flash write completion first?
3. Can AP indicate that forced 4 KB erase/program is truly complete before `0x66`?
4. Does `0x66` also handle slot selection correctly for GIF `destination=0`, `storage=2`?
5. Is there any public repo, old DLL, or updater path that sends `0x66 CB 55 AC 38 AB CD EF`?

## Recommended Offline Research Next

1. Search host DLLs and public repos for raw bytes:

```text
66 CB 55 AC 38 AB CD EF
```

2. Search ucVga/old ucVga/GvLcd wrappers for a method that builds command `0x66`.
3. Map every AP reference to the state fields touched by `0x66`:
   - `state+0x66`
   - `state+0x18`
   - `state+0x2e`
   - `state+0x32`
   - `state+0x3d`
   - `state+0x3f`
   - `state+0x1a`
   - `state+0x1b`
4. Confirm whether `0x66` is sent after page programming completion in any legitimate flow.
5. If no legitimate host use is found, treat it as high-risk and do not test live.

## Current Verdict

A clean host-only GIF 4 KB solution is not proven.

The most promising host-only escape route is not `F2`, `AA`, `E5`, `F3`, or extra waits. It is the unexplained `0x66 CB 55 AC 38 AB CD EF` branch, because it resembles the exact GIF state commit that mode1 lacks.

But until we prove its intended use, it should stay offline-only.

## Host-Side Search Result

After this report was started, an additional offline host-side search was performed.

Searched for:

```text
66 CB 55 AC 38 AB CD EF
66 CB 55 AC 38
```

Controlled binary search results:

| File | Result |
|---|---|
| `C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\ucVga.dll` | no hit |
| `C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\GvDll\GvDisplay.dll` | no hit |
| `C:\Program Files\GIGABYTE\Control Center\Lib\GBT_VGA\RLE_Compress.dll` | no hit |

Text/source search results:

- hits in AP firmware decompile outputs;
- hits in our previous analysis reports;
- no meaningful production host-source hit in the public repositories searched.

Interpretation:

```text
The 0x66 branch exists in AP firmware,
but current evidence does not show GCC/ucVga using it as a normal host command.
```

This makes it a weaker live-test candidate than it first appeared. It may be:

- an internal AP-side recovery/handshake path;
- an unused/debug command;
- a command from an updater/service path not present in the searched host DLLs;
- or a valid command constructed dynamically without a static byte array.

The last case is still possible, but there is no current evidence for it.

## Cross-Version Search Result

The same parser signature was searched in existing AP decompile dumps for F1.2, F1.3, and F1.4.

Search terms:

```text
*DAT_... == 0x66
DAT_...[5] == 0xab
DAT_...[6] == 0xcd
DAT_...[7] == 0xef
```

Result:

| AP firmware dump | `0x66 CB 55 AC 38 AB CD EF` parser branch |
|---|---|
| F1.2 | not found in existing dump |
| F1.3 | not found in existing dump |
| F1.4 | found |

Interpretation:

```text
The 0x66 branch appears to be F1.4-specific in the current dumps.
```

That makes it less likely to be a long-standing public host protocol command used by GCC. It may have been added as an AP-side recovery, diagnostic, or internal state path.

This does not prove it is unsafe, but it raises the bar for live testing.

## Static Array / Host IL Result

Existing `ucVga.dll` static array dumps include:

```text
E7 CB 55 AC 38 ...
E5 CB 55 AC 38 ...
AA CB 55 AC 38 ...
F2 CB 55 AC 38 01 ...
F2 CB 55 AC 38 02 ...
F3 CB 55 AC 38 ...
E1 CB 55 AC 38 ...
FA CB 55 AC 38 ...
D6/DE/DF/EB/F4 ...
```

They do not include:

```text
66 CB 55 AC 38 AB CD EF
11 CB 55 AC 38 AB CD EF
```

A Cecil-based command scanner was also created:

```text
<local-gif-investigation-dir>\scratch\ByteCommandScanner\
```

Outputs:

```text
<local-gif-investigation-dir>\reports\Host_DLL_Command_Byte_Scanner_20260601.md
docs/gif-firmware-analysis/reports/Host_DLL_Command_Byte_Scanner_20260601.md
```

The scanner did not find a managed host method building or sending the `0x66` command family.

Practical conclusion:

```text
There is no current host-side evidence that GCC intentionally uses 0x66.
```

## Safety Note

No live changes were made for this report.


