# Custom GIF Corruption Research Summary

## Status

The static custom-image corruption issue has a working narrow host-side fix. The custom GIF issue is still unresolved.

The important finding so far is that the GIF problem appears to be related to the same broader panel-side flash erase/write subsystem that caused the static-image corruption, but the GIF path cannot be fixed by simply applying the same byte change used for static images.

## Why GIF Is Different

Initial attempts to apply the same `0x02 -> 0x01` idea to GIF uploads were not successful.

The key reason is that `F1[0x11]` does more than select an erase size/path. AP firmware analysis suggests this field is also coupled to GIF-specific timing or finalization state.

In other words:

```text
0x01 works for static image erase behavior.
0x02 appears to be expected by the GIF state machine.
```

So forcing GIF uploads onto the static-style `0x01` path can remove or disturb GIF-specific semantics that the panel firmware expects.

Therefore a correct GIF fix may need to repair the panel firmware's internal 64KB erase helper instead of forcing GIF uploads onto the static 4KB path from the host side.

This would preserve GIF upload semantics while avoiding the unreliable erase implementation.

## AP Firmware Finding

Offline AP firmware analysis identified a likely 64KB erase helper function:

```text
AP function address: 0x0000B4D0
AP file offset:     0xA4D0
```

A potential repair direction is to replace that helper with a loop that performs sixteen reliable 4KB sector erases internally.

Conceptually:

```text
64KB erase(address)
    -> erase_4KB(address + 0x0000)
    -> erase_4KB(address + 0x1000)
    -> erase_4KB(address + 0x2000)
    ...
    -> erase_4KB(address + 0xF000)
```

This is only an offline research candidate at this stage. It has not been published as a user-facing fix.

## Firmware Updater Finding

A separate firmware updater issue was also found during offline analysis.

The native LCD firmware updater appears to erase a fixed AP range:

```text
0x1000..0xEFFF
```

However, the F1.4 AP image appears to be programmed through:

```text
0x1000..0xF3D7
```

That means the F1.4 update path may program bytes beyond the fixed erase window.

This is a strong candidate for explaining why firmware reinstall/update behavior can be inconsistent. It may also explain why repeated firmware runs sometimes recover the panel state while a first run fails.

This finding is separate from the custom GIF fix, but it is relevant because a firmware-level repair would need to be delivered safely through this updater path.


