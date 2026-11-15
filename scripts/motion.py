#!/usr/bin/env python3
"""시연 영상 모션그래픽 (11/15 대표: "영상 모션그래픽으로 퀄리티 있게").

Pillow 로 프레임을 그리고 ffmpeg 파이프로 인코딩한다. 새 프로그램 설치 없이 돈다.
  python scripts/motion.py opening <out.mp4>                      # 오프닝 8초 (내비가 끝나는 곳 → 마지막 300 m → 앱 이름)
  python scripts/motion.py appeal <out.mp4>                       # 오프닝 뒤 13초 '왜 라스트마일인가' (단속 연 221만 건 → 다른 점 넷)
  python scripts/motion.py closing <out.mp4>                      # 마무리 6초 (핵심 세 줄)
  python scripts/motion.py polish <body.mp4> <out.mp4> <plan.json>  # 본편 전체: 강조 확대 여러 곳 + 왼쪽 아래 진행 막대(3 km·1 km·300 m·도착·승하차)
        # plan.json 은 condense.py --cues-out 시각으로 만든다 (scripts/demo_motion_plan.py)
  python scripts/motion.py highlight <src.mp4> <out.mp4> <t0> <dur> <x,y,w,h> <zoom_at> <hold> "<라벨>" [box x,y,w,h]
        # src 의 t0 부터 dur 초를 잘라, zoom_at 초에 x,y,w,h 영역으로 부드럽게 다가가 hold 초 머문 뒤 돌아온다.
        # box(원본 좌표)가 있으면 확대된 화면에서 그 둘레에 강조 테두리와 라벨을 붙인다. 소리는 원본 그대로.
색·글꼴은 앱(Pleos 토큰)과 같다: 바탕 #F7F8FA, 글자 #131417, 강조 파랑 #0082FF, 초록 #00B85E.
"""
import math, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1920, 1080, 30
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
BG = (247, 248, 250); INK = (19, 20, 23); SUB = (104, 106, 114); LINE = (224, 225, 230)
BLUE = (0, 130, 255); GREEN = (0, 184, 94); RED = (255, 76, 40)


def font(size, bold=False):
    # AppleSDGothicNeo.ttc: index 0 Regular, 6 Bold 근처 — 굵기는 인덱스로 고른다
    return ImageFont.truetype(FONT, size, index=6 if bold else 0)


def clamp01(x): return max(0.0, min(1.0, x))
def ease_out(x): x = clamp01(x); return 1 - (1 - x) ** 3
def ease_in_out(x): x = clamp01(x); return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2
def prog(t, a, b): return clamp01((t - a) / (b - a)) if b > a else float(t >= a)
def lerp(a, b, p): return a + (b - a) * p
def mix(c1, c2, p): return tuple(int(lerp(a, b, p)) for a, b in zip(c1, c2))


def writer(out, sec=None, audio_from=None, audio_ss=None):
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-"]
    if audio_from:
        cmd += ["-ss", f"{audio_ss:.3f}", "-t", f"{sec:.3f}", "-i", audio_from, "-map", "0:v", "-map", "1:a?", "-c:a", "aac", "-b:a", "160k"]
    else:
        cmd += ["-f", "lavfi", "-t", f"{sec:.3f}", "-i", "anullsrc=r=48000:cl=stereo", "-map", "0:v", "-map", "1:a", "-c:a", "aac", "-b:a", "160k"]
    cmd += ["-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", "-r", str(FPS), "-shortest", "-movflags", "+faststart", out]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE)


def text_center(d, cx, y, s, f, fill):
    w = d.textlength(s, font=f); d.text((cx - w / 2, y), s, font=f, fill=fill)


def alpha_layer(base, draw_fn, a):
    """draw_fn(ImageDraw) 로 그린 층을 투명도 a 로 얹는다."""
    if a <= 0: return
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0)); draw_fn(ImageDraw.Draw(layer))
    if a < 1:
        r, g, b, al = layer.split(); al = al.point(lambda v: int(v * a)); layer = Image.merge("RGBA", (r, g, b, al))
    base.alpha_composite(layer)


