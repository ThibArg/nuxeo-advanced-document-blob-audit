#!/usr/bin/env bash
# Writes the two S16 files over maxBlobSize (10 MB), which are kept out of git:
#   S16-too-large/too-large-00.txt and S16-too-large/too-large-01.txt (11 MB each).
#
#   bash test-campaign/_generator/make-large-files.sh [--force]
#
# A file already there with the size MANIFEST.tsv gives is kept, unless --force. Each file is written
# to a temporary name, checked against the SHA-256 of MANIFEST.tsv, then renamed: a failed or
# interrupted run never leaves a partial file behind. The agent helpers call this script on first use.
#
# Same bytes as s16() in gen_text.py: change both together (the SHA-256 check catches a drift).
# Needs bash, awk, and shasum or sha256sum.
set -euo pipefail

campaign=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
manifest="$campaign/MANIFEST.tsv"
force=false
[ "${1:-}" = --force ] && force=true

# The file being written, removed if the script stops before renaming it.
tmp=""
trap '[ -z "$tmp" ] || rm -f "$tmp"' EXIT

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    else
        sha256sum "$1" | awk '{ print $1 }'
    fi
}

# manifest_field RELATIVE_PATH COLUMN: 2 = size, 3 = sha256.
manifest_field() {
    awk -F'\t' -v p="$1" -v c="$2" '$1 == p { print $c }' "$manifest"
}

# big_lines CHANGED_INDEX: the records of gen_text.big_lines, line CHANGED_INDEX in ALERT (0: none).
big_lines() {
    awk -v changed="$1" 'BEGIN {
        size = 0
        for (i = 1; size < 11500000; i++) {
            status = (i == changed) ? "ALERT" : "nominal"
            line = sprintf("Record %07d: sensor reading %03d at station %02d, status %s\n", i, i % 997, i % 53, status)
            printf "%s", line
            size += length(line)
        }
    }'
}

# make RELATIVE_PATH CHANGED_INDEX
make() {
    local rel=$1 target="$campaign/$1" size sha actual
    size=$(manifest_field "$rel" 2)
    sha=$(manifest_field "$rel" 3)
    if [ -z "$sha" ]; then
        echo "make-large-files: $rel is not in MANIFEST.tsv" >&2
        return 1
    fi
    if ! $force && [ -f "$target" ] && [ "$(wc -c <"$target" | tr -d ' ')" = "$size" ]; then
        return 0
    fi
    tmp="$target.tmp.$$"
    big_lines "$2" >"$tmp"
    actual=$(sha256_of "$tmp")
    if [ "$actual" != "$sha" ]; then
        echo "make-large-files: $rel has SHA-256 $actual, MANIFEST.tsv expects $sha" >&2
        return 1
    fi
    mv "$tmp" "$target"
    tmp=""
    echo "make-large-files: wrote $rel ($size bytes)" >&2
}

make S16-too-large/too-large-00.txt 0
make S16-too-large/too-large-01.txt 50000
