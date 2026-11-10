#!/usr/bin/env python3
"""시연 영상에 앱 음성 자막을 입힌다 (주행 중엔 내비가 앞에 있어 앱 음성이 화면에 안 보이므로).

사용: python scripts/captions.py <raw.mp4> <logcat_epoch.txt> <rec_start_epoch> <out.mp4> [--trim 초] [--note "자막 한 줄"] [--voice Yuna | --voice edge:ko-KR-SunHiNeural [--rate +10%]]
  logcat_epoch.txt: `adb logcat -v epoch -s LastMile:D` 출력 (디버그 빌드, "🔊 문장" 줄의 epoch 초를 쓴다)
  rec_start_epoch : screenrecord 를 띄운 순간의 epoch 초 (date +%s.%N)
  --voice NAME    : adb screenrecord 는 화면만 담고 에뮬레이터 소리는 녹음하지 않는다. 같은 문장을 macOS `say -v NAME`(한국어 Yuna)로
                    합성해 자막 시각에 얹고, 자막은 음성 길이만큼 보여 준다. 큐 목록은 <out>.cues.json 으로 남겨 condense.py 가 같은 구간을 쓴다.
자막 PNG 는 Pillow 로 그리고 ffmpeg overlay(enable=between) 로 얹는다 (ffmpeg 에 libass/freetype 이 없어도 됨).
"""
import json, os, re, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, H = 1920, 1080

def raw_cues(logcat, rec_start):
    cues = []
    for line in open(logcat, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*(\d+\.\d+)\s.*LastMile: 🔊 (.+)$", line)
        if not m: continue
        t = float(m.group(1)) - rec_start
        if t < 0: continue
        cues.append([t, m.group(2).strip()])
    return cues

def parse(logcat, rec_start, durs=None):
    """(시작, 끝, 문장) 목록. durs 가 있으면(음성 합성 길이) 그만큼, 없으면 글자 수 기준(0.28초/자, 최소 4초 최대 9초). 다음 큐 전까지로 자른다."""
    cues = raw_cues(logcat, rec_start); out = []
    for i, (t, text) in enumerate(cues):
        dur = durs[i] + 0.4 if durs else min(9.0, max(4.0, 0.28 * len(text)))
        if i + 1 < len(cues): dur = min(dur, cues[i + 1][0] - t - 0.2)
        out.append((t, t + max(dur, 1.5), text))
    return out

def synth(texts, voice, tmp, rate=None):
    """문장마다 음성 파일을 만들고 (경로, 길이초) 목록을 돌려준다.
    voice = macOS `say` 음성 이름(Yuna …) 또는 `edge:ko-KR-SunHiNeural` 처럼 Microsoft Edge 신경망 음성(pip edge-tts, 키 불필요;
    실행 파일은 EDGE_TTS 환경변수 또는 PATH 의 edge-tts). rate 는 edge 전용 말속도("+10%")."""
    out = []
    for i, text in enumerate(texts):
        if voice.startswith("edge:"):
            p = os.path.join(tmp, f"v{i}.mp3")
            cmd = [os.environ.get("EDGE_TTS", "edge-tts"), "-v", voice[5:], "-t", text, "--write-media", p]
            if rate: cmd += [f"--rate={rate}"]
            subprocess.run(cmd, check=True)
        else:
            p = os.path.join(tmp, f"v{i}.aiff")
            subprocess.run(["say", "-v", voice, "-o", p, text], check=True)
        d = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]).decode().strip())
        out.append((p, d))
    return out

def load_cues(video, logcat, rec_start):
    """condense.py 용: captions.py --voice 가 남긴 <video>.cues.json 이 있으면 그 구간을, 없으면 글자 수 기준 구간을 쓴다."""
    side = video + ".cues.json"
    if os.path.exists(side): return [tuple(c) for c in json.load(open(side, encoding="utf-8"))]
    return parse(logcat, rec_start)

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
    trim = None; note = "라스트마일 음성 안내 (내비 화면 위에서 재생)"; voice = None
    if "--trim" in sys.argv: trim = float(sys.argv[sys.argv.index("--trim") + 1])
    if "--note" in sys.argv: note = sys.argv[sys.argv.index("--note") + 1]
    if "--voice" in sys.argv: voice = sys.argv[sys.argv.index("--voice") + 1]
    rate = sys.argv[sys.argv.index("--rate") + 1] if "--rate" in sys.argv else None
    tmp = tempfile.mkdtemp(); clips = None
    if voice:
        clips = synth([t for _, t in raw_cues(logcat, rec_start)], voice, tmp, rate)
        cues = parse(logcat, rec_start, [d for _, d in clips])
        json.dump(cues, open(out + ".cues.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    else:
        cues = parse(logcat, rec_start)
    print(f"{len(cues)} cues" + (f" (음성 {voice})" if voice else "")); [print(f"  {a:6.1f}-{b:6.1f}  {t[:60]}") for a, b, t in cues]
    inputs = ["-i", raw]; chain = []; prev = "[0:v]"
    for i, (a, b, t) in enumerate(cues):
        p = os.path.join(tmp, f"c{i}.png"); card(t, p, note); inputs += ["-i", p]
        chain.append(f"{prev}[{i+1}:v]overlay=0:0:enable='between(t,{a:.2f},{b:.2f})'[v{i}]"); prev = f"[v{i}]"
    # scale 을 먼저 적용하기 위해 체인 머리를 바꾼다
    fc = f"[0:v]scale={W}:{H}[base];" + ";".join(chain).replace("[0:v]", "[base]", 1)
    maps = ["-map", prev]; acodec = ["-an"]
    if clips:
        # 음성 클립을 자막 시각(ms)만큼 늦춰 섞고, 영상 길이까지 무음으로 채운다
        dur = float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", raw]).decode().strip())
        base = len(inputs) // 2; alabels = []
        for i, ((p, _), (a, _, _)) in enumerate(zip(clips, cues)):
            inputs += ["-i", p]
            fc += f";[{base+i}:a]aformat=sample_rates=48000:channel_layouts=stereo,adelay={int(a*1000)}:all=1[a{i}]"; alabels.append(f"[a{i}]")
        fc += f";{''.join(alabels)}amix=inputs={len(alabels)}:normalize=0,apad=whole_dur={dur:.3f}[aout]"
        maps += ["-map", "[aout]"]; acodec = ["-c:a", "aac", "-b:a", "160k"]
    cmd = ["ffmpeg", "-v", "error", "-y"] + inputs + (["-t", str(trim)] if trim else []) + ["-filter_complex", fc] + maps + ["-c:v", "libx264", "-crf", "20", "-pix_fmt", "yuv420p"] + acodec + ["-movflags", "+faststart", out]
    subprocess.run(cmd, check=True); print("saved", out, os.path.getsize(out) // 1024, "KB")

if __name__ == "__main__": main()
