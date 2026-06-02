# GIF 64 KB To 4 KB Strategy And Mode Differences

Date: 2026-06-01

Scope: offline analysis only. No live DLL deploy, service action, panel command, raw I2C, or firmware updater execution.

## Question

Can we make GIF uploads use the 4 KB path safely?

Related question:

```text
Is the 64 KB path only a speed optimization for large files?
```

## Short Answer

No. Current AP evidence shows the `F1[0x11]` mode byte is not only a speed/chunk-size selector.

It controls at least:

1. AP erase unit.
2. AP erase operation count.
3. Host-side preparation wait count and wait duration.
4. Large-upload finalization/timing threshold at `0x20000308`.

The dangerous part for GIF is item 4.

## Host-Side Difference

`GvLcdApi.SendImage` starts with:

```text
default:
  mode byte V_2 = 1
  prepare unit = 4096
  prepare sleep = 400 ms

if uSize >= 20480:
  mode byte V_2 = 2
  prepare unit = 65536
  prepare sleep = 2000 ms
```

Then it writes:

```text
F1[0x11] = V_2
```

For the latest real GIF upload:

```text
source GIF       = 19,244 bytes
GCC animation.bin = 1,748,766 bytes
page count       = 6,832
natural mode     = 2 / 64 KB
```

If the host decision were changed so this payload used mode `1`, host preparation would become roughly:

```text
ceil(1,748,766 / 4096) = 427 prepare steps
427 * 400 ms ~= 171 seconds before data transmission
```

The upload would be much slower, but speed is not the main blocker.

## AP-Side Difference

AP parser:

```c
bVar30 = F1[0x11];

if (bVar30 == 3) {
    erase_unit = 1;
}
else if (bVar30 == 2) {
    erase_unit = 0x10000;
}
else {
    erase_unit = 0x1000;
}

erase_count = ((page_count << 8) / erase_unit) + 1;
```

For the latest 1,748,766-byte GIF:

| Mode | Erase unit | AP erase count |
|---:|---:|---:|
| `F1[0x11]=0x02` | `0x10000` / 64 KB | `27` |
| `F1[0x11]=0x01` | `0x1000` / 4 KB | `428` |

The one-step difference is expected. Host prepare count is computed from raw byte size. AP erase count is computed from GCC's page count field and includes the AP parser's extra `+1`.

This part is exactly what we want:

```text
mode 1 avoids the broken 64 KB erase helper and uses the better 4 KB sector erase helper.
```

But AP also does this:

```c
if (bVar30 == 2) {
    DAT_0000D168 = erase_count * 3000 + 3;
}
```

For the same GIF in normal mode:

```text
DAT_0000D168 = 27 * 3000 + 3 = 81003
```

For forced mode `1`:

```text
DAT_0000D168 is not refreshed by the F1 parser.
```

That is the known unsafe coupling.

## Why Static Worked But GIF Did Not

Static image fix:

```text
F1[0x11] 0x02 -> 0x01
```

worked because static display does not appear to depend on the same large-GIF delayed finalization behavior.

GIF is different:

```text
destination = 0
storage = 2
mode = GIF / animation
payload is structured: frame table + RLE streams
large-upload timing/finalization field is tied to F1[0x11] == 2
```

Therefore a direct GIF patch:

```text
F1[0x11] 0x02 -> 0x01
```

does two things at once:

```text
good: avoids broken 64 KB erase
bad: removes the only confirmed write to the large-upload finalization threshold
```

This matches the failed/unsafe GIF test.

## Is 64 KB Only For Faster Upload?

Current answer:

```text
No.
```

It is partly a speed/erase-count optimization, but AP firmware also uses the same mode byte as a state-machine selector for large-upload finalization.

The relevant difference is:

| Behavior | Mode `1` | Mode `2` |
|---|---:|---:|
| Host prepare unit | 4 KB | 64 KB |
| Host prepare delay per unit | 400 ms | 2000 ms |
| AP erase unit | 4 KB | 64 KB |
| AP erase helper | `FUN_0000B910` | `FUN_0000B4D0` |
| AP erase helper propagates poll failure | yes | no |
| AP writes `0x20000308` timing threshold | no | yes |

