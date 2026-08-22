#!/usr/bin/env bash
#
# What the engine allocated through this binding, and did not get back.
#
# A JVM is not a program a leak checker was designed for. It allocates at
# startup, holds most of it for the life of the process, and frees almost
# none of it at exit, on purpose, because an exiting process has an operating
# system to give its pages back and a shutdown that walks them is time spent
# for nobody. Point LeakSanitizer at a JVM that does nothing at all and it
# reports about a megabyte in several thousand allocations, every one of them
# the JVM's own and none of them anything a user of this client can act on.
#
# So the question this script asks is narrower than "did anything leak". It
# is: of the blocks nobody freed, is any of them one the engine allocated.
# That is answerable, because a leak record carries the stack it was
# allocated from, and a block that came out of libzu has libzu in its stack.
# The filter below is that sentence, and it is the whole gate.
#
# Which makes the negative case load bearing. A report with no libzu in it
# looks exactly like a report from a run where the sanitizer was never
# loaded, the library was never called, or the driver exited early, and
# three of those four are green for the wrong reason. So the gate runs
# first: the same driver, told to drop a database, a connection, a
# statement, a result, an appender and a frame on the floor, which has to
# come back with libzu in the report. If it does not, this script has
# stopped measuring anything and says so.
#
# Linux only. LeakSanitizer is not available on macOS at all, and the
# darwin rows in CI get the misuse suite's descriptor counting instead,
# which catches a leaked handle without needing an allocator to agree.

set -euo pipefail

provider="${1:-ffm}"
rounds="${2:-25}"

case "$provider" in
    ffm|jni) ;;
    *) echo "usage: $0 [ffm|jni] [rounds]"; exit 2 ;;
esac

if [ "$(uname -s)" != "Linux" ]; then
    echo "LeakSanitizer is a Linux tool, and this is $(uname -s)"
    exit 1
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
work="${TMPDIR:-/tmp}/zu-leaks-$provider"
rm -rf "$work"
mkdir -p "$work"

step() { printf '\n=== %s\n' "$1"; }

# The sanitizer runtime, which has to be first in the process so that its
# allocator is the one both sides call. A gcc install has one and knows
# where it is, and asking it is more durable than a path with a version in
# it.
step "the runtime this run is watched by"
asan="$(gcc -print-file-name=libasan.so)"
if [ "$asan" = "libasan.so" ] || [ ! -e "$asan" ]; then
    echo "no libasan.so, which on Debian and Ubuntu is in libasan or gcc"
    exit 1
fi
echo "$asan"

# Without a symbolizer every frame is a hexadecimal address, the filter
# below has no name to match, and the report comes back empty for a reason
# that has nothing to do with leaks. Same resolution zu-go uses.
symbolizer="$(command -v llvm-symbolizer || ls /usr/bin/llvm-symbolizer-* 2>/dev/null | head -1 || true)"
if [ -z "$symbolizer" ]; then
    echo "no llvm-symbolizer, so every frame would be an address and nothing would match"
    exit 1
fi
echo "$symbolizer"

step "the engine this run calls"
if [ -z "${ZU_LIBRARY:-}" ]; then
    echo "set ZU_LIBRARY to a libzu.so, which is what CI does after it builds one"
    exit 1
fi
ls -l "$ZU_LIBRARY"

step "building the driver and the $provider provider"
modules="zudb,zudb-tck,zudb-$provider"
mvn -B -ntp -pl "$modules" -am -DskipTests package

classpath="$root/zudb/target/classes:$root/zudb-tck/target/classes:$root/zudb-$provider/target/classes"

# handle_segv off and a user handler allowed, because the JVM installs
# signal handlers of its own and uses the faults they catch as ordinary
# control flow: null checks, safepoint polls, stack banging. A sanitizer
# that takes those first turns a working JVM into a crash on the first
# query. exitcode 0 because the report is what is being read here, not the
# status, and detect_odr_violation off because a JVM maps the same symbols
# from more than one place and means to.
export ASAN_OPTIONS="detect_leaks=1:handle_segv=0:allow_user_segv_handler=1:detect_odr_violation=0:abort_on_error=0:exitcode=0"
export ASAN_SYMBOLIZER_PATH="$symbolizer"
export LD_PRELOAD="$asan"

