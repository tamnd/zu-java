#!/usr/bin/env bash
# Install this client the way a user does, on a machine that has never
# heard of it.
#
# This runs inside a container that holds a JDK, Maven, and nothing
# else: no Rust, no C compiler, no libzu, no header, no pkg-config file,
# and no checkout of the engine. What it does is what the front page
# tells a reader to do, which is add three lines to a pom and write the
# program on the page, and what it proves is that those two things are
# the whole of it.
#
# The point is not that the build passes. It is that the build passes
# here. A repository's own machine has the engine on it, has a toolchain
# on it, and has an environment variable pointing at a library, and
# every one of those is a way for an install to work for a reason the
# user does not have. So this asserts each of them is absent before it
# starts, and then it lets Maven do the rest.
#
# Read from the environment, all set by .github/workflows/install.yml:
#
#   SRC       the checkout, mounted read only, for the README
#   REPO      the repository the artifacts were staged into, read only
#   APP       a writable directory of this run's own
#   VERSION   the version that was staged
#   PROVIDER  zudb-ffm or zudb-jni, whichever this JDK is for
#   ABSENT    the tools that must not be on this machine
set -euo pipefail

SRC="${SRC:-/src}"
REPO="${REPO:-/repo}"
APP="${APP:-/app}"
VERSION="${VERSION:?the version that was staged}"
PROVIDER="${PROVIDER:?zudb-ffm or zudb-jni}"
ABSENT="${ABSENT:-}"

readme="$SRC/README.md"

step() { printf '\n=== %s\n' "$1"; }

step "what this machine is"
java -version 2>&1 | head -1
mvn -v 2>&1 | head -1
uname -sm

# Every Maven run here resolves into a repository of this run's own, so
# that what came over the wire is a directory that can be measured and
# not a number nobody has.
mvn() { command mvn -B -ntp -Dmaven.repo.local="$APP/m2" "$@"; }

step "and what it is not"
# A tool that is here anyway makes this whole run mean nothing, and it
# is the kind of thing that arrives in a base image without anybody
# choosing it, so it is asked about rather than assumed.
for tool in $ABSENT; do
    if command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is on this machine, so it is not the clean one this test needs" >&2
        exit 1
    fi
    echo "no $tool"
done

# The engine, in each of the shapes a machine can already have it in.
# The last two are the ones that would make this pass for the wrong
# reason: a library on the loader's path, or a variable naming one.
if command -v pkg-config >/dev/null 2>&1 && pkg-config --exists zu; then
    echo "pkg-config knows zu on this machine" >&2
    exit 1
fi
for header in /usr/include/zu.h /usr/local/include/zu.h; do
    if [ -e "$header" ]; then
        echo "$header is on this machine" >&2
        exit 1
    fi
done
found="$(find / -xdev \( -name 'libzu.so*' -o -name 'libzu.a' -o -name 'libzu.dylib' \) \
    -not -path "$SRC/*" 2>/dev/null || true)"
if [ -n "$found" ]; then
    echo "there is already a libzu on this machine:" >&2
    echo "$found" >&2
    exit 1
fi
if [ -n "${ZU_LIBRARY:-}" ]; then
    echo "ZU_LIBRARY is set, which is the other way this passes for the wrong reason" >&2
    exit 1
fi
echo "no libzu, no header, no pkg-config file, no ZU_LIBRARY"

step "the program off the front page"
# The same rule the README test uses: a fenced Java block that declares
# a public class is a whole program, and the block after it is what it
# prints. Taken off the page character for character, because a
# quickstart that was retyped here is a quickstart nobody checked.
program="$(awk '
    $0 == "```java" && !taken { inside = 1; count = 0; whole = 0; next }
    inside && $0 == "```" {
        inside = 0
        if (whole) { for (i = 1; i <= count; i++) print line[i]; taken = 1; exit }
        next
    }
    inside { line[++count] = $0; if ($0 ~ /^public class /) whole = 1 }
' "$readme")"
expected="$(awk '
    $0 == "```java" && !after { inside = 1; whole = 0; next }
    inside && $0 == "```" { inside = 0; if (whole) after = 1; next }
    inside { if ($0 ~ /^public class /) whole = 1; next }
    after && $0 == "```" { if (out) exit; out = 1; next }
    out { print }
' "$readme")"
name="$(printf '%s\n' "$program" | sed -n 's/^public class \([A-Za-z0-9_]*\) .*/\1/p' | head -1)"
if [ -z "$name" ] || [ -z "$expected" ]; then
    echo "no whole program with an output block on $readme" >&2
    exit 1
fi
echo "$name, and the ${#expected} characters it says it prints"

step "the dependencies off the front page"
# Every dependency element the page prints, filtered to the three this
# row installs. Lifting them rather than writing them here is what makes
# a groupId that changed on the page a red job: a reader who copies
# those lines gets exactly what this pom gets.
dependencies="$(awk -v want=" zudb $PROVIDER zudb-native " '
    $0 == "```xml" { inside = 1; next }
    inside && $0 == "```" { inside = 0; next }
    !inside { next }
    /<dependency>/ { count = 0; keep = 0 }
    { block[++count] = $0 }
    /<artifactId>/ {
        id = $0
        sub(/.*<artifactId>/, "", id)
        sub(/<\/artifactId>.*/, "", id)
        if (index(want, " " id " ")) keep = 1
    }
    /<\/dependency>/ { if (keep) for (i = 1; i <= count; i++) print block[i] }
