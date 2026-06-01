from PIL import Image, ImageDraw, ImageFont
import os

OUT_DIR = os.path.join(os.getcwd(), "gif_experiment_outputs")
os.makedirs(OUT_DIR, exist_ok=True)
OUTPUT = os.path.join(OUT_DIR, "aorus_test_320x170.gif")
W, H = 320, 170

frames = []

# Frame 1: Solid kirmizi + beyaz yazi
f1 = Image.new("RGB", (W, H), (220, 30, 30))
d = ImageDraw.Draw(f1)
d.rectangle([0, 0, W-1, 3], fill=(255,255,255))   # ust serit
d.rectangle([0, H-4, W-1, H-1], fill=(255,255,255)) # alt serit - alt kesim test
d.rectangle([0, 0, 3, H-1], fill=(255,255,255))   # sol
d.rectangle([W-4, 0, W-1, H-1], fill=(255,255,255)) # sag
frames.append(f1)

# Frame 2: Solid yesil + kenarlÄ±k
f2 = Image.new("RGB", (W, H), (30, 180, 30))
d = ImageDraw.Draw(f2)
d.rectangle([0, 0, W-1, 3], fill=(255,255,255))
d.rectangle([0, H-4, W-1, H-1], fill=(255,255,255))
d.rectangle([0, 0, 3, H-1], fill=(255,255,255))
d.rectangle([W-4, 0, W-1, H-1], fill=(255,255,255))
frames.append(f2)

# Frame 3: Solid mavi + kenarlÄ±k
f3 = Image.new("RGB", (W, H), (30, 80, 220))
d = ImageDraw.Draw(f3)
d.rectangle([0, 0, W-1, 3], fill=(255,255,255))
d.rectangle([0, H-4, W-1, H-1], fill=(255,255,255))
d.rectangle([0, 0, 3, H-1], fill=(255,255,255))
d.rectangle([W-4, 0, W-1, H-1], fill=(255,255,255))
frames.append(f3)

# Frame 4: Solid sari + kenarlÄ±k
f4 = Image.new("RGB", (W, H), (230, 200, 0))
d = ImageDraw.Draw(f4)
d.rectangle([0, 0, W-1, 3], fill=(0,0,0))
d.rectangle([0, H-4, W-1, H-1], fill=(0,0,0))
d.rectangle([0, 0, 3, H-1], fill=(0,0,0))
d.rectangle([W-4, 0, W-1, H-1], fill=(0,0,0))
frames.append(f4)

# Frame 5: Beyaz arka plan + renkli bantlar (alt kismÄ±n gorunup gorunmedigi test)
f5 = Image.new("RGB", (W, H), (255, 255, 255))
d = ImageDraw.Draw(f5)
band_h = H // 5
colors = [(220,30,30), (30,180,30), (30,80,220), (200,150,0), (150,0,200)]
for i, c in enumerate(colors):
    d.rectangle([0, i*band_h, W-1, (i+1)*band_h - 1], fill=c)
frames.append(f5)

# GIF olarak kaydet - tum frameler 320x170 native, hicbir scaling yok
frames[0].save(
    OUTPUT,
    format="GIF",
    save_all=True,
    append_images=frames[1:],
    duration=800,   # 800ms per frame
    loop=0,         # sonsuz dongu
    optimize=False
)

size = os.path.getsize(OUTPUT)
print(f"GIF olusturuldu: {OUTPUT}")
print(f"Boyut: {size} byte ({size/1024:.1f} KB)")
print(f"Cozunurluk: {W}x{H}")
print(f"Frame sayisi: {len(frames)}")
print()
print("ONEMLI: Bu dosyayi GCC uzerinden Custom GIF olarak yukle.")
print("Beklenen: Tum LCD dolmali, beyaz kenarlÄ±klar alt kisimda da gorunmeli.")
print("Eger alt kisim gorunuyorsa: GIF kanali calisÄ±yor!")
