"""Make Sofia's eyes read as human: larger, warmer hazel iris with a dark limbal ring, softer off-white sclera.
Usage: python3 eyes.py <head_color.tga> <out.jpg>"""
import sys, numpy as np
from PIL import Image
src, out = sys.argv[1:3]
im = np.asarray(Image.open(src).convert('RGB')).astype(np.float32)
cx, cy = 540, 1907            # iris centre in the 2048 head texture
R0, R1 = 33, 44               # original iris radius -> new iris radius
SCL = 112                     # sclera radius to tone down
H, W, _ = im.shape
y0, y1, x0, x1 = cy - SCL - 4, cy + SCL + 4, cx - SCL - 4, cx + SCL + 4
patch = im[y0:y1, x0:x1].copy(); orig = patch.copy()
yy, xx = np.mgrid[y0:y1, x0:x1]; dx, dy = xx - cx, yy - cy; r = np.hypot(dx, dy) + 1e-6
# 1) enlarge the iris: radial remap inside R1 (sample the original at r*R0/R1)
inside = r < R1
sx = cx + dx * (R0 / R1); sy = cy + dy * (R0 / R1)
def bilinear(img, x, y):
    x0_ = np.floor(x).astype(int); y0_ = np.floor(y).astype(int); fx = x - x0_; fy = y - y0_
    c = lambda yy_, xx_: img[np.clip(yy_, 0, H - 1), np.clip(xx_, 0, W - 1)]
    return (c(y0_, x0_) * ((1 - fx) * (1 - fy))[..., None] + c(y0_, x0_ + 1) * (fx * (1 - fy))[..., None]
            + c(y0_ + 1, x0_) * ((1 - fx) * fy)[..., None] + c(y0_ + 1, x0_ + 1) * (fx * fy)[..., None])
patch[inside] = bilinear(im, sx[inside], sy[inside])
# 2) warm hazel grade on the iris (keep the pupil and the original fibres as luminance)
iris = (r < R1) & (r > 9)
lum = patch[..., 0] * 0.3 + patch[..., 1] * 0.59 + patch[..., 2] * 0.11
hazel = np.stack([lum * 1.05 + 18, lum * 0.78 + 8, lum * 0.45], -1)
w = np.clip((r - 9) / 3, 0, 1)[..., None]
patch = np.where(iris[..., None], patch * (1 - 0.75 * w) + hazel * 0.75 * w, patch)
# 3) dark limbal ring + soft edge into the sclera
ring = np.exp(-((r - (R1 - 2.5)) / 2.6) ** 2)[..., None]
patch = patch * (1 - 0.55 * ring)
# 4) sclera: less paper-white, slightly warm, darker toward the corners
scl = (r >= R1) & (r < SCL)
fall = np.clip((r - R1) / (SCL - R1), 0, 1)[..., None]
tone = np.array([228, 216, 206], np.float32)
patch = np.where(scl[..., None], patch * 0.55 + tone * 0.45 * (1 - 0.35 * fall), patch)
im[y0:y1, x0:x1] = patch
Image.fromarray(np.clip(im, 0, 255).astype(np.uint8)).save(out, quality=90)
print('ok')
