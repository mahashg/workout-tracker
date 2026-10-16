import numpy as np, math, io, base64, json
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

def load(p): return Image.open(p).convert('RGB')

def muted_of(im):
    m = ImageEnhance.Color(im).enhance(0.20)
    m = ImageEnhance.Brightness(m).enhance(1.04)
    slate = Image.new('RGB', im.size, (148,163,184))
    return Image.blend(m, slate, 0.10)

def hot_of(im):
    a = np.asarray(im).astype(np.float32)
    R,G,B = a[:,:,0],a[:,:,1],a[:,:,2]
    gate = np.clip((R - np.maximum(G,B) - 8)/45.0, 0, 1)
    hot = np.asarray(ImageEnhance.Color(im).enhance(1.55)).astype(np.float32)
    hot[:,:,0] = np.clip(hot[:,:,0] + 55*gate, 0, 255)
    hot[:,:,1] = np.clip(hot[:,:,1] * (1-0.22*gate), 0, 255)
    hot[:,:,2] = np.clip(hot[:,:,2] * (1-0.22*gate), 0, 255)
    return Image.fromarray(hot.astype(np.uint8)), gate

def ellipse_poly(cx, cy, rx, ry, rot_deg=0, n=48):
    t = np.linspace(0, 2*np.pi, n)
    x = cx + rx*np.cos(t); y = cy + ry*np.sin(t)
    r = math.radians(rot_deg)
    xr = cx + (x-cx)*math.cos(r) - (y-cy)*math.sin(r)
    yr = cy + (x-cx)*math.sin(r) + (y-cy)*math.cos(r)
    return list(zip(xr, yr))

def mask_for(size, shapes):
    W,H = size
    m = Image.new('L', (W,H), 0)
    d = ImageDraw.Draw(m)
    for kind, pts in shapes:
        if kind=='poly':
            d.polygon([(x*W, y*H) for x,y in pts], fill=255)
        elif kind=='ellipse':
            (cx,cy,rx,ry,rot) = pts
            d.polygon([(x*W, y*H) for x,y in ellipse_poly(cx,cy,rx,ry,rot)], fill=255)
    return m.filter(ImageFilter.GaussianBlur(9))

def E(cx,cy,rx,ry,rot=0): return ('ellipse',(cx,cy,rx,ry,rot))
def P(pts): return ('poly', pts)

FRONT = {
 'shoulders': [E(0.350,0.198,0.090,0.043,-8), E(0.680,0.179,0.060,0.039,8)],
 'chest':     [E(0.455,0.225,0.185,0.050,0)],
 'biceps':    [E(0.252,0.283,0.042,0.072,42), E(0.775,0.270,0.042,0.072,-40)],
 'core':      [E(0.510,0.357,0.095,0.078,0)],
 'quads':     [E(0.395,0.550,0.098,0.110,3), E(0.600,0.550,0.098,0.110,-3)],
}
BACK = {
 'traps':   [P([(0.53,0.088),(0.78,0.19),(0.53,0.385),(0.26,0.19)])],
 'back':    [P([(0.225,0.25),(0.815,0.25),(0.665,0.485),(0.375,0.485)])],
 'triceps': [E(0.250,0.288,0.048,0.068,16), E(0.750,0.285,0.048,0.068,-14)],
 'glutes':  [E(0.375,0.488,0.130,0.052,0), E(0.645,0.490,0.130,0.052,0)],
 'hamstrings':[E(0.425,0.650,0.088,0.100,3), E(0.595,0.650,0.088,0.100,-3)],
 'calves':  [E(0.372,0.812,0.072,0.078,0), E(0.608,0.812,0.072,0.078,0)],
}

def make_logo(base_im, shapes, out_px=228, gain=1.0):
    W,H = base_im.size
    mut = muted_of(base_im)
    hot, gate = hot_of(base_im)
    m = np.asarray(mask_for((W,H), shapes)).astype(np.float32)/255.0
    blend = np.clip(m * (0.30 + 0.70*gate) * gain, 0, 1)
    a = np.asarray(mut).astype(np.float32); b = np.asarray(hot).astype(np.float32)
    out = a*(1-blend[...,None]) + b*blend[...,None]
    fig = Image.fromarray(np.clip(out,0,255).astype(np.uint8))
    # frame on square white canvas, figure height 96%
    S = 480
    scale = (S*0.96)/H
    fig2 = fig.resize((round(W*scale), round(H*scale)), Image.LANCZOS)
    canvas = Image.new('RGB', (S,S), (255,255,255))
    canvas.paste(fig2, ((S-fig2.width)//2, (S-fig2.height)//2))
    return canvas.resize((out_px,out_px), Image.LANCZOS)

front = load('front_clean.png'); back = load('back_clean.png')
logos = {}
GAIN = {'biceps':1.5, 'triceps':1.2, 'shoulders':1.12}
for part, shapes in FRONT.items(): logos[part] = make_logo(front, shapes, gain=GAIN.get(part,1.0))
for part, shapes in BACK.items():  logos[part] = make_logo(back, shapes, gain=GAIN.get(part,1.0))
for part, im in logos.items(): im.save(f'logo_{part}.jpg', quality=88)
print('made', sorted(logos))

# contact sheet: 11 at bubble-ish 150px + names omitted (visual check only)
order = ['shoulders','traps','chest','triceps','back','biceps','quads','hamstrings','glutes','calves','core']
tile=170; cols=4; rows=3
sheet = Image.new('RGB',(cols*tile, rows*tile),(240,238,245))
for i,part in enumerate(order):
    t = logos[part].resize((tile-14,tile-14), Image.LANCZOS)
    sheet.paste(t, ((i%cols)*tile+7, (i//cols)*tile+7))
sheet.save('contact.png'); print('contact saved', sheet.size)
