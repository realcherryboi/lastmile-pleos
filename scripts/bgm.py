#!/usr/bin/env python3
"""시연 영상 배경음악을 코드로 합성한다 (11/15 대표: "배경음악도 공모전에 어울리는 거 깔면 좋겠어").

외부 음원을 받지 않으니 저작권·라이선스 걱정이 없다. numpy + scipy 만 쓴다.
  python scripts/bgm.py <out.wav> <길이초> [--intro 8] [--outro 6] [--bpm 100]
D장조 I–V–vi–IV(D·A/C#·Bm7·Gmaj7) 진행, 밝고 차분한 테크 발표 톤:
  0 ~ intro      패드만 (오프닝 애니메이션)
  intro ~        패드 + 플럭 아르페지오 + 베이스
  intro+16초 ~   부드러운 킥·하이햇·클랩 추가 (주행 장면부터)
  끝 outro 전 ~  드럼 빠지고 패드·아르페지오만, 마지막 3초 페이드아웃
나레이션 아래로는 영상 합치는 단계(ffmpeg sidechaincompress)에서 자동으로 줄인다.
"""
import sys, wave
import numpy as np
from scipy.signal import fftconvolve, butter, sosfilt

SR = 44100


def arg(name, default):
    return float(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default


def hz(midi): return 440.0 * 2 ** ((midi - 69) / 12)


def env_adsr(n, a, r, sus=1.0):
    e = np.ones(n) * sus
    na, nr = int(a * SR), int(r * SR)
    e[:na] = np.linspace(0, 1, na) ** 2 * sus if na else e[:na]
    if nr: e[-nr:] *= np.linspace(1, 0, nr) ** 1.5
    return e


def pad_voice(f, dur, detune):
    t = np.arange(int(dur * SR)) / SR; s = np.zeros_like(t)
    for k in range(1, 7):                       # 부드러운 톱니(고조파 1/n^1.6)
        for dt in (-detune, detune):
            s += np.sin(2 * np.pi * f * k * (1 + dt) * t + k) / k ** 1.6
    return s


def pluck(f, dur=0.6):
    t = np.arange(int(dur * SR)) / SR
    s = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * 2 * f * t) + 0.12 * np.sin(2 * np.pi * 3 * f * t)
    return s * np.exp(-t / 0.18) * (1 - np.exp(-t / 0.003))


def bass(f, dur):
    t = np.arange(int(dur * SR)) / SR
    s = np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * 2 * f * t)
    return s * np.exp(-t / 0.9) * (1 - np.exp(-t / 0.01))


def kick():
    t = np.arange(int(0.35 * SR)) / SR
    f = 50 + 70 * np.exp(-t / 0.04)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.12)


rng = np.random.default_rng(7)


def noise_hit(dur, decay, lo, hi):
    n = rng.standard_normal(int(dur * SR))
    sos = butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    t = np.arange(len(n)) / SR
    return sosfilt(sos, n) * np.exp(-t / decay)


def add(buf, sig, at, gain=1.0, pan=0.0):
    i = int(at * SR)
    if i >= buf.shape[1]: return
    sig = sig[: buf.shape[1] - i] * gain
    buf[0, i:i + len(sig)] += sig * np.sqrt(0.5 * (1 - pan))
    buf[1, i:i + len(sig)] += sig * np.sqrt(0.5 * (1 + pan))


def main():
    out, total = sys.argv[1], float(sys.argv[2])
    intro, outro, bpm = arg("--intro", 8.0), arg("--outro", 6.0), arg("--bpm", 100.0)
    beat = 60 / bpm; bar = 4 * beat
    # D(add9) · A/C# · Bm7 · Gmaj7 — (베이스 midi, 패드 음들, 아르페지오 음들)
    chords = [(38, [62, 66, 69, 76], [74, 78, 81, 76]),
              (37, [61, 64, 69, 73], [73, 76, 81, 76]),
              (35, [62, 66, 69, 71], [71, 74, 78, 74]),
              (31, [62, 66, 67, 71], [71, 74, 79, 74])]
    n = int((total + 3) * SR)
    pad = np.zeros((2, n)); arp = np.zeros((2, n)); low = np.zeros((2, n)); drum = np.zeros((2, n))
    drums_on, drums_off = intro + 4 * bar, total - outro
    k = 0; t0 = 0.0
    while t0 < total:
        b, pv, av = chords[k % 4]
        for j, m in enumerate(pv):              # 패드: 마디마다 겹쳐 이어지게(앞뒤 0.8초 겹침)
            v = pad_voice(hz(m), bar + 0.8, 0.0025 + 0.001 * j) * env_adsr(int((bar + 0.8) * SR), 0.7, 0.9)
            add(pad, v, max(0, t0 - 0.4), 0.05, pan=(-0.5, 0.5, -0.2, 0.2)[j])
        if t0 >= intro - 0.01:
            add(low, bass(hz(b), beat * 2), t0, 0.32); add(low, bass(hz(b), beat * 2), t0 + 2 * beat, 0.22)
            for s in range(8):                  # 8분음표 아르페지오, 셋째 박마다 살짝 세게
                m = av[s % 4] + (12 if s in (3, 7) else 0)
                add(arp, pluck(hz(m)), t0 + s * beat / 2, (0.11 if s % 2 == 0 else 0.07), pan=(-0.35 if s % 2 else 0.35))
        if drums_on <= t0 < drums_off:
            for s in range(4):
                if s in (0, 2): add(drum, kick(), t0 + s * beat, 0.55)
                if s in (1, 3): add(drum, noise_hit(0.25, 0.06, 900, 5000), t0 + s * beat, 0.10)
            for s in range(8):
                add(drum, noise_hit(0.08, 0.02, 6000, 15000), t0 + s * beat / 2, 0.05 if s % 2 else 0.025, pan=0.3)
        t0 += bar; k += 1
    # 리버브: 2초 꼬리의 감쇠 잡음 임펄스 응답(좌우 다르게)
    ir_t = np.arange(int(2.2 * SR)) / SR
    wet = np.zeros_like(pad)
    bus = pad + arp
    for c in range(2):
        ir = rng.standard_normal(len(ir_t)) * np.exp(-ir_t / 0.55); ir /= np.sqrt((ir ** 2).sum())
        wet[c] = fftconvolve(bus[c], ir)[:n]
    # 킥이 칠 때 패드가 살짝 눌리는 펌핑(사이드체인 느낌)
    pump = np.ones(n)
    tb = drums_on
    while tb < drums_off:
        i = int(tb * SR); m = int(beat * SR)
        pump[i:i + m] = np.minimum(pump[i:i + m], 1 - 0.25 * np.exp(-np.arange(min(m, n - i)) / SR / 0.15))
        tb += beat
    mix = (pad + 0.8 * arp) * pump + 0.35 * wet + low + drum
    hp = butter(2, 35, btype="high", fs=SR, output="sos"); mix = sosfilt(hp, mix, axis=1)
    t = np.arange(n) / SR
    fade = np.clip(t / 2.0, 0, 1) * np.clip((total - t) / 3.0, 0, 1)
    mix = mix * fade
    mix = mix[:, : int(total * SR)]
    mix = np.tanh(mix / np.abs(mix).max() * 1.2) * 0.85         # 살짝 눌러 붙이고 정규화
    pcm = (mix.T * 32767).astype(np.int16)
    with wave.open(out, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    print("saved", out, f"{total:.1f}s")


if __name__ == "__main__":
    main()
