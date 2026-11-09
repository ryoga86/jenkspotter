#!/usr/bin/env python3
"""Synthesises the JenkSpotter notification sounds (no third-party samples, no licence questions).

A desk bell is approximated by a few inharmonic partials with exponential decay.
Run from the repository root: python3 tools/generate_sounds.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "sounds"

# Partial ratios and relative amplitudes of a small struck bell.
BELL_PARTIALS = [(1.0, 1.0), (2.76, 0.45), (5.40, 0.22), (8.93, 0.08)]


def bell(freq: float, length: float, decay: float, amp: float = 1.0) -> list[float]:
    samples = []
    for i in range(int(length * RATE)):
        t = i / RATE
        attack = min(1.0, t / 0.004)
        value = 0.0
        for ratio, weight in BELL_PARTIALS:
            # Higher partials die faster, which gives the bright "ding" and the soft tail.
            value += weight * math.exp(-t * decay * ratio ** 0.7) * math.sin(2 * math.pi * freq * ratio * t)
        samples.append(amp * attack * value)
    return samples


def mix(*tracks: tuple[float, list[float]]) -> list[float]:
    total = max(int(offset * RATE) + len(data) for offset, data in tracks)
    out = [0.0] * total
    for offset, data in tracks:
        start = int(offset * RATE)
        for i, v in enumerate(data):
            out[start + i] += v
    return out


def howler(length: float = 1.3) -> list[float]:
    """A falling, trembling shriek for failed builds in Wizarding Mode."""
    samples, phase = [], 0.0
    for i in range(int(length * RATE)):
        t = i / RATE
        freq = 900 * math.exp(-1.1 * t) + 180
        phase += 2 * math.pi * freq / RATE
        tremolo = 0.6 + 0.4 * math.sin(2 * math.pi * 11 * t)
        envelope = min(1.0, t / 0.03) * math.exp(-1.6 * t)
        # A few odd harmonics make it sound like a voice rather than a sine.
        tone = math.sin(phase) + 0.4 * math.sin(3 * phase) + 0.2 * math.sin(5 * phase)
        samples.append(envelope * tremolo * tone)
    return samples


def write(name: str, samples: list[float], peak: float = 0.8) -> None:
    fade = int(0.02 * RATE)
    for i in range(min(fade, len(samples))):
        samples[-1 - i] *= i / fade
    scale = peak / max(abs(s) for s in samples)
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(s * scale * 32767)) for s in samples))
    print("wrote", OUT / name)


def main() -> None:
    write("success.wav", mix((0.0, bell(1046.5, 1.2, 5.0)), (0.13, bell(1568.0, 1.4, 4.5))))
    write("failure.wav", mix((0.0, bell(784.0, 1.0, 5.5)), (0.22, bell(523.3, 1.5, 4.0))))
    write("aborted.wav", bell(440.0, 0.7, 9.0), peak=0.55)
    write("howler.wav", howler(), peak=0.7)


if __name__ == "__main__":
    main()
