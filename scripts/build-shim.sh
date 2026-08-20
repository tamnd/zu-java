#!/usr/bin/env bash
# Build the JNI shim for the machine this runs on.
#
# Usage: scripts/build-shim.sh [output directory]
#
# The shim is one C file and it links against nothing: it opens libzu at
# run time and resolves what it calls, so all this needs is a C compiler
# and the JDK headers. That is what makes seven platform builds cheap
# enough to do on seven runners, and it is why a shim built today opens
# a libzu built next year at the same ABI.
#
# One platform a run, the host's, because a C cross compiler for six
# other platforms is not something a laptop has and not something worth
# pretending about. CI runs this on each runner of its matrix and
# collects the results into the tree that zudb-jni packages.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/zudb-jni/shim}"
src="$here/zudb-jni/src/main/c/zudb_jni.c"

if [ -z "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME is not set, and the shim is built against the JDK headers" >&2
    exit 1
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "$JAVA_HOME has no include/jni.h, so it is a JRE rather than a JDK" >&2
    exit 1
fi

# The same two names every other client of this engine uses, so that the
# resource path a jar carries and the directory a release archive holds
# are spelled the same on all of them.
case "$(uname -s)" in
    Darwin) goos=darwin ;;
    Linux)  goos=linux ;;
    MINGW*|MSYS*|CYGWIN*) goos=windows ;;
    *) echo "no rule for $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) goarch=amd64 ;;
    arm64|aarch64) goarch=arm64 ;;
    *) echo "no rule for $(uname -m)" >&2; exit 1 ;;
esac

platform="$goos-$goarch"
# Alpine is not a smaller Linux, it is a different one, and the shim
# built against glibc does not load there. Asked of the loader rather
# than of /etc/os-release, which a container can be missing.
if [ "$goos" = linux ] && ldd /bin/sh 2>&1 | grep -q musl; then
    platform="$platform-musl"
fi

mkdir -p "$out/$platform"

case "$goos" in
    darwin)
        library="libzudb_jni.dylib"
        # CFLAGS unquoted on purpose: it is how the release passes an
        # -arch, and one runner building for both of this platform's
        # instruction sets is cheaper than two runners.
        # shellcheck disable=SC2086
        "${CC:-cc}" -O2 -fPIC -shared -fvisibility=hidden ${CFLAGS:-} \
            -Wall -Wextra -Werror \
            -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
            -o "$out/$platform/$library" "$src"
        ;;
    linux)
        library="libzudb_jni.so"
        # -ldl for the glibc older than 2.34 that still has dlopen in a
        # library of its own, and harmless on the ones that do not.
        # shellcheck disable=SC2086
        "${CC:-cc}" -O2 -fPIC -shared -fvisibility=hidden ${CFLAGS:-} \
            -Wall -Wextra -Werror \
            -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
            -o "$out/$platform/$library" "$src" -ldl
        ;;
    windows)
        library="zudb_jni.dll"
        if command -v cl >/dev/null 2>&1; then
            # cl writes its output beside its input unless told, and it
            # is told in the compiler's spelling rather than the shell's.
            cl //nologo //O2 //W3 //WX //LD \
                //I"$JAVA_HOME/include" //I"$JAVA_HOME/include/win32" \
                "$src" //Fe:"$out/$platform/$library" //Fo:"$out/$platform/"
        else
            "${CC:-x86_64-w64-mingw32-gcc}" -O2 -shared \
                -Wall -Wextra -Werror \
                -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" \
                -o "$out/$platform/$library" "$src"
        fi
        ;;
esac

# A library a loader has to be able to map, whatever the umask was.
chmod 0755 "$out/$platform/$library"
echo "$platform  $(du -h "$out/$platform/$library" | cut -f1)"