' "$readme")"
for wanted in zudb "$PROVIDER" zudb-native; do
    if ! printf '%s\n' "$dependencies" | grep -q "<artifactId>$wanted</artifactId>"; then
        echo "$readme prints no dependency on $wanted, so a reader cannot install one" >&2
        exit 1
    fi
done
printf '%s\n' "$dependencies" | grep '<artifactId>' | sed 's/[[:space:]]*//'

step "a project of two files"
work="$APP/quickstart"
rm -rf "$work"
mkdir -p "$work/src/main/java"
printf '%s\n' "$program" > "$work/src/main/java/$name.java"

# The staged repository stands in for Maven Central, which is where
# these coordinates resolve from the day the release goes out. Nothing
# else about the pom is special, and that is the claim: a dependency, a
# compiler release, and no plugin of ours anywhere in it.
cat > "$work/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>quickstart</artifactId>
  <version>1</version>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>17</maven.compiler.release>
    <zu.version>$VERSION</zu.version>
  </properties>

  <repositories>
    <repository>
      <id>staged</id>
      <url>file://$REPO</url>
      <releases><enabled>true</enabled></releases>
      <snapshots><enabled>false</enabled></snapshots>
    </repository>
  </repositories>

  <dependencies>
$dependencies
  </dependencies>
</project>
POM

step "mvn package, on a machine with no engine on it"
(cd "$work" && mvn package)

step "what a user's classpath came out as"
(cd "$work" && mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib)
ls -l "$work/target/lib"
jars="$(find "$work/target/lib" -name '*.jar' | wc -l)"
if [ "$jars" -ne 3 ]; then
    echo "three jars is what the page asks for and $jars is what arrived" >&2
    exit 1
fi

# The library is inside one of them, which is the whole reason a user
# who installed nothing has an engine. The platform asked about is this
# one, because a jar full of libraries for other machines is a jar that
# fails here and passes a test that only counted them.
here="dev/zudb/native/linux-amd64/libzu.so"
if ! jar tf "$work"/target/lib/zudb-native-*.jar | grep -qx "$here"; then
    echo "the zudb-native jar has no $here in it, so this machine has no engine" >&2
    jar tf "$work"/target/lib/zudb-native-*.jar | grep libzu >&2 || true
    exit 1
fi
echo "the engine came down inside the jar, at $here"

step "the program, in a directory of its own"
# From JDK 24 a program that calls native code has to say so when it
# starts, and the flag that says it arrived in 22. Which of those this
# JDK is gets asked of the JDK rather than looked up in a table here,
# because the row that runs on 17 and the row that runs on 25 are meant
# to be the same run with a different image under it.
grant=""
if java --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    grant="--enable-native-access=ALL-UNNAMED"
    echo "this JVM wants the grant, so it gets it: $grant"
else
    echo "this JVM has no such flag, which is every JDK before 22"
fi

# Somewhere else on the machine, empty, because the quickstart writes a
# database beside itself and a directory with one already in it is a
# different program.
run="$APP/run"
rm -rf "$run"
mkdir -p "$run"
set +e
(cd "$run" && java ${grant:+$grant} -cp "$work/target/classes:$work/target/lib/*" "$name" \
    > "$APP/stdout.txt" 2> "$APP/stderr.txt")
code=$?
set -e
if [ -s "$APP/stderr.txt" ]; then
    echo "it wrote to stderr, and a first run should not:" >&2
    cat "$APP/stderr.txt" >&2
    exit 1
fi
if [ "$code" -ne 0 ]; then
    echo "$name exited $code" >&2
    exit 1
fi
cat "$APP/stdout.txt"
if [ "$(cat "$APP/stdout.txt")" != "$expected" ]; then
    echo "the page says it prints" >&2
    printf '%s\n' "$expected" >&2
    echo "and it printed" >&2
    cat "$APP/stdout.txt" >&2
    exit 1
fi
echo "which is what $readme says it prints"

step "what it cost to get here"
du -sh "$APP/m2/dev/zudb" | cut -f1 | sed 's/$/ of artifacts, one platform of the seven/'

step "the failure this job is meant to catch, caught"
# A job that has only ever passed is a job nobody has seen fail. The way
# an install goes wrong on a machine like this one is the library: three
# dependencies work and two of them compile, so a reader who left the
# runtime one out has a program that builds and dies. What has to happen
# then is a message naming the line to add, and this is where that gets
# checked.
without="$APP/without"
rm -rf "$without"
cp -r "$work" "$without"
rm -f "$without"/target/lib/zudb-native-*.jar
set +e
(cd "$run" && java ${grant:+$grant} -cp "$without/target/classes:$without/target/lib/*" "$name" \
    > "$APP/without-stdout.txt" 2> "$APP/without-stderr.txt")
code=$?
set -e
if [ "$code" -eq 0 ]; then
    echo "it ran with no library artifact on the classpath, so it found one somewhere else" >&2
    exit 1
fi
said="$(cat "$APP/without-stderr.txt")"
case "$said" in
    *"a zudb-native artifact for "*) ;;
    *)
        echo "it failed without saying which artifact is missing:" >&2
        echo "$said" >&2
        exit 1
        ;;
esac
printf '%s\n' "$said" | grep -o 'no provider could bind libzu.*' | head -c 400
printf '\n'

step "installed, on a machine that had nothing"
