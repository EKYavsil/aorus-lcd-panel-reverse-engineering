from __future__ import annotations

from dataclasses import dataclass
from math import ceil


I2C_PAGE = 256


@dataclass(frozen=True)
class F1State:
    name: str
    payload_size: int
    page_count: int
    target: int
    storage: int
    frame_count: int
    delay_ms: int
    mode: int
    host_prepare_unit: int
    host_prepare_delay_ms: int
    host_prepare_steps: int
    host_prepare_total_ms: int
    ap_erase_unit: int
    ap_erase_count: int
    timing_threshold: int | None
    upload_phase: int
    erase_selector: int
    remaining_pages: int
    gif_pending_frame_count_source: int | None
    gif_pending_delay_source: int | None


def gcc_page_count(payload_size: int) -> int:
    return payload_size // I2C_PAGE + 1


def ap_erase_unit(mode: int) -> int:
    if mode == 3:
        return 1
    if mode == 2:
        return 0x10000
    return 0x1000


def emulate(
    name: str,
    payload_size: int,
    target: int,
    storage: int,
    frame_count: int,
    delay_ms: int,
    mode: int,
) -> F1State:
    page_count = gcc_page_count(payload_size)

    if mode == 2:
        host_prepare_unit = 0x10000
        host_prepare_delay_ms = 2000
    else:
        host_prepare_unit = 0x1000
        host_prepare_delay_ms = 400

    host_prepare_steps = ceil(payload_size / host_prepare_unit)
    erase_unit = ap_erase_unit(mode)
    if mode == 3:
        erase_count = 1
    else:
        erase_count = ((page_count << 8) // erase_unit) + 1

    timing_threshold = None
    if mode == 2:
        timing_threshold = erase_count * 3000 + 3

    return F1State(
        name=name,
        payload_size=payload_size,
        page_count=page_count,
        target=target,
        storage=storage,
        frame_count=frame_count,
        delay_ms=delay_ms,
        mode=mode,
        host_prepare_unit=host_prepare_unit,
        host_prepare_delay_ms=host_prepare_delay_ms,
        host_prepare_steps=host_prepare_steps,
        host_prepare_total_ms=host_prepare_steps * host_prepare_delay_ms,
        ap_erase_unit=erase_unit,
        ap_erase_count=erase_count,
        timing_threshold=timing_threshold,
        upload_phase=1,
        erase_selector=mode,
        remaining_pages=page_count,
        gif_pending_frame_count_source=frame_count if storage == 2 else None,
        gif_pending_delay_source=delay_ms if storage == 2 else None,
    )


def row(state: F1State) -> list[str]:
    timing = "unchanged" if state.timing_threshold is None else str(state.timing_threshold)
    return [
        state.name,
        str(state.payload_size),
        str(state.page_count),
        f"0x{state.target:08X}",
        str(state.storage),
        str(state.frame_count),
        str(state.delay_ms),
        str(state.mode),
        f"0x{state.ap_erase_unit:X}",
        str(state.ap_erase_count),
        timing,
        str(state.host_prepare_steps),
        f"{state.host_prepare_total_ms / 1000:.1f}s",
    ]


def main() -> None:
    latest_gif_size = 1_748_766
    latest_gif_frames = 21
    latest_gif_delay = 60

    scenarios = [
        emulate(
            "latest GIF normal mode2",
            latest_gif_size,
            target=0,
            storage=2,
            frame_count=latest_gif_frames,
            delay_ms=latest_gif_delay,
            mode=2,
        ),
        emulate(
            "latest GIF forced mode1",
            latest_gif_size,
            target=0,
            storage=2,
            frame_count=latest_gif_frames,
            delay_ms=latest_gif_delay,
            mode=1,
        ),
        emulate(
            "static original mode2",
            109_024,
            target=0x01300000,
            storage=1,
            frame_count=0,
            delay_ms=0,
            mode=2,
        ),
        emulate(
            "static fixed mode1",
            109_024,
            target=0x01300000,
            storage=1,
            frame_count=0,
            delay_ms=0,
            mode=1,
        ),
    ]

    headers = [
        "scenario",
        "payload",
        "pages",
        "target",
        "storage",
        "frames",
        "delay",
        "mode",
        "erase_unit",
        "erase_count",
        "timing_threshold",
        "host_prepare_steps",
        "host_prepare_total",
    ]
    print("\t".join(headers))
    for scenario in scenarios:
        print("\t".join(row(scenario)))


if __name__ == "__main__":
    main()


