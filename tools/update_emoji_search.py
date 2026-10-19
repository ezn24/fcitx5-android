#!/usr/bin/env python3
"""Build offline CLDR search data and conservative Pinyin dictionary additions."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/.emoji-deps"))
from pypinyin import lazy_pinyin

CLDR_REVISION = "acd6d88ae493633240e19a87a721076a8a75c310"  # CLDR 48
ASSETS = ROOT / "app/src/main/assets"
DICTIONARY = ASSETS / "usr/share/fcitx5/pinyin/dictionaries/fcitx5-android-emoji.txt"
SEARCH = ASSETS / "emoji/search.tsv"
MARKER = "# CLDR 48 generated names (tools/update_emoji_search.py)"


def key(emoji):
    return "".join(c for c in emoji if c != "\ufe0f" and not 0x1F3FB <= ord(c) <= 0x1F3FF)


def download_annotations(directory, locale):
    cache = ROOT / f"tools/.emoji-cache/{CLDR_REVISION}/{directory}/{locale}.xml"
    if not cache.exists():
        url = f"https://raw.githubusercontent.com/unicode-org/cldr/{CLDR_REVISION}/common/{directory}/{locale}.xml"
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read()
        ET.fromstring(data)
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
    return ET.fromstring(cache.read_bytes())


def build():
    picker = (ROOT / "app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerData.kt").read_text(encoding="utf-8")
    block = picker.split("    val Emoji:", 1)[1].split("    val Emoticon:", 1)[0]
    items = []
    for match in re.finditer(r'Category\([^\n]+\) to arrayOf\((.*?)\n        \)', block, re.S):
        items.extend(json.loads(literal) for literal in re.findall(r'"(?:[^"\\]|\\.)*"', match[1]))
    emojis = dict((key(emoji), emoji) for emoji in items)
    if len(emojis) < 1800:
        raise ValueError("Incomplete picker data")
    names, keywords, codes, legacy_codes = (defaultdict(set) for _ in range(4))
    chinese_names = defaultdict(set)
    for locale in ("zh_Hant", "zh", "en"):
        for directory in ("annotations", "annotationsDerived"):
            for annotation in download_annotations(directory, locale).iter("annotation"):
                emoji = annotation.attrib.get("cp", "").replace("\ufe0f", "")
                if emoji not in emojis:
                    continue
                text = annotation.text or ""
                if text in ("", "↑↑↑", "∅∅∅"):
                    continue
                target = names if annotation.attrib.get("type") == "tts" else keywords
                target[emoji].update(word.strip() for word in text.split("|") if word.strip())
                if target is names and locale != "en" and re.fullmatch(r"[\u3400-\u9fff]{2,12}", text):
                    chinese_names[emoji].add(text)

    original, _, generated = DICTIONARY.read_text(encoding="utf-8").partition(MARKER)
    original = original.rstrip() + "\n"
    existing = set()
    for line in original.splitlines():
        if line.startswith("#"):
            continue
        fields = line.split(maxsplit=2)
        if len(fields) < 2:
            continue
        emoji, code = key(fields[0]), fields[1]
        existing.add((emoji, code))
        if emoji in emojis:
            legacy_codes[emoji].add(code)

    # Retain previously shipped rows and weights, including older tone variants.
    supplemental = [line for line in generated.splitlines() if line.strip() and not line.startswith("#")]
    for line in supplemental:
        fields = line.split()
        if len(fields) >= 2:
            existing.add((key(fields[0]), fields[1]))
    for emoji, labels in chinese_names.items():
        for label in sorted(labels):
            syllables = lazy_pinyin(label, errors=lambda text: list(text))
            if not all(re.fullmatch("[a-zv]+", syllable) for syllable in syllables):
                continue
            code = "'".join(syllables)
            codes[emoji].add(code)
            if (emoji, code) not in existing:
                supplemental.append(f"{emojis[emoji]}\t{code}\t0.1")
                existing.add((emoji, code))

    rows = []
    for emoji in emojis:
        columns = [emojis[emoji]] + ["|".join(sorted(table[emoji])) for table in (names, keywords, codes, legacy_codes)]
        if any("\t" in column or "\n" in column for column in columns):
            raise ValueError("Invalid TSV field")
        rows.append("\t".join(columns))
    search = "# CLDR 48; emoji\tnames\tkeywords\tcldr_pinyin\tlegacy_pinyin\n" + "\n".join(rows) + "\n"
    dictionary = original + "\n" + MARKER + "\n" + "\n".join(sorted(supplemental)) + "\n"
    return search, dictionary, len(emojis), len(supplemental)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    search, dictionary, count, added = build()
    for path, content in ((SEARCH, search), (DICTIONARY, dictionary)):
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                raise SystemExit(f"Out of date: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
    print(f"{count} searchable emoji; {added} supplemental Pinyin pairs; {'checked' if args.check else 'updated'}")


if __name__ == "__main__":
    main()
