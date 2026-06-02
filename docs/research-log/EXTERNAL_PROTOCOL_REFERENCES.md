# External Protocol References

Date reviewed: 2026-06-02

The offline investigation folder contained local checkouts of several public AORUS/GIGABYTE LCD-related repositories. Their source code is not copied into this repository. This file records which repositories informed the investigation and what was useful from each one.

## Repositories

| Repository | Use In This Project |
| --- | --- |
| `https://github.com/Maheidem/gigabyte-gpu-lcd` | Most useful protocol reference for the newer AORUS LCD command family. It documented the clean apply sequence around `E7`, `F2`, `F1`, payload pages, `E1`, `F3`, `E5`, and `AA`. |
| `https://github.com/PrivateGER/Gigabyte-Aorus-LCD-Driver` | Useful independent protocol documentation and Linux-side LCD driver context. |
| `https://github.com/SparkyTD/aorus-lcd-imger` | Useful historical GIF/RLE format reference, especially `animation.bin` frame table and RGB565/RLE conversion behavior. |
| `https://github.com/javs/AorusLCD` | Older AORUS LCD control reference. Useful mainly as historical context that the panel command family predates current GCC. |

## Integration Decision

The public repositories were used as references, not vendored dependencies.

Reasons:

- Avoid copying third-party source into this project unnecessarily.
- Keep licensing boundaries clear.
- Keep this repository focused on the RTX 5080 ICE AP firmware defect and the final patcher.
- Preserve enough attribution for reviewers to follow the external protocol trail.

## Relevant Local Findings Influenced By Public References

- The clean apply sequence helped prove that the static-image and GIF failures were not simply missing `SetMode`, `SetLoop`, `SetDisplay`, or `Save` calls.
- The public GIF/RLE format references helped separate host-side conversion questions from panel-side flash erase reliability.
- The external protocol work reinforced that `F1[0x11] == 0x02` should be understood as a meaningful AP-side mode/erase selector, not a random byte to blindly mutate.

The final fix does not depend on these repositories at runtime.
