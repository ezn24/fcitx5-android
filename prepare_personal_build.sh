#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

# Keep native sources and binary dependencies paired with the checked-in revisions.
# Mixing FX Rime sources with official librime requires unavailable extension APIs.
for path in \
    lib/fcitx5/src/main/cpp/fcitx5 \
    lib/fcitx5/src/main/cpp/prebuilt \
    plugin/rime/src/main/cpp/fcitx5-rime; do
    expected=$(git rev-parse "HEAD:$path")
    actual=$(git -C "$path" rev-parse HEAD)
    if [[ "$actual" != "$expected" ]]; then
        echo "Unexpected submodule revision: $path (expected $expected, got $actual)" >&2
        exit 1
    fi
    echo "Using pinned revision $actual: $path"
done
