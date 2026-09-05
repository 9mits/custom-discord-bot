#!/usr/bin/env python3
"""Build the four-channel 100 ms music envelope consumed by cosmetic reveals."""

from __future__ import annotations

import argparse
import wave
from pathlib import Path

import numpy as np


def scale(values: np.ndarray) -> np.ndarray:
    ceiling = max(float(np.percentile(values, 98)), 1e-9)
    return np.clip(np.sqrt(values / ceiling), 0.0, 1.0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="16-bit PCM WAV input")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    with wave.open(str(args.source), "rb") as stream:
        rate = stream.getframerate()
        channels = stream.getnchannels()
        samples = np.frombuffer(stream.readframes(stream.getnframes()), dtype="<i2")
    samples = samples.reshape(-1, channels).mean(axis=1).astype(np.float64) / 32768.0
    frame_size = max(1, rate // 10)
    count = (len(samples) + frame_size - 1) // frame_size
    padded = np.pad(samples, (0, count * frame_size - len(samples))).reshape(count, frame_size)
    windowed = padded * np.hanning(frame_size)
    spectrum = np.abs(np.fft.rfft(windowed, axis=1)) ** 2
    frequencies = np.fft.rfftfreq(frame_size, 1.0 / rate)
    bands = []
    for low, high in ((20, 180), (180, 2000), (2000, 16000)):
        selected = spectrum[:, (frequencies >= low) & (frequencies < high)].mean(axis=1)
        bands.append(scale(selected))
    energy = np.maximum.reduce(bands)
    onset = scale(np.maximum(0.0, np.diff(energy, prepend=energy[0])))
    output = np.stack((*bands, onset), axis=1)
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    args.destination.write_bytes(np.rint(output * 255).astype(np.uint8).tobytes())
    print(f"{count} frames, {count * 100} ms, {args.destination}")


if __name__ == "__main__":
    main()