So changing only the initial decision rule:

```text
if size >= 20480 also use mode 1
```

is not a safe complete GIF fix.

## Safe 4 KB Conversion Requirements

A safe GIF 4 KB conversion needs both:

```text
1. AP erase path uses 4 KB sector erase.
2. AP finalization/timing state remains equivalent to the original large-GIF mode 2 path.
```

Known host protocol currently exposes only one byte controlling both:

```text
F1[0x11]
```

No confirmed separate host command can set:

```text
0x20000308 / DAT_0000D168
```

## Candidate Strategies

### Strategy A: Host-only force all GIFs to mode 1

Patch host decision:

```text
large GIF also F1[0x11] = 1
```

Expected:

- avoids AP 64 KB erase;
- uses many 4 KB erase operations;
- greatly increases preparation time;
- does not refresh AP timing field.

Status:

```text
Rejected as a direct fix.
This is probably what made the earlier direct GIF 0x02 -> 0x01 test unsafe.
```

### Strategy B: Host-only mode 1 plus artificial waits/apply commands

Patch:

```text
F1[0x11] = 1
then wait longer
then F2 finalize / mode apply / save
```

Problem:

- host waits do not write `0x20000308`;
- old static wait tests already showed that waiting cannot repair AP internal erase-state failures;
- no known apply command recreates the missing large-GIF finalization threshold.

Status:

```text
Not enough evidence for live testing.
```

### Strategy C: Host-only dual-F1 sequence

Concept:

```text
send one F1[0x11] = 2 to set timing,
then send another F1[0x11] = 1 for actual 4 KB erase/upload
```

Problem:

- first F1 may queue dangerous 64 KB erase;
- second F1 likely rewrites upload state and may clear the intended timing semantics;
- AP parser has no confirmed "timing only" F1 path.

Status:

```text
Offline emulation target only.
Not safe for live testing.
```

### Strategy D: AP-side semantic split

Concept:

Keep host/GCC behavior unchanged:

```text
GIF large upload still sends F1[0x11] = 2
```

But repair AP behavior:

```text
mode 2 keeps timing/finalization semantics
mode 2 internally erases using repeated 4 KB sector erases instead of broken 64 KB block erase
```

This is the technically clean solution:

```text
preserve what GIF needs from mode 2
remove only the broken 64 KB erase implementation
```

Status:

```text
Best technical design, but it is AP firmware-side and therefore deferred unless host-only paths fail.
```

### Strategy E: Find hidden command to set timing field

Desired host-only split:

```text
F1[0x11] = 1 for 4 KB erase
separate command sets DAT_0000D168 as if mode 2 had run
```

Current search result:

```text
No confirmed command found.
```

Status:

```text
Continue offline search if needed.
This is the only path that could make a clean host-only general GIF fix plausible.
```

## Current Recommendation

Do not assume the 20 KB decision is only a speed optimization.

Next work should focus on proving or disproving this exact possibility:

```text
Can the AP timing/finalization state normally created by F1[0x11] == 2
be recreated while using F1[0x11] == 1?
```

If yes:

```text
host-only general GIF 4 KB fix may be possible.
```

If no:

```text
general reliable GIF fix probably requires AP-side semantic split:
keep mode 2 externally, replace internal 64 KB erase with verified 4 KB sector erase.
```

## Immediate Offline Next Steps

1. Build a small AP F1 state emulator:
   - input: payload size, page count, target, storage, frame count, delay, mode byte;
   - output: erase unit, erase count, timing threshold, upload phase fields.

2. Model the latest GIF:
   - `animation.bin = 1,748,766 bytes`
   - `page_count = 6,832`
   - mode 2 original vs mode 1 forced.

3. Search AP parser and host DLL one more time for any command/call that can write or influence:
   - `DAT_0000D168 / 0x20000308`
   - `DAT_0000D164 / upload_pending`
   - `DAT_0000D160 / upload_phase`
   - GIF slot state fields around `state+0x18`, `state+0x19`, `state+0x1A`, `state+0x1B`.

4. Only after that, decide whether a live host-only test is justified.