# From JDK 24 native access belongs to whoever starts the JVM, and this is
# that. Every JDK since 17 accepts the flag, so asking whether it does is
# cheaper than deciding from a version number.
grant=""
if java --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    grant="--enable-native-access=ALL-UNNAMED"
fi

# One leak record, in the shape LSan writes them: a heading, the stack that
# allocated it, and a blank line. Prints the records that are ours, and what
# makes a record ours is frame #1 rather than any frame, because #0 is the
# sanitizer's own interceptor and #1 is whoever called malloc.
#
# The distinction earns its keep on the JNI row. Asking for a jmethodID
# allocates a JVM-side table entry which the JVM never frees, by design, and
# the stack for it runs through the shim because the shim is what asked. Any
# frame at all would call that ours and it is not: at #1 it is os::malloc in
# libjvm. A block the shim really did allocate has the shim at #1 and is
# caught, which is the point of naming the shim here at all.
#
# The symbol test beside the library names is for an engine built with debug
# info, where a frame reads zu_query at a Rust source line instead of naming
# the library it came out of.
ours() {
    awk '
        /^(Direct|Indirect) leak of/ { inside = 1; count = 0; ours = 0 }
        !inside { next }
        { record[++count] = $0 }
        /^[[:space:]]*#1 / && (/libzu\.(so|dylib)/ || /libzudb_jni\./ || /in zu_[a-z]/) {
            ours = 1
        }
        /^[[:space:]]*$/ {
            if (ours) { for (i = 1; i <= count; i++) print record[i] }
            inside = 0
        }
    ' "$1"
}

# The wider question, for the line below that says what was let through: how
# many records name one of ours anywhere in the stack rather than at the top
# of it.
mentions() {
    awk '
        /^(Direct|Indirect) leak of/ { inside = 1; seen = 0 }
        !inside { next }
        /libzu\.(so|dylib)/ || /libzudb_jni\./ || /in zu_[a-z]/ { seen = 1 }
        /^[[:space:]]*$/ { if (seen) total++; inside = 0 }
        END { print total + 0 }
    ' "$1"
}

run() {
    local name="$1" file="$2"
    shift 2
    set +e
    # shellcheck disable=SC2086
    env "$@" java ${grant:+$grant} -Xmx512m -Dzu.provider="$provider" \
        -cp "$classpath" dev.zudb.tck.Leaks "$rounds" \
        > "$work/$name.out" 2> "$file"
    local status=$?
    set -e
    cat "$work/$name.out"
    if [ $status -ne 0 ]; then
        echo "the driver exited $status, and a driver that did not finish has not measured anything"
        sed -n '1,80p' "$file"
        exit 1
    fi
}

step "the gate: one of everything dropped on the floor"
run gate "$work/gate.txt" ZU_LEAK_GATE=1
ours "$work/gate.txt" > "$work/gate-ours.txt"
if [ ! -s "$work/gate-ours.txt" ]; then
    echo "the gate leaked on purpose and the report has no libzu in it, so this"
    echo "script is not measuring what it says it measures"
    grep -c "leak of" "$work/gate.txt" || true
    sed -n '1,40p' "$work/gate.txt"
    exit 1
fi
echo "the gate leaked and was caught, in $(grep -c "leak of" "$work/gate-ours.txt") records:"
sed -n '1,12p' "$work/gate-ours.txt"

step "the run that is meant to be clean"
run clean "$work/clean.txt"
ours "$work/clean.txt" > "$work/clean-ours.txt"

step "what the report says"
grep "^SUMMARY: AddressSanitizer" "$work/clean.txt" || echo "no summary, which means nothing leaked at all"
echo "records in total: $(grep -c "leak of" "$work/clean.txt" || true)"
echo "records allocated by us: $(grep -c "leak of" "$work/clean-ours.txt" || true)"
# Said out loud rather than dropped quietly, because a record that names one
# of our libraries somewhere below the top of its stack is a record this
# script decided not to fail on, and a decision nobody can see is a decision
# nobody can argue with. On the JNI row this number is the JVM's jmethodID
# table and is expected to be small and steady.
echo "records that only pass through us: $(($(mentions "$work/clean.txt") - $(grep -c "leak of" "$work/clean-ours.txt" || true)))"

if [ -s "$work/clean-ours.txt" ]; then
    echo
    echo "the engine allocated these through the $provider provider and never got them back:"
    cat "$work/clean-ours.txt"
    exit 1
fi

step "nothing the engine allocated is still out, on the $provider provider"
