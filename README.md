# zu for the JVM

The Java client for [zu](https://github.com/tamnd/zu), an embedded property-graph database, plus Kotlin and Scala idiom layers over it.

```java
import dev.zudb.*;

try (Database db = Database.open("social.zu1");
     Connection conn = db.connect()) {

    try (Result result = conn.query("""
            MATCH (p:Person)-[:Follows]->(f)
            RETURN p.name AS name, count(*) AS n ORDER BY n DESC LIMIT 5
            """)) {
        result.stream()
              .map(r -> r.getString("name") + ": " + r.getLong("n"))
              .forEach(System.out::println);
    }
}
```

```xml
<dependency>
  <groupId>dev.zudb</groupId>
  <artifactId>zudb</artifactId>
  <version>${zu.version}</version>
</dependency>
<dependency>
  <groupId>dev.zudb</groupId>
  <artifactId>zudb-ffm</artifactId>
  <version>${zu.version}</version>
  <scope>runtime</scope>
</dependency>
```

Text blocks for queries, try-with-resources for every handle, `Stream<Row>` for iteration. Nothing here should surprise a Java developer, which is the whole goal.

## Reading a column without reading a row

A row at a time is the shape most callers want, and it is not the shape that makes an embedded database worth embedding. Every column of a result is also readable as one borrowed buffer over the engine's own memory, with no copy and no per-row call:

```java
try (Result r = conn.query("MATCH (p:Person) RETURN p.age")) {
    LongBuffer ages = r.longs(0);
    ByteBuffer valid = r.valid(0);

    long total = 0;
    for (int i = 0; i < ages.remaining(); i++) {
        if (valid.get(i) != 0) {
            total += ages.get(i);
        }
    }
}
```

The buffers are read-only views in native byte order, and they are valid until the `Result` closes. A result larger than one chunk is readable a chunk at a time through `r.chunks()`, which is the path that does not need the whole column resident. `java.nio` rather than `MemorySegment` on purpose: a Java 17 caller can name a `LongBuffer`, and both providers can hand one back without copying.

What it is worth, summing one integer column of a hundred thousand rows on an M-series laptop, JDK 25:

| How | Per row |
|---|---|
| `r.longs(0)` and a loop over the buffer | 0.45 ns |
| the same a chunk at a time | 4.1 ns |
| `for (Row row : r) row.getLong(0)` | 45 ns |
| `r.stream().mapToLong(...)` | 67 ns |

A row at a time is a boundary crossing a cell, and a hundred crossings cost about what one borrowed buffer costs. Both surfaces are there because both are the right answer to a different question, but a loop over a million rows should be reading a column.

## Getting rows in

Two ways, and which one you want follows from whether the database exists yet. There is a third below for the rows that should not go in at all.

A loader builds one out of whole columns. It is the fastest way values get in and, while the engine has no DDL, it is the only way a table comes into being at all:

```java
try (Loader loader = Loader.create(Path.of("social.zu1"))) {
    loader.table("Person", "Follows", 3);
    loader.column("id", 1L, 2L, 3L);
    loader.column("name", "ada", "grace", "alan");
    loader.edges(new int[] {0, 1}, new int[] {1, 2});
    loader.finish();
}
```

Columns go in as arrays or as `java.nio` buffers, and which you pass is the difference between a copy and no copy. A direct buffer is read where it lies, so the engine sees the memory your program already filled and nothing crosses the boundary but a pointer. An array is memory nothing outside the JVM can address, so it is copied off-heap first. `Linker.Option.critical(true)` would let a Java array through without either, at the price of blocking the collector for the length of the copy, and on a column this size that is not a trade worth making.

An appender adds rows to a table that already exists, a value at a time, with no statement anywhere near it:

```java
try (Appender rows = conn.appender("Person")) {
    rows.append(4L).append("hedy").endRow();
    rows.append(5L).append("katherine").endRow();
    rows.finish();
}
```

Values are written in the order the table declares its columns, which `columnName(int)` will tell you, and a row is a row once `endRow()` has ended it. A value the column will not take ends its row there and rolls back the values already written into it, so a refused append never leaves half a row behind. Closing an appender that was never finished writes what it has anyway, because a loop that threw halfway should keep the rows it managed; `discard()` is there for when it should not.

What each is worth on an M-series laptop, JDK 25:

| How | Per row |
|---|---|
| `loader.column(name, direct LongBuffer)` | 0.44 ns |
| `loader.column(name, long[])` | 1.1 ns |
| `loader.column(name, List<String>)` | 108 ns |
| a whole two-column load, write included | 630 ns |
| `appender.append(...).endRow()` | 87 ns |
| the same row as an `INSERT` statement | 4.0 ms |

The first two lines are the copy: 0.68 ns a row over a hundred thousand rows is 68 microseconds to move 800 KB, which is about what a memcpy costs and about what a direct buffer saves. It is a small share of a load that also writes a file, and it is the whole difference at the boundary itself.

The last line is the one to read twice. A statement per row parses, plans, runs and commits per row, and none of that work says anything the row before it did not already say. That is what an appender is for.

## Querying memory you already hold

The third way is not to get the rows in at all. A frame names columns your program is already holding as a table of one connection, and statements read your buffers where they lie:

```java
LongBuffer ids = ByteBuffer.allocateDirect(3 * 8).order(nativeOrder()).asLongBuffer();
ids.put(new long[] {1, 2, 3}).flip();

try (Frame people = Frame.of("Person", 3)) {
    people.column("id", ids);
    conn.register(people);
    try (Result r = conn.query("MATCH (p:Person) RETURN sum(p.id) AS total")) {
        System.out.println(r.row(0).getLong(0));
    }
}
```

Nothing is copied at registration and nothing is copied at read. A scan builds vectors pointing straight at your buffers wherever the layouts agree, and the layouts that agree are the ones Arrow and this engine both keep: 64-bit signed integers, doubles, one bit a row for a boolean, and characters end to end with offsets cutting them up. A narrower integer, an unsigned one, a single-precision float and Arrow's microseconds against the nanoseconds this engine keeps time in are widened a value at a time as a statement reaches them, so a frame of a hundred columns costs you the one the statement named.

Every buffer has to be direct, and one that is not is refused rather than copied. Everywhere else in this API a heap buffer costs a memcpy and nothing else, because the call reads it and is finished. A frame keeps the pointer for as long as it is registered, so a copy here would mean the engine reading a copy of your data for the rest of the frame's life, which is a frame that is not a frame.

The buffers stay yours and the library never writes one, but a direct buffer is looked after for you: the frame holds a reference to everything handed to it and lets go at the moment the engine says it has finished, which is after the last statement reading the frame ends and is neither the unregister that preceded it nor the close. So a buffer cannot be collected out from under a running statement. The optional release callback runs at that same moment, and it is there for the other kind of buffer, one over memory you allocated yourself or one a lock has to be taken to let go of.

A frame is read only and has no edges. A statement that would write to one is refused, a name a stored table already holds is refused, a name another frame holds replaces that frame, and registering inside a transaction is refused because a table appearing halfway through one is not something the transaction could then be rolled back over.

A million rows, three columns, the same laptop:

| How | Per row |
|---|---|
| describing them and registering them | 0.51 ns |
| the same million rows through a loader, write included | 630 ns |
| `sum(p.id)` over a frame | 1.06 ns |
| `sum(p.id)` over the same numbers in a database | 1.14 ns |
| the same over a 32-bit column, which is widened as it is read | 1.32 ns |
| a string comparison over a frame | 2.26 ns |
| the same over a database | 3.89 ns |

The first two lines are the whole point. Half a millisecond to make a million rows queryable against six hundred to write them down, and the three lines after that say the query is not paying for it afterwards.

## Watching a statement, and stopping it

Three ways, and they are the same mechanism seen from different sides. `conn.interrupt()` is safe from another thread and stops whatever is running. `conn.rowsRead()` is safe from another thread too and says how far it has got. And a progress callback is both of those without the thread that would otherwise have to do the polling:

```java
long deadline = 30_000;
conn.onProgress(Duration.ofMillis(250), (rows, millis) -> millis < deadline);
```

Answering false stops the statement exactly as `interrupt()` would, so a timeout is a one line watcher and a progress bar is the same watcher with a repaint in it. The arrangement belongs to the connection and covers every statement after it, so it is set once when the connection is opened rather than around each query.

The callback runs on a thread of the library's, one per statement, never two at once and never after the statement it belongs to has returned. Two things follow. Whatever it touches has to be usable from another thread, so a counter a progress bar reads should be an `AtomicLong` rather than a field. And it must not call back into the library on the connection it is reporting on, because that connection is inside the executor.

This is the other of the two places a pointer to Java code goes the other way. An exception crossing an upcall would take the JVM down, so a watcher that throws is logged and answered as though it had asked for the statement to stop, which is the reading that loses least: a callback that threw is a program that has stopped wanting the answer.

## How it binds

The Foreign Function and Memory API is the primary path. The downcall handles are written by hand against `zu.h` rather than generated with `jextract`, because the C ABI here is around seventy functions with a stable shape, and a hand-written layer is where the interesting decisions live: which calls are `Linker.Option.critical` because they are short pure accessors, where the out-parameter scratch space comes from so that a query does not allocate, and how a `zu_error` becomes a typed Java exception exactly once. There is no native code in this repository beyond `libzu` itself.

An SDK that requires a recent JDK in 2026 excludes a large part of the enterprise ecosystem, so there is a JNI provider too:

| Artifact | Baseline | Role |
|---|---|---|
| `dev.zudb:zudb` | Java 17 | the API, no native code, no FFM types in the public surface |
| `dev.zudb:zudb-ffm` | Java 25 | the FFM provider, selected automatically |
| `dev.zudb:zudb-jni` | Java 17 | the fallback provider |
| `dev.zudb:zudb-native-{platform}` | | the `libzu` binaries |

A `ServiceLoader` picks the provider at run time and application code never names one. The FFM artifact targets Java 25 rather than the Java 22 that finalised the API, because 22 has been out of support since September 2024 and shipping against an unsupported release only moves the problem. CI runs 17, 21, 25, and 26.

One thing to know before your first run: from JDK 24, native access must be granted explicitly. The jars carry `Enable-Native-Access: ALL-UNNAMED` for the class path case, the module path case wants `--enable-native-access=dev.zudb.ffm`, and the provider checks `Module::isNativeAccessEnabled` before the first downcall so that the failure is an exception naming the flag rather than a JVM warning on stderr three frames from any of our code.

## Errors

Every failure is a `ZuException`, and the subclass is chosen from the GQLSTATUS class rather than from the message: `ZuSyntaxException` for 42, `ZuDataException` for 22, `ZuTransactionException` for 25 and 40, and so on down. The exception carries the whole diagnostic, so a caller reads fields instead of parsing prose:

```java
catch (ZuSyntaxException e) {
    e.code();                 // the GQLSTATUS, for example 42001
    e.condition();            // its standard text
    e.position();             // line, column and byte offset, when there is one
    e.caret().ifPresent(System.err::println);
    e.retryable();            // whether running it again could work
}
```

## What works today

The engine has no DDL yet, so there is no `CREATE NODE TABLE` and no statement in this client writes a schema. A table comes into being through `Loader`, which is why the loader example above builds the graph the example at the top of this file reads. What runs against a fresh database with nothing in it is the expression and projection surface: `RETURN`, `UNWIND`, parameters, lists, records, and the temporal types.

## Building

```sh
mvn test -Dzu.library=/path/to/libzu.dylib
```

The provider looks at `-Dzu.library`, then `ZU_LIBRARY`, then the platform library path. The tests skip rather than fail when no `libzu` is reachable, so a checkout with no engine build beside it is still green.

The benchmarks are JMH and are not published:

```sh
mvn package -DskipTests
ZU_LIBRARY=/path/to/libzu.dylib java -jar zudb-bench/target/benchmarks.jar
```

`ZU_LIBRARY` rather than `-Dzu.library` there, because JMH forks a JVM of its own and a fork inherits the environment rather than the system properties.

## Beyond Java

- **Kotlin** (`dev.zudb:zudb-kotlin`, tier 2), a thin extension layer, not a second binding. `use`, `Sequence`, `Flow` with cancellation wired to `interrupt()`, and an inline `transaction { }`.
- **Scala 3** (`dev.zudb:zudb-scala_3`, tier 2), `Using`, `LazyList`, `Option` for NULL, a derivable `RowDecoder[T]`, and `Either[ZuError, A]` variants for callers who prefer errors as values.
- **JUnit 5** (`dev.zudb:zudb-junit5`), a `@ZuGraph` extension injecting a temp-file connection, so a test is two lines.

Virtual threads get a straight answer rather than a shrug: a query is a native downcall and pins its carrier thread for the duration, so the documented pattern is a bounded platform-thread executor for queries and virtual threads for everything else. GraalVM native-image works, with reachability metadata in the jar and a native-image build of the sample app in CI.

## Specification

Spec/2064g/dx/08-jvm.md in [tamnd/zu](https://github.com/tamnd/zu). Milestone: DX4 (tamnd/zu#170).

## Status

Pre-1.0 and pre-release. Nothing is published yet. The engine, the C ABI, and this client all move on one version number, so a release here always pairs with the same release of [`tamnd/zu`](https://github.com/tamnd/zu).

## Where things live

| What | Where |
|---|---|
| Engine, Rust SDK, CLI, `zu.h`, conformance corpus | [tamnd/zu](https://github.com/tamnd/zu) |
| Documentation and website | [tamnd/zu-web](https://github.com/tamnd/zu-web) |
| This client | here |

If a bug reproduces through the `zu` CLI, it belongs in [tamnd/zu](https://github.com/tamnd/zu/issues), not here.

## License

Apache-2.0, same as the engine.
