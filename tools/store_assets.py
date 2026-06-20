#!/usr/bin/env python3
"""
Ryu 네이티브 앱 - 출시 자료 생성기
  python tools/store_assets.py icon                 # base_icon.svg + Ryu배지 + 둥근모서리 -> 런처 PNG + icon_512
  python tools/store_assets.py banner --title "My App" --sub "한 줄 소개"
  python tools/store_assets.py shots ./raw_shots    # 폴더 캡처 -> 731x1300
의존성:  pip install cairosvg pillow --break-system-packages
"""
import os, sys, io, argparse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES  = os.path.join(ROOT, "app/src/main/res")
OUT  = os.path.join(HERE, "store_out")
ICON_RADIUS = 0.1875   # 둥근 모서리 비율(512 기준 약 96px)

RYU_BADGE = '''
  <circle cx="410" cy="102" r="74" fill="#ffffff" stroke="#cfe3fb" stroke-width="5"/>
  <text x="394" y="140" font-family="Poppins" font-weight="700" font-size="104" fill="#5b9be8" text-anchor="middle">R</text>
  <g transform="translate(436,82) scale(1.35)" fill="#5b9be8"><ellipse cx="3" cy="1" rx="15" ry="9"/><circle cx="-11" cy="-2" r="7.5"/><circle cx="-19" cy="0" r="2.3"/><circle cx="-6" cy="-11" r="6"/><path d="M16 4q14 3 7 -9q-4 -7 9 -8" fill="none" stroke="#5b9be8" stroke-width="2.4" stroke-linecap="round"/></g>
'''

def _svg2png(svg, w, h):
    import cairosvg
    return cairosvg.svg2png(bytestring=svg.encode(), output_width=w, output_height=h)

def _with_badge(base_svg):
    return base_svg.replace("</svg>", RYU_BADGE + "</svg>")

def _round(im, frac=ICON_RADIUS):
    from PIL import Image, ImageDraw
    w, h = im.size; r = int(min(w, h) * frac)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w, h), radius=r, fill=255)
    out = im.convert("RGBA"); out.putalpha(mask); return out

def cmd_icon(args):
    from PIL import Image, ImageDraw
    base = open(os.path.join(HERE, "base_icon.svg"), encoding="utf-8").read()
    svg = _with_badge(base)
    os.makedirs(OUT, exist_ok=True)
    # 스토어 512 (둥근 모서리)
    _round(Image.open(io.BytesIO(_svg2png(svg, 512, 512)))).save(os.path.join(OUT, "icon_512.png"))
    dens = {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}
    for d, sz in dens.items():
        folder = os.path.join(RES, f"mipmap-{d}"); os.makedirs(folder, exist_ok=True)
        im = Image.open(io.BytesIO(_svg2png(svg, sz, sz))).convert("RGBA")
        _round(im).save(os.path.join(folder, "ic_launcher.png"))          # 둥근 사각
        mask = Image.new("L", (sz, sz), 0); ImageDraw.Draw(mask).ellipse((0,0,sz,sz), fill=255)
        rnd = im.copy(); rnd.putalpha(mask); rnd.save(os.path.join(folder, "ic_launcher_round.png"))  # 원형
    print("아이콘 생성(둥근모서리) 완료 ->", os.path.join(OUT,"icon_512.png"), "+ res/mipmap-*")

def cmd_banner(args):
    title = args.title or "App Name"; sub = args.sub or "Play your app"
    badge = RYU_BADGE.replace('cx="410" cy="102"','cx="900" cy="120"').replace('x="394" y="140"','x="884" y="158"').replace('translate(436,82)','translate(926,100)')
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="578" viewBox="0 0 1024 578">
      <rect width="1024" height="578" fill="#0f1115"/>
      <rect width="1024" height="578" fill="#5b9be8" opacity="0.06"/>
      <text x="80" y="270" font-family="Poppins, Arial" font-size="72" font-weight="700" fill="#eaf1fb">{title}</text>
      <text x="82" y="330" font-family="Arial" font-size="26" fill="#9bb2cf">{sub}</text>
      {badge}
    </svg>'''
    os.makedirs(OUT, exist_ok=True)
    open(os.path.join(OUT, "banner_1024x578.png"), "wb").write(_svg2png(svg, 1024, 578))
    print("배너 생성 ->", os.path.join(OUT, "banner_1024x578.png"))

def cmd_shots(args):
    from PIL import Image
    src = args.folder; out = os.path.join(OUT, "screenshots"); os.makedirs(out, exist_ok=True)
    TW, TH, BG = 731, 1300, (15,17,21); n = 0
    for f in sorted(os.listdir(src)):
        if not f.lower().endswith((".png",".jpg",".jpeg",".webp")): continue
        im = Image.open(os.path.join(src, f)).convert("RGB")
        g = im.convert("L"); bb = g.point(lambda p: 255 if p < 240 else 0).getbbox()
        if bb: im = im.crop(bb)
        w, h = im.size; s = min(TW/w, TH/h)
        im2 = im.resize((max(1,int(w*s)), max(1,int(h*s))), Image.LANCZOS)
        cv = Image.new("RGB", (TW, TH), BG); cv.paste(im2, ((TW-im2.width)//2, (TH-im2.height)//2))
        n += 1; cv.save(os.path.join(out, f"shot_{n}.png"))
    print(f"스크린샷 {n}장 가공 -> {out}")

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("icon")
    b = sub.add_parser("banner"); b.add_argument("--title"); b.add_argument("--sub")
    s = sub.add_parser("shots"); s.add_argument("folder")
    a = ap.parse_args()
    {"icon":cmd_icon,"banner":cmd_banner,"shots":cmd_shots}[a.cmd](a)
