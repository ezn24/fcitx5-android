#!/usr/bin/env python3
#
# Generate androidkeyboard phrase prediction data from Tatoeba sentences and
# optional Google Books Ngram shards.
#
# Output format:
#   prefix words<TAB>next<TAB>score
#

from __future__ import annotations

import argparse
import bz2
import csv
import gzip
import io
import math
import os
from collections import Counter, defaultdict
from pathlib import Path
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile


TATOEBA_SENTENCES_URL = "https://downloads.tatoeba.org/exports/sentences.tar.bz2"
GOOGLE_2020_BASE = "https://storage.googleapis.com/books/ngrams/books/20200217"
GOOGLE_2020_TOTALS = {
    1: 24,
    2: 589,
    3: 6881,
    4: 6668,
    5: 19423,
}

WORD_RE = re.compile(r"[a-z]+(?:['-][a-z]+)?")
BAD_WORDS = {
    "thy", "thou", "thee", "hath", "unto", "wherefore", "shall", "shalt",
    "art", "dost", "didst", "ye", "nay",
    "tom", "mary", "john", "jack", "mike", "jim", "bob", "alice", "ken",
    "george", "susan", "linda", "nancy", "helen", "jane", "kate", "betty",
    "boston", "tokyo", "paris", "london",
}
CONVERSATIONAL_STARTS = {
    "i", "you", "we", "they", "he", "she", "it", "this", "that", "there",
    "what", "where", "when", "why", "how", "can", "could", "would", "should",
    "do", "does", "did", "are", "is", "was", "were", "will", "please",
    "thanks", "thank", "sorry", "good", "see", "let", "let's", "have",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate English next-word phrase predictions for fcitx5-android."
    )
    parser.add_argument(
        "--tatoeba",
        default=None,
        help="Tatoeba sentences file/archive path or URL. Supports .csv, .tar.bz2, .bz2.",
    )
    parser.add_argument(
        "--download-tatoeba",
        action="store_true",
        help=f"Download the current Tatoeba sentences archive ({TATOEBA_SENTENCES_URL}).",
    )
    parser.add_argument(
        "--extra-phrases",
        action="append",
        default=[],
        help="Plain text phrase file, one sentence per line. Can be repeated.",
    )
    parser.add_argument(
        "--ngram",
        action="append",
        default=[],
        help="Google Books Ngram file path or URL. Supports .gz and .zip. Can be repeated.",
    )
    parser.add_argument(
        "--google-2020-shards",
        default="",
        help=(
            "Download selected Google Books Ngram v3 shards, e.g. "
            "'2:0-30,3:0-80'. Do not request all shards unless you have enough disk/time."
        ),
    )
    parser.add_argument(
        "--google-corpus",
        default="eng",
        help="Google Books Ngram 2020 corpus id, normally 'eng' or 'eng-us'.",
    )
    parser.add_argument(
        "--cache-dir",
        default="build/phrase-prediction-cache",
        help="Download/cache directory.",
    )
    parser.add_argument(
        "--output",
        default="app/src/main/assets/usr/share/fcitx5/androidkeyboard/phrase_predictions.txt",
        help="Output phrase prediction file.",
    )
    parser.add_argument("--max-words", type=int, default=8)
    parser.add_argument("--max-prefix-words", type=int, default=4)
    parser.add_argument("--top-per-prefix", type=int, default=20)
    parser.add_argument("--max-entries", type=int, default=160000)
    parser.add_argument("--min-score", type=int, default=2)
    parser.add_argument("--tatoeba-weight", type=float, default=12.0)
    parser.add_argument("--extra-weight", type=float, default=200.0)
    parser.add_argument("--ngram-weight", type=float, default=8.0)
    return parser.parse_args()


def cache_download(source: str, cache_dir: Path) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    name = source.rstrip("/").rsplit("/", 1)[-1]
    target = cache_dir / name
    if target.exists() and target.stat().st_size > 0:
        return target
    tmp = target.with_suffix(target.suffix + ".part")
    print(f"Downloading {source}", file=sys.stderr)
    with urllib.request.urlopen(source) as response, tmp.open("wb") as out:
        shutil.copyfileobj(response, out)
    tmp.replace(target)
    return target


def resolve_source(source: str, cache_dir: Path) -> Path:
    if source.startswith(("http://", "https://")):
        return cache_download(source, cache_dir)
    return Path(source)


