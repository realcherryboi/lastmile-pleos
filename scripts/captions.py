#!/usr/bin/env python3
"""시연 영상에 앱 음성 자막을 입힌다 (주행 중엔 내비가 앞에 있어 앱 음성이 화면에 안 보이므로).

사용: python scripts/captions.py <raw.mp4> <logcat_epoch.txt> <rec_start_epoch> <out.mp4> [--trim 초] [--note "자막 한 줄"]
  logcat_epoch.txt: `adb logcat -v epoch -s LastMile:D` 출력 (디버그 빌드, "🔊 문장" 줄의 epoch 초를 쓴다)
  rec_start_epoch : screenrecord 를 띄운 순간의 epoch 초 (date +%s.%N)
자막 PNG 는 Pillow 로 그리고 ffmpeg overlay(enable=between) 로 얹는다 (ffmpeg 에 libass/freetype 이 없어도 됨).
"""
import os, re, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, H = 1920, 1080

def parse(logcat, rec_start):
    cues = []
    for line in open(logcat, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*(\d+\.\d+)\s.*LastMile: 🔊 (.+)$", line)
        if not m: continue
        t = float(m.group(1)) - rec_start
        if t < 0: continue
        cues.append([t, m.group(2).strip()])
    # 다음 자막 전까지 또는 글자 수 기준(0.28초/자, 최소 4초 최대 9초)
    out = []
    for i, (t, text) in enumerate(cues):
        dur = min(9.0, max(4.0, 0.28 * len(text)))
        if i + 1 < len(cues): dur = min(dur, cues[i + 1][0] - t - 0.2)
        out.append((t, t + max(dur, 1.5), text))
    return out

def wrap(text, font, maxw, draw):
    words = text.split(" "); lines = []; cur = ""
    for w in words:
        trial = (cur + " " + w).strip()
        if draw.textlength(trial, font=font) <= maxw: cur = trial
        else: lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines

def card(text, path, note=None):
    font = ImageFont.truetype(FONT, 40, index=0); small = ImageFont.truetype(FONT, 26, index=0)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    lines = wrap(text, font, W - 2 * 140 - 80, d)
    lh = 54; pad = 28
    boxh = pad * 2 + lh * len(lines) + (34 if note else 0)
    boxw = min(W - 2 * 120, max(d.textlength(l, font=font) for l in lines) + 2 * pad + 60)
    x0 = (W - boxw) / 2; y0 = H - 120 - boxh
    d.rounded_rectangle([x0, y0, x0 + boxw, y0 + boxh], radius=22, fill=(18, 22, 28, 215))
    d.ellipse([x0 + pad, y0 + pad + 8, x0 + pad + 30, y0 + pad + 38], fill=(0, 224, 116, 255))   # 🔊 점
    y = y0 + pad
    for l in lines: d.text((x0 + pad + 46, y), l, font=font, fill=(255, 255, 255, 255)); y += lh
    if note: d.text((x0 + pad + 46, y + 2), note, font=small, fill=(170, 180, 190, 255))
    img.save(path)

def main():
    raw, logcat, rec_start, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
    trim = None; note = "라스트마일 음성 안내 (내비 화면 위에서 재생)"
    if "--trim" in sys.argv: trim = float(sys.argv[sys.argv.index("--trim") + 1])
    if "--note" in sys.argv: note = sys.argv[sys.argv.index("--note") + 1]
    cues = parse(logcat, rec_start)
    print(f"{len(cues)} cues"); [print(f"  {a:6.1f}-{b:6.1f}  {t[:60]}") for a, b, t in cues]
    tmp = tempfile.mkdtemp()
    inputs = ["-i", raw]; chain = []; prev = "[0:v]"
    for i, (a, b, t) in enumerate(cues):
        p = os.path.join(tmp, f"c{i}.png"); card(t, p, note); inputs += ["-i", p]
        chain.append(f"{prev}[{i+1}:v]overlay=0:0:enable='between(t,{a:.2f},{b:.2f})'[v{i}]"); prev = f"[v{i}]"
    fc = ";".join(chain)
    cmd = ["ffmpeg", "-v", "error", "-y"] + inputs + ["-filter_complex", f"[0:v]scale={W}:{H}[0:v];" + fc.replace("[0:v]", "[0:v]", 1), "-map", prev]
    # scale 을 먼저 적용하기 위해 체인 머리를 바꾼다
    fc2 = f"[0:v]scale={W}:{H}[base];" + fc.replace("[0:v]", "[base]", 1)
    cmd = ["ffmpeg", "-v", "error", "-y"] + inputs + (["-t", str(trim)] if trim else []) + ["-filter_complex", fc2, "-map", prev, "-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out]
    subprocess.run(cmd, check=True); print("saved", out, os.path.getsize(out) // 1024, "KB")

if __name__ == "__main__": main()
