# N2B Flash Result Propagation Harness

Public dry-run verifier for the current three-patch AP/AP1 repair:

```text
BA44 timeout: 300 -> 1000
B4D0: preserve the native 64 KB erase poll result
B6CC: preserve the 256-byte page-program poll result
```

The verifier checks:

- AP/AP1 size and equality;
- patched AP/AP1 SHA256;
- CRC16 over `0x28..EOF`;
- all three patch sites;
- the original signed `GvLcdFwUpdate.dll` SHA256.

It does not import or call firmware update exports. Passing `--live` is explicitly rejected.

Proprietary AP/AP1 payloads, vendor DLLs, compiled binaries, and live logs are not included.