def normalize_words(text: str) -> list[str]:
    text = text.split("#", 1)[0].lower()
    text = text.replace("’", "'").replace("`", "'")
    if any(ch.isdigit() for ch in text):
        return []
    words = WORD_RE.findall(text)
    if any(len(word) > 24 for word in words):
        return []
    if any(word in BAD_WORDS for word in words):
        return []
    return words


def normalize_sentence(text: str, max_words: int) -> list[str]:
    words = normalize_words(text)
    if not 2 <= len(words) <= max_words:
        return []
    if len(words[0]) == 1 and words[0] != "i":
        return []
    return words


def score_sentence(words: list[str], base_weight: float) -> int:
    score = base_weight
    if words[0] in CONVERSATIONAL_STARTS:
        score *= 1.7
    if any(word in {"you", "i", "we", "me", "your", "please", "thanks", "sorry"} for word in words):
        score *= 1.3
    if len(words) <= 5:
        score *= 1.15
    return max(1, int(round(score)))


def add_sentence_edges(
    edge_scores: Counter[tuple[str, str]],
    words: list[str],
    base_weight: float,
    max_prefix_words: int,
) -> None:
    score = score_sentence(words, base_weight)
    for begin in range(0, len(words) - 1):
        max_len = min(max_prefix_words, len(words) - begin - 1)
        for length in range(1, max_len + 1):
            prefix = " ".join(words[begin : begin + length])
            next_word = words[begin + length]
            edge_scores[(prefix, next_word)] += score


def add_prediction_row(edge_scores: Counter[tuple[str, str]], line: str) -> bool:
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 2:
        return False
    prefix_words = normalize_words(parts[0])
    next_words = normalize_words(parts[1])
    if len(prefix_words) > 4 or len(next_words) != 1:
        return False
    if not prefix_words or not next_words:
        return False
    score = 1
    if len(parts) >= 3:
        try:
            score = max(1, int(parts[2]))
        except ValueError:
            pass
    edge_scores[(" ".join(prefix_words), next_words[0])] += score
    return True


def iter_tatoeba_sentences(path: Path) -> iter:
    def rows_from_text(stream: io.TextIOBase):
        reader = csv.reader(stream, delimiter="\t")
        for row in reader:
            if len(row) >= 3 and row[1] == "eng":
                yield row[2]

    suffixes = "".join(path.suffixes)
    if suffixes.endswith(".tar.bz2"):
        with tarfile.open(path, "r:bz2") as archive:
            member = next(
                (m for m in archive.getmembers() if Path(m.name).name.startswith("sentences")),
                None,
            )
            if member is None:
                return
            extracted = archive.extractfile(member)
            if extracted is None:
                return
            with io.TextIOWrapper(extracted, encoding="utf-8", errors="ignore", newline="") as stream:
                yield from rows_from_text(stream)
    elif path.suffix == ".bz2":
        with bz2.open(path, "rt", encoding="utf-8", errors="ignore", newline="") as stream:
            yield from rows_from_text(stream)
    else:
        with path.open("r", encoding="utf-8", errors="ignore", newline="") as stream:
            yield from rows_from_text(stream)


def iter_phrase_file(path: Path) -> iter:
    with path.open("r", encoding="utf-8", errors="ignore") as stream:
        for line in stream:
            yield line.strip()


def iter_ngram_lines(path: Path) -> iter:
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8", errors="ignore") as stream:
            yield from stream
    elif path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                with archive.open(name) as raw:
                    with io.TextIOWrapper(raw, encoding="utf-8", errors="ignore") as stream:
                        yield from stream
    else:
        with path.open("r", encoding="utf-8", errors="ignore") as stream:
            yield from stream


def parse_ngram_line(line: str) -> tuple[list[str], int] | None:
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 2:
        return None
    words = normalize_sentence(parts[0].replace("_", " "), max_words=5)
    if len(words) < 2:
        return None
    count = 0
    if len(parts) >= 4 and parts[1].isdigit():
        try:
            year = int(parts[1])
            if year >= 1990:
                count = int(parts[2])
        except ValueError:
            return None
    else:
        for part in parts[1:]:
            try:
                count += int(part.split(",", 1)[0])
            except ValueError:
                continue
    if count <= 0:
        return None
    return words, count