# ---------------------------------------------------------------- opening
def opening(out, sec=8.0):
    p = writer(out, sec)
    f_cap, f_bub, f_chip = font(30), font(32), font(34, True)
    f_title, f_sub, f_small = font(116, True), font(46), font(28)
    y = 540; x0, xp, x1 = 200, 1000, 1720
    n = int(sec * FPS)
    for i in range(n):
        t = i / FPS
        im = Image.new("RGBA", (W, H), BG + (255,)); d = ImageDraw.Draw(im)
        out_a = 1 - ease_in_out(prog(t, 4.8, 5.4))          # 1·2막이 사라지는 정도
        dy = -60 * ease_in_out(prog(t, 4.8, 5.4))

        def scene(dd):
            # 지금의 내비: 경로선이 목적지 핀까지
            dd.text((x0, 300 + dy), "지금의 내비", font=f_cap, fill=SUB + (int(255 * ease_out(prog(t, 0.2, 0.7))),))
            pr = ease_in_out(prog(t, 0.3, 1.6)); xe = lerp(x0, xp, pr)
            dd.rounded_rectangle([x0, y - 7 + dy, xe, y + 7 + dy], radius=7, fill=BLUE + (255,))
            if pr < 1:
                dd.ellipse([xe - 16, y - 16 + dy, xe + 16, y + 16 + dy], fill=(255, 255, 255, 255), outline=BLUE + (255,), width=6)
            pin = ease_out(prog(t, 1.5, 1.9))
            if pin > 0:
                r = 22 * pin
                dd.ellipse([xp - r, y - r + dy, xp + r, y + r + dy], fill=INK + (255,))
                dd.ellipse([xp - r * 0.4, y - r * 0.4 + dy, xp + r * 0.4, y + r * 0.4 + dy], fill=(255, 255, 255, 255))
            # 내비 말풍선
            ba = ease_out(prog(t, 1.8, 2.3))
            if ba > 0:
                s = "“목적지 부근입니다. 안내를 종료합니다.”"; tw = dd.textlength(s, font=f_bub)
                bx0 = xp - tw / 2 - 30; by0 = y - 150 + dy + 14 * (1 - ba)
                dd.rounded_rectangle([bx0, by0, bx0 + tw + 60, by0 + 76], radius=38, fill=(255, 255, 255, int(255 * ba)), outline=LINE + (int(255 * ba),), width=2)
                dd.text((bx0 + 30, by0 + 18), s, font=f_bub, fill=INK + (int(255 * ba),))
            # 마지막 300 m: 초록 점선이 이어진다
            g = ease_in_out(prog(t, 2.7, 3.7)); xg = lerp(xp + 30, x1, g)
            xx = xp + 30
            while xx < xg:
                dd.rounded_rectangle([xx, y - 7 + dy, min(xx + 34, xg), y + 7 + dy], radius=7, fill=GREEN + (255,)); xx += 52
            la = ease_out(prog(t, 3.2, 3.7))
            if la > 0:
                s = "마지막 300 m"; tw = dd.textlength(s, font=f_chip)
                dd.text(((xp + x1) / 2 - tw / 2 + 15, y + 34 + dy + 10 * (1 - la)), s, font=f_chip, fill=GREEN + (int(255 * la),))
            for k, s in enumerate(["세워도 되나?", "어디에 세우지?", "정말 내렸나?"]):
                ca = ease_out(prog(t, 3.5 + 0.28 * k, 3.95 + 0.28 * k))
                if ca <= 0: continue
                tw = dd.textlength(s, font=f_chip); cx = (xp + x1) / 2 + 15 + (k - 1) * 290; cy = y + 130 + dy + 18 * (1 - ca)
                dd.rounded_rectangle([cx - tw / 2 - 26, cy, cx + tw / 2 + 26, cy + 68], radius=34, fill=(237, 238, 242, int(255 * ca)))
                dd.text((cx - tw / 2, cy + 13), s, font=f_chip, fill=INK + (int(255 * ca),))
            na = ease_out(prog(t, 3.9, 4.4))
            if na > 0: dd.text((x0, y + 150 + dy), "그다음은 아무도 맡지 않는다.", font=f_cap, fill=SUB + (int(255 * na),))

        alpha_layer(im, scene, out_a)

        # 3막: 앱 이름
        ta = ease_out(prog(t, 5.2, 5.9)); sa = ease_out(prog(t, 5.6, 6.3)); ma = ease_out(prog(t, 6.2, 6.8))
        def title(dd):
            s = "라스트마일"; tw = dd.textlength(s, font=f_title); cx = W / 2; ty = 400 + 24 * (1 - ta)
            dd.ellipse([cx - tw / 2 - 64, ty + 46, cx - tw / 2 - 28, ty + 82], fill=GREEN + (int(255 * ta),))
            dd.text((cx - tw / 2, ty), s, font=f_title, fill=INK + (int(255 * ta),))
            s2 = "내비가 끝나는 마지막 300 m를 차가 이어받는다"; tw2 = dd.textlength(s2, font=f_sub)
            dd.text((cx - tw2 / 2, 560 + 16 * (1 - sa)), s2, font=f_sub, fill=(68, 70, 78, int(255 * sa)))
            s3 = "Pleos Connect 에뮬레이터 실제 구동 화면 · 내장 내비 주행 시뮬레이션"; tw3 = dd.textlength(s3, font=f_small)
            dd.text((cx - tw3 / 2, 660), s3, font=f_small, fill=SUB + (int(255 * ma),))
        alpha_layer(im, title, max(ta, 0.0001) if ta > 0 else 0)
        p.stdin.write(im.convert("RGB").tobytes())
    p.stdin.close(); p.wait()


