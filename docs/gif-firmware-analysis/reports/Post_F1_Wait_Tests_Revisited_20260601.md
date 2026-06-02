# Post-F1 Wait Tests Revisited

Date: 2026-06-01

Scope: old static-upload wait experiments, reinterpreted for the current 64 KB erase investigation. No live panel, service, firmware updater, or DLL deploy was used for this note.

## Why This Matters

The current question is whether the original 64 KB erase path is broken simply because GCC starts sending payload chunks before AP-side erase is finished.

We already tested that class of theory earlier.

## Evidence 1: 30-Second Extra Post-F1 Wait

Source files:

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\Static_PostF1_Wait_Test_Build_Report.md
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\static_post_f1_erase_wait_test.log
```

The patch did not change:

```text
nType
destination
payload
F1/F2 bytes
chunk bytes
return values
GIF path
```

It only waited 30000 ms after the existing post-F1 wait and before the first payload chunk.

Key log:

```text
F1_METADATA nType=1 result=True raw=F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00 decoded.destination=0x01300000 decoded.packetMode=2
POST_F1_WAIT_START extraMs=30000 existingEstimatedMs=5000 totalEstimatedMs=35000
POST_F1_WAIT_END elapsedMs=30002
FIRST_CHUNK nType=1 index=0 total=426 retry=0 result=True
CHUNK_SUMMARY nType=1 actualChunks=426 finalChunkIndex=425 finalResult=True
F2_FINALIZE nType=1 result=True
SENDIMAGE_RETURN nType=1 result=True
```

The important detail is `F1[0x11] = 0x02`, so this stayed on the original 64 KB erase path.

Recorded result:

```text
upper area white
lower area black
```

## Evidence 2: 10-Second Existing Wait Variant

Source file:

```text
<local-artifacts-not-for-github>\panel_reset_investigation_raw\deep_reset_research_20260527\Wait_10s_Only_Test_Report.md
```

This patch avoided inserting a new sleep block. It changed only the original wait constant:

```text
original: V_4 = 2000, wait = V_3 * 2000 + 1000
patched:  V_4 = 4500, wait = V_3 * 4500 + 1000
```

For the static payload, this changed the original wait from about 5000 ms to about 10000 ms.

Recorded result in the investigation history:

```text
negative / did not fix lower black region
```

## Inference

These tests strongly weaken this simple explanation:

```text
AP erase is fine, but GCC sends chunks too early after F1.
```

If that were the full problem, the 30-second extra wait should have let the original 64 KB path finish erasing before chunk 0. It did not.

The better current model is:

```text
The failure happens inside AP's own 64 KB erase loop.
```

Likely sub-cases:

```text
the second 64 KB erase at 0x01310000 is never accepted;
or it is issued while flash is still busy and gets ignored;
or it times out and AP ignores the timeout;
or AP advances its erase state without verifying that the second block is actually erased.
```

Host-side waiting after F1 cannot fix a skipped or falsely-completed internal AP erase operation.

## Current Conclusion

The earlier wait experiments do not disprove a 64 KB-path bug. They refine it.

The issue is probably not:

```text
need more host-side delay before chunks
```

The issue is more likely:

```text
AP's 64 KB erase implementation does not reliably complete/verify the second block, and the failure is hidden from GCC.
```

This also explains why the static fix worked when only `F1[0x11]` was changed from `0x02` to `0x01`: it avoided the unreliable 64 KB path and forced the AP through the 4 KB sector erase path.


