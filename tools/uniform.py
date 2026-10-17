"""Repaint Sofia's Rocketbox lab coat texture as the VisionOttica female uniform.

- light-blue shirt  -> bordeaux silk foulard (shading preserved)
- hospital ID card  -> bordeaux VisionOttica name badge "SOFIA"
- right chest panel -> pocket with bordeaux piping + logo
Usage: python3 uniform.py <src_body_color.tga> <logo_color.png> <logo_light.png> <out.jpg>
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

src, logo_c, logo_l, out = sys.argv[1:5]
im = Image.open(src).convert('RGB')
a = np.asarray(im).astype(np.float32)
r, g, b = a[..., 0], a[..., 1], a[..., 2]

# ---- 1. shirt -> ivory top, with a bordeaux silk foulard at the neckline
mask = ((b > r + 22) & (b > 110)).astype(np.uint8) * 255
m = Image.fromarray(mask).filter(ImageFilter.MaxFilter(11)).filter(ImageFilter.MinFilter(11))  # close button holes
m = np.asarray(m) > 0
m &= a.mean(axis=2) > 35  # never paint the black background
lum = (0.3 * r + 0.59 * g + 0.11 * b)
L = lum / np.median(lum[m])  # relative fabric shading
H, W = lum.shape
yy, xx = np.mgrid[0:H, 0:W]
ivory = np.array([226, 222, 214], np.float32)[None, None, :] * np.clip(L[..., None], 0.55, 1.15)
# foulard region: the collar band (wraps the neck) + a draped triangle with a knot under the chin
fz = Image.new('L', (W, H), 0); fd = ImageDraw.Draw(fz)
fd.rectangle((60, 1020, 680, 1320), fill=255)                         # shirt collar band
fd.polygon([(840, 960), (1160, 960), (1075, 1110), (1000, 1260), (930, 1110)], fill=255)  # drape
fd.ellipse((955, 985, 1045, 1065), fill=255)                          # knot
fz = np.asarray(fz.filter(ImageFilter.GaussianBlur(3))) / 255.0
twill = 0.05 * np.sin((xx + yy) * 0.9)
motif = (((xx // 22) + (yy // 22)) % 2 == 0) & (np.hypot(xx % 22 - 11, yy % 22 - 11) < 3.2)
base = np.array([92, 13, 33], np.float32)
silk = base[None, None, :] * (np.clip(L, 0.5, 1.4)[..., None] ** 1.3) * (1 + twill[..., None])
silk = np.where(motif[..., None], silk * 1.25 + np.array([10, 5, 6]), silk)
silk = silk + np.clip((L - 1.05) * 1.2, 0, 1)[..., None] * np.array([45, 22, 26])  # sheen on folds
col = ivory * (1 - fz[..., None]) + silk * fz[..., None]
a = np.where(m[..., None], np.clip(col, 0, 255), a)
im = Image.fromarray(a.astype(np.uint8))
d = ImageDraw.Draw(im)

# ---- 2. name badge (replaces the hospital ID card)
bx0, by0, bx1, by1 = 1157, 27, 1293, 109
d.rounded_rectangle((bx0, by0, bx1, by1), radius=8, fill=(126, 22, 48), outline=(96, 14, 34), width=2)
logo = Image.open(logo_l).convert('RGBA')
lw, lh = logo.size
logo = logo.crop((0, 0, lw, int(lh * 0.80)))  # symbol + wordmark, no tagline
white = Image.new('RGBA', logo.size, (255, 255, 255, 255)); white.putalpha(logo.getchannel('A'))
tw = 70; th = int(white.height * tw / white.width)
im.paste(white.resize((tw, th), Image.LANCZOS), ((bx0 + bx1 - tw) // 2, by0 + 5), white.resize((tw, th), Image.LANCZOS))
d.rectangle((bx0 + 10, by1 - 30, bx1 - 10, by1 - 10), fill=(246, 244, 242))
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf', 15)
d.text(((bx0 + bx1) / 2, by1 - 20), 'SOFIA', fill=(70, 18, 30), font=font, anchor='mm')

# ---- 3. chest pocket on Sofia's right front panel (opposite the badge, as on the real uniform)
px, py, pw, ph = 728, 1370, 124, 118
shade = Image.new('RGBA', im.size, (0, 0, 0, 0)); sd = ImageDraw.Draw(shade)
sd.rectangle((px, py, px + pw, py + ph), outline=(0, 0, 0, 38), width=3)   # pocket seam
sd.line((px + 4, py + ph - 3, px + pw - 4, py + ph - 3), fill=(0, 0, 0, 30), width=6)  # soft shadow at bottom
im = Image.alpha_composite(im.convert('RGBA'), shade.filter(ImageFilter.GaussianBlur(1.2)))
d = ImageDraw.Draw(im)
d.line((px + 2, py + 6, px + pw - 2, py + 6), fill=(118, 20, 44), width=5)  # bordeaux piping
lc = Image.open(logo_c).convert('RGBA')
lc = lc.crop((0, 0, lc.width, int(lc.height * 0.80)))
ew = 104; eh = int(lc.height * ew / lc.width * 0.62)  # panel is foreshortened sideways on the body: pre-squash height
emb = lc.resize((ew, eh), Image.LANCZOS)
im.paste(emb, (px + (pw - ew) // 2, py + 34), emb)
im.convert('RGB').save(out, quality=90)
print('ok', out)
