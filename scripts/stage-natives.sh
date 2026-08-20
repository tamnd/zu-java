#!/usr/bin/env bash
# Lay the seven libzu builds out where zudb-native packages them from.
#
# Usage: scripts/stage-natives.sh <source>
#
#   scripts/stage-natives.sh v0.11.0            a tag of tamnd/zu
#   scripts/stage-natives.sh ../zu/dist         a directory of built prefixes
#
# The engine names its targets the way Rust does and this client names
# its platforms the way Go does, because that is what every other client
# of this engine names its artifacts after. The table below is the only
# place the two spellings meet, so a target added to the engine is one
# row here and nothing else.
#
# What is copied is the shared library and only the shared library. The
# archive also carries the static library, the header, the CLI, the
# pkg-config file and the CMake package, and none of those is anything a
# JVM can use: a jar that held them would be a jar that is four times
# the size for a file nobody opens.
set -euo pipefail

source="${1:?usage: stage-natives.sh <tag or directory>}"
here="$(cd "$(dirname "$0")/.." && pwd)"
out="$here/zudb-native/lib"

# rust target, go platform, library file name
rows="
x86_64-unknown-linux-gnu    linux-amd64        libzu.so
aarch64-unknown-linux-gnu   linux-arm64        libzu.so
x86_64-unknown-linux-musl   linux-amd64-musl   libzu.so
aarch64-unknown-linux-musl  linux-arm64-musl   libzu.so
x86_64-apple-darwin         darwin-amd64       libzu.dylib
aarch64-apple-darwin        darwin-arm64       libzu.dylib
x86_64-pc-windows-msvc      windows-amd64      zu.dll
"

work=""
if [ -d "$source" ]; then
    prefixes="$source"
else
    # A tag, which means the release archives. Downloaded once into a
    # directory of this run's own, so that a second run of the script
    # cannot half-unpack over the first.
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    prefixes="$work"
    echo "downloading libzu $source from tamnd/zu"
    for target in $(echo "$rows" | awk 'NF {print $1}'); do
        archive="libzu-$target.tar.zst"
        gh release download "$source" --repo tamnd/zu --pattern "$archive" --dir "$work"
        # The documented fallback as well as the first choice, because
        # tar learned --zstd in 1.31 and RHEL 8 ships 1.30.
        if tar --zstd -tf "$work/$archive" >/dev/null 2>&1; then
            tar --zstd -xf "$work/$archive" -C "$work"
        else
            zstd -dc "$work/$archive" | tar -xf - -C "$work"
        fi
    done
fi

rm -rf "$out"
echo "$rows" | while read -r target platform library; do
    [ -n "$target" ] || continue
    from="$prefixes/libzu-$target"
    # The library lives in bin/ on Windows, where a DLL is a thing that
    # runs, and in lib/ everywhere else.
    if [ -f "$from/bin/$library" ]; then
        from="$from/bin/$library"
    else
        from="$from/lib/$library"
    fi
    if [ ! -f "$from" ]; then
        echo "no $library for $target under $prefixes" >&2
        exit 1
    fi
    mkdir -p "$out/$platform"
    cp "$from" "$out/$platform/$library"
    # A library a loader has to be able to map, whatever the transport
    # did to the mode on the way here.
    chmod 0755 "$out/$platform/$library"
    echo "$platform  $(du -h "$out/$platform/$library" | cut -f1)"
done
