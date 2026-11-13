#!/usr/bin/env python3
"""자막을 입힌 시연 영상에서 아무 일도 없는 주행 구간을 빠르게 돌린다 (잘라 내지 않고 배속 → 내비 화면이 이어진다).

사용: python scripts/condense.py <captioned.mp4> <logcat_epoch.txt> <rec_start_epoch> <out.mp4>
          [--shots 폴더] [--speed 8] [--start 초] [--before 3] [--after 2] [--mingap 10]
          [--scenes scenes.txt] [--title "제목|한 줄|작은 글"] [--closing "큰 문장|줄1|줄2|줄3"]
  1배속으로 남기는 구간 = 음성 자막(🔊 줄) 앞 before초 ~ 자막 끝 뒤 after초, 그리고 demo.sh 장면 캡처(--shots 폴더의 PNG mtime) 앞 4초 ~ 뒤 1초.
  그 사이가 --mingap 초보다 길 때만 --speed 배속으로 돌리고 오른쪽 위에 "▶▶ N배속" 배지를 얹는다(짧은 틈은 조작 장면이라 1배속).
  --start 앞은 버린다(초기화 조작).
  입력에 소리가 있으면(captions.py --voice) 1배속 구간은 그대로, 배속 구간은 무음으로 이어 붙인다. 자막 구간은 captions.py 가 남긴 <captioned>.cues.json 을 우선 쓴다.
  --scenes: 장면 라벨 파일. 한 줄에 `<기준>[±초]<탭>라벨[<탭>상황 설명]`. 상황 설명이 있으면 화면 왼쪽 클러스터 위에 제목+설명 카드로, 없으면 상태바 칩으로. 기준은 start | cue:N(N번째 🔊 시작) | cueend:N(그 끝) | shot:이름(캡처 PNG 이름, 확장자 없이).
           라벨은 다음 줄의 시각까지 보이고, 시스템 상태바의 빈 자리(헤드업 카드 오른쪽, 상태 아이콘 왼쪽)에 작은 칩으로 얹는다.
  --title / --closing: 앞뒤에 4초·6초 카드(페이드). 글은 `|` 로 줄을 나눈다. 기본 문구가 있으니 생략해도 된다.
"""
import os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from captions import load_cues, FONT, W, H
from PIL import Image, ImageDraw, ImageFont

BG = (18, 22, 28)
TITLE_DEFAULT = "라스트마일|내비가 끝나는 마지막 300 m를 차가 이어받는다|Pleos Connect 에뮬레이터 실제 구동 화면 · 내장 내비 주행 시뮬레이션 · 음성은 앱이 말한 문장을 합성"
CLOSING_DEFAULT = "내비는 도착까지, 라스트마일은 세우고 내리기까지.|세워도 되는 곳인지 · 어디에 · 정말 멈춰 내렸는지를 차량 신호로 판단|주행 중 터치 0회 · 내비 위 한 줄 헤드업으로만 말한다|내린 자리를 기억해 다음 방문에 제안"
TITLE_SEC, CLOSING_SEC, FADE = 4.0, 6.0, 0.5