MUSIC_CREDIT = "배경음악 Corporate Motivational Ambient · AShamaluevMusic"   # 대표가 고른 곡(11/15), 출처 표기


# ---------------------------------------------------------------- appeal
APPEAL = [
    ("세워도 되는지 미리 알려 준다", "목적지 부근 차선 규칙을 3 km 전부터 · 황색 점선은 5분까지, 실선은 정차할 수 없다"),
    ("폰이 모르는 걸 차가 안다", "브레이크 · 기어 P · 승객석 문 신호로 ‘멈췄다, 내렸다’를 확정"),
    ("주행 중 화면 전환 0회", "안내는 내비 위 한 줄 카드로 · 터치는 물을 때 ‘물어보기’ 한 번뿐"),   # 대표 11/15: 물어보기도 터치다 → "터치 0회" 금지
    ("갈수록 내 차에 맞춰진다", "길가에 내린 자리를 기억해 다음 방문 때 먼저 알려 준다"),
]


def appeal(out, sec=13.0):
    """오프닝 뒤 '왜 라스트마일인가' (11/15 대표: "처음에 왜 매력적인지 어필하는 부분"). 숫자는 기획서에 출처가 있는 것만."""
    p = writer(out, sec)
    f_big, f_cap, f_src = font(150, True), font(40, True), font(26)
    f_h, f_t, f_d, f_n = font(52, True), font(40, True), font(28), font(30, True)
    n = int(sec * FPS)
    for i in range(n):
        t = i / FPS
        im = Image.new("RGBA", (W, H), BG + (255,))
        # 1막 (0~3.4초): 문제의 크기
        a1 = ease_out(prog(t, 0.1, 0.6)) * (1 - ease_in_out(prog(t, 3.0, 3.5)))
        def hook(dd):
            cnt = int(221 * ease_out(prog(t, 0.2, 1.4)))
            s = f"{cnt}만 건"; tw = dd.textlength(s, font=f_big)
            dd.text((W / 2 - tw / 2, 400 - 30 * (1 - a1)), s, font=f_big, fill=RED + (255,))
            s2 = "서울시 불법 주정차 단속, 한 해 (2024년)"; tw2 = dd.textlength(s2, font=f_cap)
            dd.text((W / 2 - tw2 / 2, 600), s2, font=f_cap, fill=INK + (255,))
            b = ease_out(prog(t, 1.2, 1.7))
            s3 = "목적지 앞에서 ‘세워도 되나?’를 모른 채 세운 대가 · 황색 실선 앞 정차 과태료는 승용차 4만 원"; tw3 = dd.textlength(s3, font=f_src)
            dd.text((W / 2 - tw3 / 2, 680 + 10 * (1 - b)), s3, font=f_src, fill=SUB + (int(255 * b),))
        alpha_layer(im, hook, a1)
        # 2막 (3.3초~): 다른 점 넷
        a2 = ease_out(prog(t, 3.3, 3.8)) * (1 - ease_in_out(prog(t, sec - 0.6, sec)))
        def cards(dd):
            hs = "라스트마일이 다른 점"; tw = dd.textlength(hs, font=f_h)
            dd.text((W / 2 - tw / 2, 150 - 16 * (1 - a2)), hs, font=f_h, fill=INK + (255,))
            cw, ch, gx, gy = 780, 250, 40, 36; x0 = (W - 2 * cw - gx) / 2; y0 = 280
            for k, (title, desc) in enumerate(APPEAL):
                c = ease_out(prog(t, 3.7 + 0.45 * k, 4.3 + 0.45 * k))
                if c <= 0: continue
                cx = x0 + (k % 2) * (cw + gx); cy = y0 + (k // 2) * (ch + gy) + 24 * (1 - c); A = int(255 * c)
                dd.rounded_rectangle([cx, cy, cx + cw, cy + ch], radius=28, fill=(255, 255, 255, A), outline=LINE + (A,), width=2)
                col = GREEN if k % 2 == 0 else BLUE
                dd.ellipse([cx + 40, cy + 44, cx + 96, cy + 100], fill=col + (A,))
                ns = str(k + 1); nw = dd.textlength(ns, font=f_n); dd.text((cx + 68 - nw / 2, cy + 52), ns, font=f_n, fill=(255, 255, 255, A))
                dd.text((cx + 124, cy + 46), title, font=f_t, fill=INK + (A,))
                # 설명은 카드 폭에 맞춰 두 줄까지
                words, lines, cur = desc.split(" "), [], ""
                for w in words:
                    trial = (cur + " " + w).strip()
                    if dd.textlength(trial, font=f_d) <= cw - 164: cur = trial
                    else: lines.append(cur); cur = w
                lines.append(cur)
                for li, l in enumerate(lines[:2]): dd.text((cx + 124, cy + 120 + 42 * li), l, font=f_d, fill=(68, 70, 78, A))
            e = ease_out(prog(t, 9.5, 10.1))
            es = "지금부터 Pleos Connect 에뮬레이터 실제 구동 화면"; ew = dd.textlength(es, font=f_src)
            dd.text((W / 2 - ew / 2, 900 + 10 * (1 - e)), es, font=f_src, fill=SUB + (int(255 * e),))
        alpha_layer(im, cards, a2)
        p.stdin.write(im.convert("RGB").tobytes())
    p.stdin.close(); p.wait()


# ---------------------------------------------------------------- closing
def closing(out, sec=6.0):
    p = writer(out, sec)
    f1, f2, f3 = font(64, True), font(38), font(30)
    lines = ["세워도 되는 곳인지 · 어디에 · 정말 멈춰 내렸는지를 차량 신호로 판단",
             "주행 중엔 내비 위 한 줄 안내와 '물어보기' 버튼 하나",
             "길가에 내린 자리를 기억해 다음 방문 때 먼저 알려 준다"]
    n = int(sec * FPS)
    for i in range(n):
        t = i / FPS
        im = Image.new("RGBA", (W, H), BG + (255,))
        fade = 1 - ease_in_out(prog(t, sec - 0.5, sec))
        def draw(dd):
            a = ease_out(prog(t, 0.1, 0.7))
            dd.text((200, 300 + 20 * (1 - a)), "내비는 도착까지, 라스트마일은 세우고 내리기까지.", font=f1, fill=INK + (int(255 * a),))
            for k, s in enumerate(lines):
                b = ease_out(prog(t, 0.8 + 0.35 * k, 1.3 + 0.35 * k)); yy = 440 + 78 * k + 16 * (1 - b)
                dd.ellipse([200, yy + 14, 222, yy + 36], fill=GREEN + (int(255 * b),))
                dd.text((246, yy), s, font=f2, fill=(68, 70, 78, int(255 * b)))
            c = ease_out(prog(t, 2.4, 3.0))
            dd.text((200, 760), "라스트마일 · Pleos Connect 앱", font=f3, fill=SUB + (int(255 * c),))
            dd.text((200, 960), MUSIC_CREDIT, font=font(22), fill=SUB + (int(200 * c),))
        alpha_layer(im, draw, fade)
        p.stdin.write(im.convert("RGB").tobytes())
    p.stdin.close(); p.wait()


# ---------------------------------------------------------------- highlight
def highlight(src, out, t0, dur, rect, zoom_at, hold, label, box=None, ramp=0.7):
    rx, ry, rw, rh = rect
    reader = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{t0:.3f}", "-t", f"{dur:.3f}", "-i", src,
                               "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-"], stdout=subprocess.PIPE)
    p = writer(out, dur, audio_from=src, audio_ss=t0)
    f_lab = font(40, True)
    n = int(dur * FPS); size = W * H * 3
    for i in range(n):
        buf = reader.stdout.read(size)
        if len(buf) < size: break
        t = i / FPS
        frame = Image.frombytes("RGB", (W, H), buf)
        z = ease_in_out(prog(t, zoom_at, zoom_at + ramp)) * (1 - ease_in_out(prog(t, zoom_at + ramp + hold, zoom_at + 2 * ramp + hold)))
        cx0, cy0, cw0, ch0 = 0, 0, W, H
        cx, cy, cw, ch = lerp(cx0, rx, z), lerp(cy0, ry, z), lerp(cw0, rw, z), lerp(ch0, rh, z)
        im = frame.crop((int(cx), int(cy), int(cx + cw), int(cy + ch))).resize((W, H), Image.LANCZOS).convert("RGBA")
        # 강조: 다 다가간 뒤에만
        a = ease_out(prog(t, zoom_at + ramp, zoom_at + ramp + 0.45)) * (1 - ease_in_out(prog(t, zoom_at + ramp + hold - 0.3, zoom_at + ramp + hold)))
        if a > 0 and box:
            sx, sy = W / rw, H / rh
            bx, by, bw, bh = box
            x0b, y0b = (bx - rx) * sx, (by - ry) * sy; x1b, y1b = x0b + bw * sx, y0b + bh * sy
            pad = 14
            # 바깥을 살짝 어둡게 — 보는 곳만 남긴다
            mask = Image.new("L", (W, H), int(90 * a)); ImageDraw.Draw(mask).rounded_rectangle([x0b - pad, y0b - pad, x1b + pad, y1b + pad], radius=26, fill=0)
            scrim = Image.new("RGBA", (W, H), INK + (0,)); scrim.putalpha(mask); im.alpha_composite(scrim)
            def draw(dd):
                dd.rounded_rectangle([x0b - pad, y0b - pad, x1b + pad, y1b + pad], radius=26, outline=BLUE + (int(255 * a),), width=7)
                tw = dd.textlength(label, font=f_lab); lx = (x0b + x1b) / 2 - tw / 2 - 30; ly = min(H - 110, y1b + pad + 26) + 12 * (1 - a)
                dd.rounded_rectangle([lx, ly, lx + tw + 64, ly + 84], radius=42, fill=(19, 20, 23, int(235 * a)))
                dd.text((lx + 32, ly + 18), label, font=f_lab, fill=(255, 255, 255, int(255 * a)))
            alpha_layer(im, draw, 1.0)
        p.stdin.write(im.convert("RGB").tobytes())
    reader.stdout.close(); reader.wait(); p.stdin.close(); p.wait()


