# -*- coding: utf-8 -*-
"""make_audio.py — 30s ambient pad for the promo video, synthesized locally
(4 soft chords, sine stack + slow shimmer, low volume, fade in/out).
Output: play/promo/pad-30s.wav. No external assets, no network."""
import wave, math, os, struct

SR = 44100
DUR = 30.5
# Dm — Bb — F — C (one chord per ~7.6s), warm and neutral
CHORDS = [
    [146.83, 220.00, 293.66, 349.23],   # D3 F3 D4 F4
    [116.54, 174.61, 233.08, 349.23],   # Bb2 F3 Bb3 F4
    [130.81, 196.00, 261.63, 392.00],   # C3 G3 C4 G4
    [174.61, 261.63, 349.23, 523.25],   # F3 C4 F4 C5
]
CHORD_LEN = DUR / len(CHORDS)
SHIMMER = 1174.66  # D6 sparkle, very quiet, tremolo

def env(t, dur):
    a = min(1.0, t / 1.8)              # slow attack
    r = min(1.0, (dur - t) / 2.2)      # release into next chord
    return a * r

frames = []
total = int(SR * DUR)
for i in range(total):
    t = i / SR
    seg = min(int(t / CHORD_LEN), len(CHORDS) - 1)
    ct = t - seg * CHORD_LEN
    freqs = CHORDS[seg]
    # cross-blend with next chord in the last 1.2s
    s = 0.0
    w = env(ct, CHORD_LEN)
    for k, f in enumerate(freqs):
        s += math.sin(2 * math.pi * f * ct) * (0.9 if k == 0 else 0.55 / (k + 1))
        s += math.sin(2 * math.pi * (f * 1.003) * ct) * 0.18   # detune warmth
    s *= 0.16 * w
    sh = math.sin(2 * math.pi * SHIMMER * t) * 0.012 * \
         (0.5 + 0.5 * math.sin(2 * math.pi * 0.23 * t)) * w
    master = 1.0
    if t < 2.0: master = t / 2.0
    if t > DUR - 2.5: master = max(0.0, (DUR - t) / 2.5)
    v = (s + sh) * master
    v = max(-1.0, min(1.0, v))
    frames.append(struct.pack("<hh", int(v * 32767), int(v * 32767)))

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(os.path.dirname(here), "promo", "pad-30s.wav")
os.makedirs(os.path.dirname(out), exist_ok=True)
with wave.open(out, "w") as f:
    f.setnchannels(2); f.setsampwidth(2); f.setframerate(SR)
    f.writeframes(b"".join(frames))
print("wrote", out, os.path.getsize(out), "bytes")
