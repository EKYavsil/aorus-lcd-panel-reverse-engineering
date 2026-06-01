# SendImage Tracing Experiments

This folder contains source-level trace patch builders and helper scripts used to prove that the static image payload path was not the root cause.

Key conclusion from this phase: static pData rendered correctly offline and SendImage wrote all expected chunks successfully, so investigation moved from converter/payload bugs to AP firmware erase/write semantics.

Compiled binaries, patched DLLs, raw pData dumps, and logs are kept out of GitHub-facing folders.
