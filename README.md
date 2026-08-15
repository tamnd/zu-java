# zu for the JVM

The Java client for [zu](https://github.com/tamnd/zu), an embedded property-graph database, plus Kotlin and Scala idiom layers over it.

```java
import dev.zudb.*;

try (Database db = Database.open("social.zu1");
     Connection conn = db.connect()) {

    conn.execute("CREATE NODE TABLE Person(id INT64 PRIMARY KEY, name STRING)");
    conn.loadCsv("Person", Path.of("people.csv"));

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
```

Text blocks for queries, try-with-resources for every handle, `Stream<Row>` for iteration. Nothing here should surprise a Java developer, which is the whole goal.

## How it binds

The Foreign Function and Memory API (Panama) is the primary path, with `jextract` generating the bindings from `zu.h` and `MemorySegment` giving genuinely zero-copy column access. There is no hand-written JNI shim on that path and no native code beyond `libzu` itself.

An SDK that requires a recent JDK in 2026 excludes a large part of the enterprise ecosystem, so there is a JNI provider too:

| Artifact | Baseline | Role |
|---|---|---|
| `dev.zudb:zudb` | Java 17 | the API, no native code, no FFM types in the public surface |
| `dev.zudb:zudb-ffm` | Java 22+ | the FFM provider, selected automatically |
| `dev.zudb:zudb-jni` | Java 17+ | the fallback provider |
| `dev.zudb:zudb-native-{platform}` | | the `libzu` binaries |

A `ServiceLoader` picks the provider at runtime and logs the choice once at debug level. Application code never names one. Baseline for the modern artifact is **Java 25 LTS**, CI runs 17, 21, 25, and 26.

One thing to know before your first run: from JDK 24, native access must be granted explicitly. The jars carry `Enable-Native-Access: ALL-UNNAMED` for the classpath case, the docs give the exact `--enable-native-access=dev.zudb` flag for the module path, and the binding detects the ungranted state at `Database.open` and throws a message containing the flag you need. A JVM warning on stderr three frames from any of our code is not a diagnosis anyone can act on.

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
