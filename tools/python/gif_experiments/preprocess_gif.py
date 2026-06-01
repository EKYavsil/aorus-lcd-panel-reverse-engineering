# preprocess_gif.py
# Herhangi bir GIF'i GCC'nin encode edebilecegi kucuk bir versiyona donusturur.
# Her frame < 5000 byte RLE hedefi.
# Cikti: <isim>_gcc.gif
import sys, os, io, struct
from PIL import Image

DESK = os.path.join(os.getcwd(), "gif_experiment_outputs")
os.makedirs(DESK, exist_ok=True)

def snap_to_rgb565(img_rgb):
    # Palette renklerini exact RGB565 round-trip degerlerine snaple.
    # Bu sayede GCC'nin renk donusumu sifir hata ile yapilir -> kirmizi run sayisi azalir.
    px = img_rgb.load()
    w, h = img_rgb.size
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            r5 = r >> 3;  g6 = g >> 2;  b5 = b >> 3
            px[x, y] = (r5 << 3, g6 << 2, b5 << 3)
    return img_rgb

def process(gif_path, colors=4, pixelate=80, max_frames=4):
    src = Image.open(gif_path)
    W, H = 320, 170

    frames = []
    try:
        while True:
            frames.append(src.copy().convert("RGB"))
            src.seek(src.tell() + 1)
    except EOFError:
        pass

    if len(frames) > max_frames:
        step = len(frames) / max_frames
        frames = [frames[int(i * step)] for i in range(max_frames)]

    out_frames = []
    for f in frames:
        sw = max(1, W // pixelate)
        sh = max(1, H // pixelate)
        small = f.resize((sw, sh), Image.LANCZOS)
        q_small = small.quantize(colors=colors, dither=Image.Dither.NONE)
        big = q_small.convert("RGB").resize((W, H), Image.NEAREST)
        big = snap_to_rgb565(big)
        out_frames.append(big)

    base = os.path.splitext(os.path.basename(gif_path))[0]
    out_path = os.path.join(DESK, base + "_gcc.gif")

    # Tum frame'leri TEK global palette ile kaydet, LCT OLMADAN.
    # LCT iceren GIF'lerde GCC farkli (yanlis) kodlama kullaniyor -> panel reddediyor.
    # Yontem: her frame'i ayri tek-frame GIF olarak kaydet (PIL optimizasyonu yok),
    # sonra hepsini tek multi-frame GIF'e elle birlestir (GCT-only, tam frame).
    n = len(out_frames)
    combined = Image.new("RGB", (W * n, H))
    for idx2, fr in enumerate(out_frames):
        combined.paste(fr, (idx2 * W, 0))
    q_combined = combined.quantize(colors=colors, dither=Image.Dither.NONE)
    p_frames = [q_combined.crop((idx2 * W, 0, (idx2 + 1) * W, H)) for idx2 in range(n)]

    # Her frame'i ayri single-frame GIF olarak kaydet -> tam 320x170 LZW verisi
    single_gifs = []
    for pf in p_frames:
        buf = io.BytesIO()
        pf.save(buf, format='GIF', optimize=False)
        single_gifs.append(buf.getvalue())

    # Frame[0]'dan GCT al
    f0 = single_gifs[0]
    gct_exp  = f0[10] & 0x07
    gct_n    = 2 ** (gct_exp + 1)
    hdr_size = 13 + gct_n * 3  # header + GCT sonu

    def extract_lzw(sg, hdr_size):
        # Tek-frame GIF'ten Image Descriptor sonrasindaki LZW bloklarini ayikla
        pos = hdr_size
        while sg[pos] != 0x2C:
            if sg[pos] == 0x21:
                pos += 2
                while sg[pos]: pos += 1 + sg[pos]
                pos += 1
            else:
                pos += 1
        local_flags = sg[pos + 9]
        pos += 10
        if local_flags & 0x80:                     # LCT varsa atla
            pos += 3 * (2 ** ((local_flags & 7) + 1))
        # LZW min_code_size + veri bloklari + terminator
        end = pos + 1
        while sg[end]: end += 1 + sg[end]
        end += 1
        return sg[pos:end]

    delay_cs = 150 // 10  # 15 centisecond

    result = bytearray()
    result += b'GIF89a'
    result += f0[6 : hdr_size]        # LSD (7 byte) + GCT
    # Netscape sonsuz dongu uzantisi
    result += b'\x21\xff\x0b' + b'NETSCAPE2.0' + b'\x03\x01\x00\x00\x00'

    for sg in single_gifs:
        lzw = extract_lzw(sg, hdr_size)
        result += b'\x21\xf9\x04\x00'             # GCE: disposal=0, no transparency
        result += struct.pack('<H', delay_cs)
        result += b'\x00\x00'
        result += b'\x2c'                          # Image Descriptor
        result += b'\x00\x00\x00\x00'             # left=0, top=0
        result += struct.pack('<HH', W, H)         # tam 320x170
        result += b'\x00'                          # LCT yok, interlace yok
        result += lzw
    result += b'\x3b'

    with open(out_path, 'wb') as f:
        f.write(result)
    print("Kaydedildi: %s" % out_path)
    print("  %d kaynak frame -> %d frame" % (len(frames), len(out_frames)))
    print("  %d renk, pixelate=%d" % (colors, pixelate))
    print("  GIF boyutu: %d byte" % os.path.getsize(out_path))
    return out_path

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Kullanim: python preprocess_gif.py <gif_path> [colors=4] [pixelate=80] [max_frames=4]")
        sys.exit(1)
    gif  = sys.argv[1]
    cols = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    pix  = int(sys.argv[3]) if len(sys.argv) > 3 else 80
    mf   = int(sys.argv[4]) if len(sys.argv) > 4 else 4
    process(gif, cols, pix, mf)
