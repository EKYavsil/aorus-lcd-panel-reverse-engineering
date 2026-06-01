# Failed Approaches And Eliminated Hypotheses

This project was not solved by guessing a single byte. Several likely explanations were tested and eliminated first.

## Eliminated Or Weakened

| Hypothesis | Result |
|---|---|
| RTX 5080 / Blackwell card is not recognized | False. GCC DLLs detected the card and device IDs correctly. |
| NVIDIA private I2C functions are missing | False. Private and public NVAPI I2C entry points were present. |
| Panel is unreachable over I2C | False. Regular `0xC2`/`0xEA` paths returned success for many operations. |
| Custom image payload is not sent | False. Thousands of 256-byte chunks were sent successfully. |
| Static pData/converter is bad | False. Dumped static pData rendered correctly offline with no lower black area. |
| Apply/display-state sequence is missing | Not sufficient. Clean apply sequence did not fix the lower black region. |
| Firmware reinstall wipes custom media slot | False or unreliable. Firmware updater did not clear the stale static media content. |
| Delay/retry/pacing fixes the issue | False. Several wait/retry/pacing variants failed. |

## Important Failed Tests

- Public clean apply sequence after static upload:
  - `OpenLcd`
  - `SetDisplay`
  - `SetLoop`
  - `SetMode`
  - `Save`
  - repeated `SetMode`/`Save`
- post-F1 wait experiments
- 10-second natural wait experiment
- chunk pacing experiment
- retry-after-success experiments
- firmware reinstall attempts across F1.2/F1.3/F1.4

These failures were useful because they moved the investigation away from display mode and toward AP flash erase/program behavior.

## Breakthrough

The successful test changed only:

```text
F1[0x11]: 0x02 -> 0x01
```

This changed static image upload erase granularity from 64 KB block erase to 4 KB sector erase.

The symptom disappeared, strongly linking the original corruption to the 64 KB block erase path around `0x01310000`.

