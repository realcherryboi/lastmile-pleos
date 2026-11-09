#!/usr/bin/env python3
"""자막을 입힌 시연 영상에서 아무 일도 없는 주행 구간을 빠르게 돌린다 (잘라 내지 않고 배속 → 내비 화면이 이어진다).

사용: python scripts/condense.py <captioned.mp4> <logcat_epoch.txt> <rec_start_epoch> <out.mp4>
          [--shots 폴더] [--speed 8] [--start 초] [--before 3] [--after 2] [--mingap 10]
  1배속으로 남기는 구간 = 음성 자막(🔊 줄) 앞 before초 ~ 자막 끝 뒤 after초, 그리고 demo.sh 장면 캡처(--shots 폴더의 PNG mtime) 앞 4초 ~ 뒤 1초.
  그 사이가 --mingap 초보다 길 때만 --speed 배속으로 돌리고 오른쪽 위에 "▶▶ N배속" 배지를 얹는다(짧은 틈은 조작 장면이라 1배속).
  --start 앞은 버린다(초기화 조작).
  입력에 소리가 있으면(captions.py --voice) 1배속 구간은 그대로, 배속 구간은 무음으로 이어 붙인다. 자막 구간은 captions.py 가 남긴 <captioned>.cues.json 을 우선 쓴다.
"""
import os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from captions import load_cues, FONT, W, H
from PIL import Image, ImageDraw, ImageFont

def arg(name, default, cast=float):
    return cast(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default

def badge(path, speed):
    font = ImageFont.truetype(FONT, 30, index=0)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    text = f"▶▶  주행 구간 {speed:g}배속"; tw = d.textlength(text, font=font)
    x1 = W - 60; x0 = x1 - tw - 56; y0 = 40
    d.rounded_rectangle([x0, y0, x1, y0 + 62], radius=18, fill=(18, 22, 28, 200))
    d.text((x0 + 28, y0 + 13), text, font=font, fill=(255, 255, 255, 255))
    img.save(path)

def main():
    src, logcat, rec_start, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4]
    shots = arg("--shots", None, str); speed = arg("--speed", 8.0); start = arg("--start", 0.0)
    before = arg("--before", 3.0); after = arg("--after", 2.0); mingap = arg("--mingap", 10.0)
    dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src]).decode().strip())
    has_audio = "audio" in subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", src]).decode()
    keep = [(a - before, b + after) for a, b, _ in load_cues(src, logcat, rec_start)]
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
    print(f"{dur:.0f}s → {total:.0f}s ({len(segs)} segments)")
    tmp = tempfile.mkdtemp(); bp = os.path.join(tmp, "badge.png"); badge(bp, speed)
    fc = []; labels = []
    for i, (a, b, fast) in enumerate(segs):
        chain = f"[0:v]trim=start={a:.3f}:end={b:.3f},setpts=PTS-STARTPTS"
        if fast: chain += f",setpts=PTS/{speed},fps=30[s{i}];[s{i}][1:v]overlay=0:0[v{i}]"
        else: chain += f",fps=30[v{i}]"
        fc.append(chain); labels.append(f"[v{i}]")
        if has_audio:   # 배속 구간엔 말이 없으므로(자막 앞뒤는 1배속) 그 길이만큼 무음
            if fast: fc.append(f"aevalsrc=0:d={(b-a)/speed:.3f}:s=48000:c=stereo[a{i}]")
            else: fc.append(f"[0:a]atrim=start={a:.3f}:end={b:.3f},asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo[a{i}]")
            labels.append(f"[a{i}]")
    fc.append("".join(labels) + f"concat=n={len(segs)}:v=1:a={1 if has_audio else 0}[out]" + ("[aout]" if has_audio else ""))
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", src, "-i", bp, "-filter_complex", ";".join(fc), "-map", "[out]"] \
        + (["-map", "[aout]", "-c:a", "aac", "-b:a", "160k"] if has_audio else []) \
        + ["-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out]
    subprocess.run(cmd, check=True); print("saved", out, os.path.getsize(out) // 1024, "KB")

if __name__ == "__main__": main()