def arg(name, default, cast=float):
    return cast(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default

def font(size): return ImageFont.truetype(FONT, size, index=0)

def badge(path, speed):
    f = font(30); img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    text = f"▶▶  주행 구간 {speed:g}배속"; tw = d.textlength(text, font=f)
    x1 = W - 60; x0 = x1 - tw - 56; y0 = 40
    d.rounded_rectangle([x0, y0, x1, y0 + 62], radius=18, fill=BG + (200,))
    d.text((x0 + 28, y0 + 13), text, font=f, fill=(255, 255, 255, 255))
    img.save(path)

def chip(text, path, x0=1250, x1=1590, y0=10, h=50):
    """상태바 빈 자리(헤드업 오른쪽 ~ 상태 아이콘 왼쪽)에 들어가는 장면 라벨. 길면 글자를 줄인다."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img); size = 26
    while size > 18 and d.textlength(text, font=font(size)) + 36 > x1 - x0: size -= 1
    f = font(size); tw = d.textlength(text, font=f); w = tw + 36
    d.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=h / 2, fill=(0, 224, 116, 235))
    d.text((x0 + 18, y0 + (h - size) / 2 - 3), text, font=f, fill=(10, 20, 14, 255))
    img.save(path)

def card(spec, path, big):
    lines = spec.split("|"); img = Image.new("RGB", (W, H), BG); d = ImageDraw.Draw(img)
    if big:   # 제목 카드: 큰 제목 + 한 줄 + 작은 글
        f1, f2, f3 = font(104), font(44), font(28); y = H / 2 - 150
        d.ellipse([W / 2 - 300 - 26, y + 42, W / 2 - 300 + 10, y + 78], fill=(0, 224, 116))
        d.text((W / 2 - 260, y), lines[0], font=f1, fill=(255, 255, 255)); y += 150
        if len(lines) > 1: d.text((W / 2 - 260, y), lines[1], font=f2, fill=(230, 235, 240)); y += 90
        if len(lines) > 2: d.text((W / 2 - 260, y), lines[2], font=f3, fill=(150, 162, 175))
    else:     # 마무리 카드: 큰 문장 + 세 줄
        f1, f2 = font(60), font(36); y = H / 2 - 190
        d.text((160, y), lines[0], font=f1, fill=(255, 255, 255)); y += 130
        for l in lines[1:]:
            d.ellipse([160, y + 14, 176, y + 30], fill=(0, 224, 116)); d.text((200, y), l, font=f2, fill=(210, 218, 226)); y += 70
    img.save(path)

def situation(title, desc, path, x0=20, x1=620, y0=112):
    """상황 설명 카드: 화면 왼쪽 1/3(차량 클러스터 — 기어 P·문 그림은 위·아래로 남김) 위쪽에 제목 + 1~3줄 설명.
    영상만 보고도 지금 무슨 상황인지 알 수 있게 (대표 피드백 11/13)."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    ft, fd = font(30), font(24); pad = 22; maxw = x1 - x0 - 2 * pad
    lines = []
    for para in desc.split("|"):
        cur = ""
        for w in para.split(" "):
            trial = (cur + " " + w).strip()
            if d.textlength(trial, font=fd) <= maxw: cur = trial
            else: lines.append(cur); cur = w
        if cur: lines.append(cur)
    h = pad + 40 + (10 + 34 * len(lines) if lines else 0) + pad - 6
    d.rounded_rectangle([x0, y0, x1, y0 + h], radius=20, fill=BG + (228,))
    d.ellipse([x0 + pad, y0 + pad + 9, x0 + pad + 18, y0 + pad + 27], fill=(0, 224, 116, 255))
    d.text((x0 + pad + 30, y0 + pad), title, font=ft, fill=(255, 255, 255, 255))
    y = y0 + pad + 50
    for l in lines: d.text((x0 + pad, y), l, font=fd, fill=(200, 210, 220, 255)); y += 34
    img.save(path)

def read_scenes(path, cues, shots, rec_start):
    """장면 파일 → [(원본 시각, 라벨, 상황 설명)] (시각순). 줄 형식: 기준[±초]<탭>라벨[<탭>상황 설명(| 로 줄바꿈)]"""
    out = []
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"): continue
        cols = line.split("\t"); ref, label = cols[0], cols[1]; desc = cols[2].strip() if len(cols) > 2 else ""; off = 0.0
        for sgn in "+-":
            if sgn in ref[1:]:
                i = ref.rindex(sgn)
                if ref[i + 1:].replace(".", "").isdigit(): off = float(ref[i:]); ref = ref[:i]
        if ref == "start": t = 0.0
        elif ref.startswith("cue:"): t = cues[int(ref[4:])][0]
        elif ref.startswith("cueend:"): t = cues[int(ref[7:])][1]
        elif ref.startswith("shot:"): t = os.path.getmtime(os.path.join(shots, ref[5:] + ".png")) - rec_start
        else: raise SystemExit(f"장면 기준을 모른다: {ref}")
        out.append((t + off, label.strip(), desc))
    return sorted(out)

def main():
    src, logcat, rec_start, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
    shots = arg("--shots", None, str); speed = arg("--speed", 8.0); start = arg("--start", 0.0)
    before = arg("--before", 3.0); after = arg("--after", 2.0); mingap = arg("--mingap", 10.0)
    scenes = arg("--scenes", None, str); title = arg("--title", TITLE_DEFAULT if "--title" in sys.argv else None, str)
    closing = arg("--closing", CLOSING_DEFAULT if "--closing" in sys.argv else None, str)
    if title == "": title = TITLE_DEFAULT
    if closing == "": closing = CLOSING_DEFAULT
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src]).decode().strip())
    has_audio = "audio" in subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", src]).decode()
    cues = load_cues(src, logcat, rec_start)
    keep = [(a - before, b + after) for a, b, _ in cues]
    if shots:
        for f in sorted(os.listdir(shots)):
            if f.endswith(".png"):
                t = os.path.getmtime(os.path.join(shots, f)) - rec_start; keep.append((t - 4.0, t + 1.0))
    keep = sorted((max(start, a), min(dur, b)) for a, b in keep if b > start)
    merged = []
    for a, b in keep:
        if merged and a <= merged[-1][1] + 1.0: merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else: merged.append((a, b))
    segs = []; t = start
    for a, b in merged:
        if a - t > mingap: segs.append((t, a, True)); segs.append((a, b, False))
        else: segs.append((t, b, False))          # 짧은 틈은 1배속으로 잇는다
        t = b
    if dur - t > 0.5: segs.append((t, dur, False))
    total = sum((b - a) / (speed if fast else 1) for a, b, fast in segs)
    for a, b, fast in segs: print(f"  {a:6.1f}-{b:6.1f}  {'▶▶ %gx' % speed if fast else '1x'}")
    print(f"{dur:.0f}s → {total:.0f}s ({len(segs)} segments)" + (f" + 카드 {TITLE_SEC + CLOSING_SEC:g}s" if title or closing else ""))

    def out_t(ts):   # 원본 시각 → 본편(카드 제외) 시각
        o = 0.0
        for a, b, fast in segs:
            if ts <= b: return o + (max(ts, a) - a) / (speed if fast else 1)
            o += (b - a) / (speed if fast else 1)
        return o

    tmp = tempfile.mkdtemp(); bp = os.path.join(tmp, "badge.png"); badge(bp, speed)
    inputs = ["-i", src, "-i", bp]; n_in = 2; fc = []; labels = []
    def add_input(path, loop_sec=None):
        nonlocal n_in
        inputs.extend((["-loop", "1", "-t", f"{loop_sec}"] if loop_sec else []) + ["-i", path]); n_in += 1; return n_in - 1
    for i, (a, b, fast) in enumerate(segs):
        chain = f"[0:v]trim=start={a:.3f}:end={b:.3f},setpts=PTS-STARTPTS"
        if fast: chain += f",setpts=PTS/{speed},fps=30[s{i}];[s{i}][1:v]overlay=0:0[v{i}]"
        else: chain += f",fps=30[v{i}]"
        fc.append(chain); labels.append(f"[v{i}]")
        if has_audio:   # 배속 구간엔 말이 없으므로(자막 앞뒤는 1배속) 그 길이만큼 무음
            if fast: fc.append(f"aevalsrc=0:d={(b-a)/speed:.3f}:s=48000:c=stereo[a{i}]")
            else: fc.append(f"[0:a]atrim=start={a:.3f}:end={b:.3f},asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo[a{i}]")
            labels.append(f"[a{i}]")
    A = 1 if has_audio else 0
    fc.append("".join(labels) + f"concat=n={len(segs)}:v=1:a={A}[body]" + ("[abody]" if has_audio else ""))

    # 장면 라벨: 본편 위에 enable=between 으로
    if scenes:
        sc = read_scenes(scenes, cues, shots, rec_start); prev = "[body]"
        for i, (ts, label, desc) in enumerate(sc):
            t0 = out_t(max(ts, start)); t1 = out_t(sc[i + 1][0]) if i + 1 < len(sc) else total
            if t1 - t0 < 0.3: continue
            p = os.path.join(tmp, f"chip{i}.png")
            if desc: situation(label, desc, p)          # 상황 설명이 있으면 왼쪽 카드(상태바 칩 대신)
            else: chip(label, p)
            k = add_input(p)
            fc.append(f"{prev}[{k}:v]overlay=0:0:enable='between(t,{t0:.2f},{t1:.2f})'[l{i}]"); prev = f"[l{i}]"
            print(f"  라벨 {t0:6.1f}-{t1:6.1f}  {label}")
        fc.append(f"{prev}null[body2]")
    else: fc.append("[body]null[body2]")

    # 제목·마무리 카드
    parts_v, parts_a = [], []
    if title:
        p = os.path.join(tmp, "title.png"); card(title, p, True); k = add_input(p, TITLE_SEC)
        fc.append(f"[{k}:v]fps=30,format=yuv420p,fade=t=in:st=0:d={FADE},fade=t=out:st={TITLE_SEC - FADE}:d={FADE}[tv]"); parts_v.append("[tv]")
        if has_audio: fc.append(f"aevalsrc=0:d={TITLE_SEC}:s=48000:c=stereo[ta]"); parts_a.append("[ta]")
    parts_v.append("[body2]"); parts_a.append("[abody]")
    if closing:
        p = os.path.join(tmp, "closing.png"); card(closing, p, False); k = add_input(p, CLOSING_SEC)
        fc.append(f"[{k}:v]fps=30,format=yuv420p,fade=t=in:st=0:d={FADE},fade=t=out:st={CLOSING_SEC - FADE}:d={FADE}[cv]"); parts_v.append("[cv]")
        if has_audio: fc.append(f"aevalsrc=0:d={CLOSING_SEC}:s=48000:c=stereo[ca]"); parts_a.append("[ca]")
    if len(parts_v) > 1:
        inter = "".join(v + (a if has_audio else "") for v, a in zip(parts_v, parts_a if has_audio else [""] * len(parts_v)))
        fc.append(f"{inter}concat=n={len(parts_v)}:v=1:a={A}[out]" + ("[aout]" if has_audio else ""))
    else:
        fc.append("[body2]null[out]"); has_audio and fc.append("[abody]anull[aout]")

    cmd = ["ffmpeg", "-v", "error", "-y"] + inputs + ["-filter_complex", ";".join(fc), "-map", "[out]"] \
        + (["-map", "[aout]", "-c:a", "aac", "-b:a", "160k"] if has_audio else []) \
        + ["-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out]
    subprocess.run(cmd, check=True); print("saved", out, os.path.getsize(out) // 1024, "KB")

if __name__ == "__main__": main()
