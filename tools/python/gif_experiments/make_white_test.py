"""
Tamamen beyaz (0xFFFF) 12 frame binary. Eger panel beyaz gosterirse
verimiz dogru enjekte ediliyor. Gostermezse baska bir sey oynatiliyor.
"""
import struct, os
DESK = os.path.join(os.getcwd(), "gif_experiment_outputs")
os.makedirs(DESK, exist_ok=True)
W, H, FC, N = 320, 170, 12, 2
WHITE = 0xFFFF

frame = bytearray()
for _ in range(H):
    frame += struct.pack("<HH", 160, WHITE)
    frame += struct.pack("<HH", 160 | 0x8000, WHITE)
frame = bytes(frame)

hdr_sz = 2 + FC * 10
total = hdr_sz + FC * len(frame)
buf = bytearray(total)
struct.pack_into("<H", buf, 0, FC)
cursor = hdr_sz
for i in range(FC):
    buf[cursor:cursor+len(frame)] = frame
    struct.pack_into("<I", buf, 2+i*10, cursor+len(frame)-1)
    struct.pack_into("<H", buf, 2+i*10+4, W)
    struct.pack_into("<H", buf, 2+i*10+6, H)
    struct.pack_into("<H", buf, 2+i*10+8, 3)
    cursor += len(frame)

data = bytes(buf)
out = os.path.join(DESK, "bypass_fixedruns.bin")
with open(out, "wb") as f:
    f.write(data)
print(f"OK: {len(data)} bytes, fc={FC}, tum frameler BEYAZ (0xFFFF)")
