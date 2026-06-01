"""
make_color_cycle.py
12 solid-color frame binary olusturur (N=2 format, bypass_v18b ile ayni yapi)
Her frame tamamen farkli renk: kirmizi, yesil, mavi, sari, cyan, magenta, turuncu, mor, pembe, teal, lime, beyaz
"""
import struct, os

DESK = os.path.join(os.getcwd(), "gif_experiment_outputs")
os.makedirs(DESK, exist_ok=True)
W, H, FC, N = 320, 170, 12, 2

COLORS = [
    0xF800,  # kirmizi
    0x07E0,  # yesil
    0x001F,  # mavi
    0xFFE0,  # sari
    0x07FF,  # cyan
    0xF81F,  # magenta
    0xFC00,  # turuncu
    0x801F,  # mor
    0xF80F,  # pembe
    0x03EF,  # teal
    0x87E0,  # lime
    0xFFFF,  # beyaz
]

def encode_frame(color):
    out = bytearray()
    for _ in range(H):
        # run1: cnt=160, same color, no EOR
        out += struct.pack("<HH", 160, color)
        # run2: cnt=160, same color, EOR flag
        out += struct.pack("<HH", 160 | 0x8000, color)
    return bytes(out)

payloads = [encode_frame(c) for c in COLORS]
frame_sz = len(payloads[0])
assert frame_sz == N * 4 * H  # 1360

hdr_sz = 2 + FC * 10
total = hdr_sz + FC * frame_sz
buf = bytearray(total)
struct.pack_into("<H", buf, 0, FC)
cursor = hdr_sz
for i, pay in enumerate(payloads):
    buf[cursor:cursor+frame_sz] = pay
    struct.pack_into("<I", buf, 2+i*10, cursor+frame_sz-1)
    struct.pack_into("<H", buf, 2+i*10+4, W)
    struct.pack_into("<H", buf, 2+i*10+6, H)
    struct.pack_into("<H", buf, 2+i*10+8, 3)
    cursor += frame_sz

data = bytes(buf)
out_path = os.path.join(DESK, "bypass_fixedruns.bin")
with open(out_path, "wb") as f:
    f.write(data)
print(f"OK: {len(data)} byte, fc={FC}, frame_sz={frame_sz}")
print(f"Kaydedildi: {out_path}")
print("Renkler: kirmizi -> yesil -> mavi -> sari -> cyan -> magenta -> turuncu -> mor -> pembe -> teal -> lime -> beyaz")