def add_ngram_scores(
    edge_scores: Counter[tuple[str, str]],
    source: Path,
    weight: float,
    max_prefix_words: int,
) -> int:
    added = 0
    for line in iter_ngram_lines(source):
        parsed = parse_ngram_line(line)
        if parsed is None:
            continue
        words, count = parsed
        prefix_words = words[:-1][-max_prefix_words:]
        next_word = words[-1]
        boost = max(1, int(round(math.log10(count + 1) * weight)))
        edge_scores[(" ".join(prefix_words), next_word)] += boost
        added += 1
    return added


def parse_shards(spec: str) -> list[tuple[int, int]]:
    result: list[tuple[int, int]] = []
    if not spec:
        return result
    for chunk in spec.split(","):
        n_part, range_part = chunk.split(":", 1)
        n = int(n_part)
        if n not in GOOGLE_2020_TOTALS:
            raise ValueError(f"unsupported ngram size: {n}")
        if "-" in range_part:
            start, end = [int(x) for x in range_part.split("-", 1)]
            result.extend((n, i) for i in range(start, end + 1))
        else:
            result.append((n, int(range_part)))
    return result


def google_2020_url(corpus: str, n: int, index: int) -> str:
    total = GOOGLE_2020_TOTALS[n]
    if index < 0 or index >= total:
        raise ValueError(f"shard {n}:{index} out of range 0-{total - 1}")
    return f"{GOOGLE_2020_BASE}/{corpus}/{n}-{index:05d}-of-{total:05d}.gz"


def write_output(
    edge_scores: Counter[tuple[str, str]],
    output: Path,
    top_per_prefix: int,
    min_score: int,
    max_entries: int,
) -> int:
    grouped: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for (prefix, next_word), score in edge_scores.items():
        if score >= min_score:
            grouped[prefix].append((next_word, score))

    rows: list[tuple[str, str, int]] = []
    for prefix, items in grouped.items():
        items.sort(key=lambda item: (-item[1], item[0]))
        for next_word, score in items[:top_per_prefix]:
            rows.append((prefix, next_word, score))
    rows.sort(key=lambda row: (-row[2], row[0], row[1]))
    rows = rows[:max_entries]
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        for prefix, next_word, score in rows:
            stream.write(f"{prefix}\t{next_word}\t{score}\n")
    return len(rows)


def main() -> int:
    args = parse_args()
    cache_dir = Path(args.cache_dir)
    edge_scores: Counter[tuple[str, str]] = Counter()

    tatoeba_source = args.tatoeba
    if args.download_tatoeba and not tatoeba_source:
        tatoeba_source = TATOEBA_SENTENCES_URL
    if tatoeba_source:
        path = resolve_source(tatoeba_source, cache_dir)
        count = 0
        seen_sentences: set[tuple[str, ...]] = set()
        for text in iter_tatoeba_sentences(path):
            words = normalize_sentence(text, args.max_words)
            key = tuple(words)
            if words and key not in seen_sentences:
                seen_sentences.add(key)
                add_sentence_edges(edge_scores, words, args.tatoeba_weight, args.max_prefix_words)
                count += 1
        print(f"Tatoeba sentences used: {count}", file=sys.stderr)

    for source in args.extra_phrases:
        path = Path(source)
        count = 0
        seen_sentences: set[tuple[str, ...]] = set()
        for text in iter_phrase_file(path):
            if "\t" in text and add_prediction_row(edge_scores, text):
                count += 1
                continue
            words = normalize_sentence(text, args.max_words)
            key = tuple(words)
            if words and key not in seen_sentences:
                seen_sentences.add(key)
                add_sentence_edges(edge_scores, words, args.extra_weight, args.max_prefix_words)
                count += 1
        print(f"Extra phrases used from {path}: {count}", file=sys.stderr)

    ngram_sources = list(args.ngram)
    for n, index in parse_shards(args.google_2020_shards):
        ngram_sources.append(google_2020_url(args.google_corpus, n, index))
    for source in ngram_sources:
        path = resolve_source(source, cache_dir)
        added = add_ngram_scores(edge_scores, path, args.ngram_weight, args.max_prefix_words)
        print(f"Ngram rows used from {path}: {added}", file=sys.stderr)

    if not edge_scores:
        print("No usable data was loaded.", file=sys.stderr)
        return 2
    rows = write_output(
        edge_scores,
        Path(args.output),
        args.top_per_prefix,
        args.min_score,
        args.max_entries,
    )
    print(f"Wrote {rows} prediction rows to {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