# ---------------------------------------------------------------- polish (본편 전체)
STEPS = ["3 km", "1 km", "300 m", "도착", "승하차"]


def progress_layer(title, nodes, t, a):
    """왼쪽 위, 장면 카드(condense.py situation, y 140~) 바로 위 진행 막대 — 아래쪽은 두 줄 자막이 올라와 겹친다. nodes = 각 단계에 닿은 시각(None 이면 아직).
    title 은 계획 파일에만 남기고 화면에는 안 쓴다 — 첫/다음 방문은 장면 카드가 말한다."""
    lw, lh = 600, 66
    lay = Image.new("RGBA", (lw, lh), (0, 0, 0, 0)); d = ImageDraw.Draw(lay)
    A = lambda v: int(v * a)
    d.rounded_rectangle([0, 0, lw - 1, lh - 1], radius=20, fill=(18, 22, 28, A(228)))
    xs = [60 + k * 120 for k in range(5)]; ny = 22; fl = font(19)
    reached = [n is not None and t >= n for n in nodes]
    # 채워진 선: 마지막으로 닿은 단계에서 다음 단계 쪽으로 시간 비율만큼
    fill_x = None
    for k in range(5):
        if reached[k]:
            fill_x = xs[k]
            if k + 1 < 5 and nodes[k + 1] is not None and not reached[k + 1]:
                fill_x = lerp(xs[k], xs[k + 1], clamp01((t - nodes[k]) / (nodes[k + 1] - nodes[k])))
    d.rounded_rectangle([xs[0], ny - 2, xs[-1], ny + 2], radius=2, fill=(70, 78, 90, A(255)))
    if fill_x is not None:
        d.rounded_rectangle([xs[0], ny - 2, fill_x, ny + 2], radius=2, fill=GREEN + (A(255),))
    for k, x in enumerate(xs):
        if reached[k]:
            pulse = prog(t, nodes[k], nodes[k] + 0.7)
            if 0 < pulse < 1:
                r = 9 + 14 * ease_out(pulse)
                d.ellipse([x - r, ny - r, x + r, ny + r], outline=GREEN + (A(255 * (1 - pulse)),), width=3)
            pop = 1 + 0.35 * math.sin(math.pi * prog(t, nodes[k], nodes[k] + 0.35))
            r = 9 * pop; d.ellipse([x - r, ny - r, x + r, ny + r], fill=GREEN + (A(255),))
        else:
            d.ellipse([x - 8, ny - 8, x + 8, ny + 8], fill=(18, 22, 28, A(255)), outline=(110, 118, 130, A(255)), width=3)
        tw = d.textlength(STEPS[k], font=fl)
        d.text((x - tw / 2, ny + 13), STEPS[k], font=fl, fill=((255, 255, 255) if reached[k] else (130, 138, 150)) + (A(255),))
    return lay


