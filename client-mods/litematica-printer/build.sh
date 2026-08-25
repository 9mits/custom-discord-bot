#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
source_dir="${1:-$repo_root/runtime/litematica-printer-build}"
upstream_commit="94f25eb61037525e9372b46b37c1bf071c62832d"
patch_file="$repo_root/client-mods/litematica-printer/vanilla-slot-sync.patch"

if [[ ! -d "$source_dir/.git" ]]; then
    if [[ -e "$source_dir" ]]; then
        echo "Refusing to replace non-git path: $source_dir" >&2
        exit 1
    fi
    git clone https://github.com/Yur1Ca/litematica-printer.git "$source_dir"
fi

if [[ -n "$(git -C "$source_dir" status --porcelain)" ]]; then
    if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$upstream_commit" ]] \
            || ! git -C "$source_dir" apply --reverse --check "$patch_file"; then
        echo "Refusing to change an unrelated dirty Printer checkout: $source_dir" >&2
        exit 1
    fi
else
    git -C "$source_dir" fetch origin "$upstream_commit"
    git -C "$source_dir" checkout --detach "$upstream_commit"
    if ! git -C "$source_dir" apply --check "$patch_file"; then
        echo "Printer patch does not apply cleanly to $upstream_commit" >&2
        exit 1
    fi
    git -C "$source_dir" apply "$patch_file"
fi

if [[ -d "$repo_root/runtime/jdk25/Contents/Home" ]]; then
    export JAVA_HOME="$repo_root/runtime/jdk25/Contents/Home"
fi

(
    cd "$source_dir"
    ./gradlew :1.21.11:build
)

find "$source_dir/versions/1.21.11/build/libs" -maxdepth 1 -type f \
    -name 'litematica-printer-*.jar' ! -name '*-sources.jar' -print
