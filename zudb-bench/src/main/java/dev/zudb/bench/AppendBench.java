package dev.zudb.bench;

import dev.zudb.Appender;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What adding a row to a table that already exists costs.
 *
 * <p>One benchmark invocation is one row, so the score is the row, which is
 * the unit a caller writes. Every iteration starts from a copy of a database
 * with one row in it, so a long run measures appending rather than a table
 * growing under it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class AppendBench {

  private Path dir;
  private Path template;
  private long counter;

  private Database db;
  private Connection conn;
  private Appender appender;

  @Setup
  public void build() throws IOException {
    dir = Files.createTempDirectory("zu-append-bench");
    // The table an appender appends to has to exist, and a bulk load is the
    // only thing that makes one.
    template = dir.resolve("template.zu");
    try (Loader loader = Loader.create(template)) {
      loader.table("Person", "Knows", 1);
      loader.column("id", -1L);
      loader.column("name", "seed");
      loader.finish();
    }
  }

  @TearDown
  public void clean() throws IOException {
    Temp.deleteTree(dir);
  }

  @Setup(Level.Iteration)
  public void open() throws IOException {
    Path path = dir.resolve("append-" + counter++ + ".zu");
    Files.copy(template, path);
    db = Database.open(path);
    conn = db.connect();
    appender = conn.appender("Person");
  }

  @TearDown(Level.Iteration)
  public void close() {
    if (appender != null) {
      appender.close();
      appender = null;
    }
    if (conn != null) {
      conn.close();
      conn = null;
    }
    if (db != null) {
      db.close();
      db = null;
    }
  }

  /** One row of two columns, written the way a loop that knows its schema writes it. */
  @Benchmark
  public void row() {
    appender.append(counter++).append("n").endRow();
  }

  /** The same row through the dynamic path, which costs a type test a value. */
  @Benchmark
  public void rowOfObjects() {
    appender.row(counter++, "n");
  }

  /** What the appender is worth, against the statement it replaces. */
  @Benchmark
  public void rowThroughAStatement() {
    conn.execute("INSERT (:Person {id: " + counter++ + ", name: 'n'})");
  }
}
