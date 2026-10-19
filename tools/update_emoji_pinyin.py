#!/usr/bin/env python3
"""Generate a compact, offline Hanzi-to-Pinyin table for the emoji editor."""
import argparse
import io
from pathlib import Path
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/.emoji-deps"))
from pypinyin import lazy_pinyin
from pypinyin.constants import PINYIN_DICT


def build():
    source = (ROOT / "lib/libime/src/main/cpp/libime/src/libime/pinyin/pinyindata.cpp").read_text(encoding="utf-8")
    syllables = sorted(set(re.findall(
        r'\{\s*"([a-z]+)",\s*PinyinInitial::\w+,\s*PinyinFinal::\w+,\s*PinyinFuzzyFlag::None\s*\}', source
    )))
    if len(syllables) < 400:
        raise ValueError("Incomplete LibIME syllable table")
    indices = {value: index for index, value in enumerate(syllables)}
    entries = []
    for point in sorted(PINYIN_DICT):
        reading = lazy_pinyin(chr(point))[0]
        if reading in indices:
            entries.append((point, indices[reading]))
    output = io.BytesIO()
    output.write(b"EPY1")
    output.write(struct.pack(">H", len(syllables)))
    for value in syllables:
        data = value.encode("ascii")
        output.write(struct.pack(">H", len(data)))
        output.write(data)
    output.write(struct.pack(">I", len(entries)))
    for point, index in entries:
        output.write(struct.pack(">IH", point, index))
    return output.getvalue(), len(entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data, count = build()
    path = ROOT / "app/src/main/assets/emoji/hanzi-pinyin.bin"
    if args.check:
        if not path.exists() or path.read_bytes() != data:
            raise SystemExit("Hanzi Pinyin data is out of date")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    print(f"{count} Hanzi readings; {len(data)} bytes; {'checked' if args.check else 'generated'}")


if __name__ == "__main__":
    main()
