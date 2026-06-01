import struct, math, os

DESK = os.path.join(os.getcwd(), "gif_experiment_outputs")
os.makedirs(DESK, exist_ok=True)
W, H, FC = 320, 170, 24

def hsv_to_rgb565(h, s=1.0, v=1.0):
    h = h % 1.0
    i = int(h * 6); f = h * 6 - i
    p, q, t = v*(1-s), v*(1-f*s), v*(1-(1-f)*s)
    rgb = [(v,t,p),(q,v,p),(p,v,t),(p,q,v),(t,p,v),(v,p,q)][i % 6]
    r, g, b = int(rgb[0]*255), int(rgb[1]*255), int(rgb[2]*255)
    return ((r>>3)<<11)|((g>>2)<<5)|(b>>3)

payloads = []
for fi in range(FC):
    out = bytearray()
    for y in range(H):
        hue = (y / H + fi / FC) % 1.0
        c = hsv_to_rgb565(hue)
        out += struct.pack('<HH', 1,          c)
        out += struct.pack('<HH', 0x8000|319, c)
    payloads.append(bytes(out))

fc  = FC
hdr = 2 + fc * 10
total = hdr + sum(len(p) for p in payloads)
buf = bytearray(total)
struct.pack_into('<H', buf, 0, fc)
cursor = hdr
for i, p in enumerate(payloads):
    buf[cursor:cursor+len(p)] = p
    struct.pack_into('<I', buf, 2+i*10,   cursor+len(p)-1)
    struct.pack_into('<H', buf, 2+i*10+4, W)
    struct.pack_into('<H', buf, 2+i*10+6, H)
    struct.pack_into('<H', buf, 2+i*10+8, 3)
    cursor += len(p)

path = os.path.join(DESK, "bypass_rainbow.bin")
with open(path, "wb") as f:
    f.write(buf)
print("bypass_rainbow.bin: fc=%d usize=%d (%dKB)" % (fc, total, total//1024))
print("Her frame: 170 satir x farkli hue rengi, kayan dalga")
