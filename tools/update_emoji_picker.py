#!/usr/bin/env python3
"""Regenerate the emoji picker in EmojiLibrary's iOS-oriented order."""

import argparse
import json
from pathlib import Path
import re
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/java/org/fcitx/fcitx5/android/input/picker/PickerData.kt"
REVISION = "024c39fcabaa936804aaaef92e89b28a47def5db"
SOURCE = (
    f"https://raw.githubusercontent.com/PoomSmart/EmojiLibrary/{REVISION}/"
    "PSEmojiUtilities%2BEmoji.m"
)
UNICODE = "https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt"
CATEGORIES = (
    ("PeopleEmoji", "\U0001f642", "ic_baseline_tag_faces_24"),
    ("NatureEmoji", "\U0001f338", "ic_baseline_flower_24"),
    ("FoodAndDrinkEmoji", "\U0001f382", "ic_baseline_cake_24"),
    ("ActivityEmoji", "\u26bd", "ic_baseline_sports_basketball_24"),
    ("TravelAndPlacesEmoji", "\U0001f698", "ic_baseline_directions_car_24"),
    ("ObjectsEmoji", "\U0001f4a1", "ic_baseline_emoji_objects_24"),
    ("SymbolsEmoji", "\U0001f523", "ic_baseline_emoji_symbols_24"),
    ("FlagsEmoji", "\U0001f3c1", "ic_baseline_flag_24"),
)


def download(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode("utf-8-sig")


def canonical_sequences(data):
    # Apple includes redundant presentation selectors in some strings. Use the
    # fully-qualified Unicode form so Android's RGI filter recognizes them.
    result = {}
    for line in data.splitlines():
        fields = line.split("#", 1)[0].split(";")
        if len(fields) != 2 or fields[1].strip() != "fully-qualified":
            continue
        emoji = "".join(chr(int(point, 16)) for point in fields[0].split())
        result[emoji.replace("\ufe0f", "")] = emoji
    if len(result) < 3000:
        raise ValueError("Incomplete Unicode emoji data")
    return result


def generate(source, canonical):
    lines = [
        "    // iOS-oriented categories/order adapted from PoomSmart/EmojiLibrary (MIT).",
        f"    // Source revision: {REVISION}; regenerate with tools/update_emoji_picker.py.",
        "    val Emoji: List<Pair<Category, Array<String>>> = listOf(",
    ]
    total = 0
    for method, label, icon in CATEGORIES:
        match = re.search(
            rf"\+ \(NSArray <NSString \*> \*\){method} \{{.*?"
            r"data = \[self AsFakeSet:@\[(.*?)\]\];",
            source,
            re.DOTALL,
        )
        if not match:
            raise ValueError(f"Missing source category: {method}")
        raw = re.findall(r'@("(?:[^"\\]|\\.)*")', match[1])
        items = []
        for literal in raw:
            emoji = json.loads(literal)
            key = emoji.replace("\ufe0f", "")
            if key not in canonical:
                raise ValueError(f"Non-standard emoji in {method}: {ascii(emoji)}")
            emoji = canonical[key]
            if emoji not in items:
                items.append(emoji)
        if len(items) < 100:
            raise ValueError(f"Incomplete source category: {method}")
        total += len(items)
        lines.append(f'        Category("{label}", R.drawable.{icon}) to arrayOf(')
        for offset in range(0, len(items), 10):
            values = ", ".join(json.dumps(item, ensure_ascii=False) for item in items[offset:offset + 10])
            lines.append(f"            {values},")
        lines.append("        ),")
    lines.append("    )")
    if total < 1800:
        raise ValueError("Incomplete emoji picker data")
    return "\n".join(lines), total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check without writing files")
    args = parser.parse_args()
    block, total = generate(download(SOURCE), canonical_sequences(download(UNICODE)))
    original = TARGET.read_text(encoding="utf-8")
    pattern = (
        r"(?:    // iOS-oriented categories/order[^\n]*\n    // Source revision:[^\n]*\n)?"
        r"    val Emoji: List<Pair<Category, Array<String>>> = listOf\(.*?\n    \)"
    )
    updated, count = re.subn(pattern, lambda _: block, original, flags=re.DOTALL)
    if count != 1:
        raise ValueError("Expected exactly one emoji picker declaration")
    if args.check:
        if updated != original:
            raise SystemExit("Emoji picker is out of date")
    else:
        TARGET.write_text(updated, encoding="utf-8", newline="\n")
    print(f"Emoji picker: {len(CATEGORIES)} categories, {total} entries; {'checked' if args.check else 'updated'}")


if __name__ == "__main__":
    main()