def polish(src, out, plan):
    """plan: {"visits": [{"title", "from", "to", "nodes": [5]}], "highlights": [{"at", "hold", "rect", "box", "label"}]}"""
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src]).decode())
    reader = subprocess.Popen(["ffmpeg", "-v", "error", "-i", src, "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-"], stdout=subprocess.PIPE)
    p = writer(out, dur, audio_from=src, audio_ss=0.0)
    f_lab = font(40, True); size = W * H * 3; ramp = 0.7; i = 0
    while True:
        buf = reader.stdout.read(size)
        if len(buf) < size: break
        t = i / FPS; i += 1
        frame = Image.frombytes("RGB", (W, H), buf)
        h = next((h for h in plan["highlights"] if h["at"] - 0.1 <= t <= h["at"] + 2 * ramp + h["hold"] + 0.1), None)
        z = 0.0
        if h:
            z = ease_in_out(prog(t, h["at"], h["at"] + ramp)) * (1 - ease_in_out(prog(t, h["at"] + ramp + h["hold"], h["at"] + 2 * ramp + h["hold"])))
        if z > 0:
            rx, ry, rw, rh = h["rect"]
            cx, cy, cw, ch = lerp(0, rx, z), lerp(0, ry, z), lerp(W, rw, z), lerp(H, rh, z)
            im = frame.crop((int(cx), int(cy), int(cx + cw), int(cy + ch))).resize((W, H), Image.LANCZOS).convert("RGBA")
            a = ease_out(prog(t, h["at"] + ramp, h["at"] + ramp + 0.45)) * (1 - ease_in_out(prog(t, h["at"] + ramp + h["hold"] - 0.3, h["at"] + ramp + h["hold"])))
            if a > 0:
                sx, sy = W / rw, H / rh; bx, by, bw, bh = h["box"]; pad = 14
                x0b, y0b = (bx - rx) * sx, (by - ry) * sy; x1b, y1b = x0b + bw * sx, y0b + bh * sy
                mask = Image.new("L", (W, H), int(90 * a)); ImageDraw.Draw(mask).rounded_rectangle([x0b - pad, y0b - pad, x1b + pad, y1b + pad], radius=26, fill=0)
                scrim = Image.new("RGBA", (W, H), INK + (0,)); scrim.putalpha(mask); im.alpha_composite(scrim)
                def draw(dd, label=h["label"]):
                    dd.rounded_rectangle([x0b - pad, y0b - pad, x1b + pad, y1b + pad], radius=26, outline=BLUE + (int(255 * a),), width=7)
                    tw = dd.textlength(label, font=f_lab); lx = min(max(40, (x0b + x1b) / 2 - tw / 2 - 32), W - 40 - tw - 64); ly = min(H - 110, y1b + pad + 26) + 12 * (1 - a)
                    dd.rounded_rectangle([lx, ly, lx + tw + 64, ly + 84], radius=42, fill=(19, 20, 23, int(235 * a)))
                    dd.text((lx + 32, ly + 18), label, font=f_lab, fill=(255, 255, 255, int(255 * a)))
                alpha_layer(im, draw, 1.0)
        else:
            im = frame.convert("RGBA")
        v = next((v for v in plan["visits"] if v["from"] <= t < v["to"]), None)
        if v:
            pa = ease_out(prog(t, v["from"], v["from"] + 0.4)) * (1 - z)
            if pa > 0.01: im.alpha_composite(progress_layer(v["title"], v["nodes"], t, pa), dest=(20, 64))
        p.stdin.write(im.convert("RGB").tobytes())
    reader.stdout.close(); reader.wait(); p.stdin.close(); p.wait()


def parse_rect(s): return tuple(float(v) for v in s.split(","))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "opening": opening(sys.argv[2])
    elif cmd == "closing": closing(sys.argv[2])
    elif cmd == "appeal": appeal(sys.argv[2])
    elif cmd == "polish":
        import json; polish(sys.argv[2], sys.argv[3], json.load(open(sys.argv[4], encoding="utf-8")))
    elif cmd == "highlight":
        src, out, t0, dur, rect, zoom_at, hold, label = sys.argv[2:10]
        box = parse_rect(sys.argv[10]) if len(sys.argv) > 10 else None
        highlight(src, out, float(t0), float(dur), parse_rect(rect), float(zoom_at), float(hold), label, box)
    else:
        raise SystemExit(__doc__)
