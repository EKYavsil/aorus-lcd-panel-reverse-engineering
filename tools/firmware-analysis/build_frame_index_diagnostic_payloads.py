from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent
OUT = ROOT / "diagnostic_payloads"
W = 320
H = 170


def rgb565_le(color: tuple[int, int, int]) -> bytes:
    r, g, b = color
    value = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)
    return bytes((value & 0xFF, (value >> 8) & 0xFF))


def solid_frame_rgb565(color: tuple[int, int, int]) -> bytes:
    return rgb565_le(color) * (W * H)


def image_rgb565_le(image: Image.Image) -> bytes:
    rgb = image.convert("RGB")
    out = bytearray()
    for r, g, b in rgb.getdata():
        out.extend(rgb565_le((r, g, b)))
    return bytes(out)


def rle_compress_rgb565_le(raw: bytes) -> bytes:
    if len(raw) % 2:
        raise ValueError("RGB565 data must be even length")
    values = [raw[i] | (raw[i + 1] << 8) for i in range(0, len(raw), 2)]
    out = bytearray()
    i = 0
    while i < len(values):
        run_value = values[i]
        run = 1
        while i + run < len(values) and values[i + run] == run_value and run < 0x7FFF:
            run += 1
        if run >= 3:
            out.extend((run | 0x8000).to_bytes(2, "little"))
            out.extend(run_value.to_bytes(2, "little"))
            i += run
            continue

        literal_start = i
        i += run
        while i < len(values):
            look_value = values[i]
            look_run = 1
            while i + look_run < len(values) and values[i + look_run] == look_value and look_run < 0x7FFF:
                look_run += 1
            if look_run >= 3 or i - literal_start >= 0x7FFF:
                break
            i += look_run
        literal_count = i - literal_start
        out.extend(literal_count.to_bytes(2, "little"))
        for value in values[literal_start:i]:
            out.extend(value.to_bytes(2, "little"))
    return bytes(out)


def animation_container(frames_rgb565: list[bytes]) -> bytes:
    streams = [rle_compress_rgb565_le(frame) for frame in frames_rgb565]
    offset = 2 + len(streams) * 10
    headers = bytearray()
    for stream in streams:
        offset += len(stream)
        headers.extend((offset - 1).to_bytes(4, "little"))
        headers.extend(W.to_bytes(2, "little"))
        headers.extend(H.to_bytes(2, "little"))
        headers.extend((3).to_bytes(2, "little"))
    return len(streams).to_bytes(2, "little") + bytes(headers) + b"".join(streams)


def decode_rle_len(stream: bytes) -> tuple[bool, int, int, str | None]:
    pos = 0
    pixels = 0
    ops = 0
    while pos < len(stream):
        if pos + 2 > len(stream):
            return False, pixels, ops, "truncated block header"
        block = int.from_bytes(stream[pos : pos + 2], "little")
        pos += 2
        count = block & 0x7FFF
        if block & 0x8000:
            if pos + 2 > len(stream):
                return False, pixels, ops, "truncated repeat pixel"
            pos += 2
            pixels += count
        else:
            byte_count = count * 2
            if pos + byte_count > len(stream):
                return False, pixels, ops, "truncated literal"
            pos += byte_count
            pixels += count
        ops += 1
        if pixels > W * H:
            return False, pixels, ops, "too many pixels"
    if pixels != W * H:
        return False, pixels, ops, "wrong pixel count"
    return True, pixels, ops, None


def parse_container(payload: bytes) -> list[dict[str, object]]:
    frame_count = int.from_bytes(payload[:2], "little")
    header_len = 2 + frame_count * 10
    previous_end = header_len - 1
    rows = []
    for index in range(frame_count):
        off = 2 + index * 10
        end = int.from_bytes(payload[off : off + 4], "little")
        width = int.from_bytes(payload[off + 4 : off + 6], "little")
        height = int.from_bytes(payload[off + 6 : off + 8], "little")
        fmt = int.from_bytes(payload[off + 8 : off + 10], "little")
        start = previous_end + 1
        stream = payload[start : end + 1]
        ok, pixels, ops, err = decode_rle_len(stream)
        rows.append(
            {
                "index": index,
                "header_offset": off,
                "ap_validate_offset": index * 10 + 6,
                "stream_start": start,
                "stream_end": end,
                "stream_size": len(stream),
                "width": width,
                "height": height,
                "format": fmt,
                "rle_ok": ok,
                "pixels": pixels,
                "rle_ops": ops,
                "error": err,
            }
        )
        previous_end = end
    return rows


def make_labeled_frame(color: tuple[int, int, int], label: str) -> Image.Image:
    image = Image.new("RGB", (W, H), color)
    draw = ImageDraw.Draw(image)
    box = (8, 8, 150, 38)
    draw.rectangle(box, fill=(0, 0, 0))
    draw.text((14, 15), label, fill=(255, 255, 255))
    return image


def save_standard_gif(path: Path, frames: list[Image.Image], duration: int = 150) -> None:
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=duration, loop=0)


def write_case(name: str, colors: list[tuple[str, tuple[int, int, int]]]) -> dict[str, object]:
    frames = [make_labeled_frame(color, f"{idx}:{label}") for idx, (label, color) in enumerate(colors)]
    gif_path = OUT / f"{name}.gif"
    bin_path = OUT / f"{name}.bin"
    json_path = OUT / f"{name}.json"
    save_standard_gif(gif_path, frames)
    raw_frames = [image_rgb565_le(frame) for frame in frames]
    payload = animation_container(raw_frames)
    bin_path.write_bytes(payload)
    rows = parse_container(payload)
    summary = {
        "name": name,
        "gif_path": str(gif_path),
        "bin_path": str(bin_path),
        "gif_size": gif_path.stat().st_size,
        "bin_size": len(payload),
        "bin_md5": hashlib.md5(payload).hexdigest().upper(),
        "frame_count": len(colors),
        "colors": [{"index": i, "label": label, "rgb": color} for i, (label, color) in enumerate(colors)],
        "frames": rows,
        "predicted_ap_loop_indices_if_state_0x11_starts_at_1": list(range(1, len(colors))),
    }
    json_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return summary


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cases = [
        (
            "diag_2frame_frame1_white",
            [
                ("BLACK_DUMMY", (0, 0, 0)),
                ("WHITE_VISIBLE", (255, 255, 255)),
            ],
        ),
        (
            "diag_4frame_frame0_dummy_visible_1_2_3",
            [
                ("BLACK_DUMMY", (0, 0, 0)),
                ("WHITE_VISIBLE", (255, 255, 255)),
                ("RED_VISIBLE", (255, 0, 0)),
                ("GREEN_VISIBLE", (0, 255, 0)),
            ],
        ),
        (
            "control_2frame_frame1_black",
            [
                ("WHITE_FRAME0", (255, 255, 255)),
                ("BLACK_FRAME1", (0, 0, 0)),
            ],
        ),
    ]
    summaries = [write_case(name, colors) for name, colors in cases]
    (OUT / "index.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    print(json.dumps(summaries, indent=2))


if __name__ == "__main__":
    main()


