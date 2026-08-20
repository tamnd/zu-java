package dev.zudb.bench;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.Statement;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What a whole statement costs, from the call to the closed result.
 *
 * <p>These numbers are the engine's work plus the boundary's, and the
 * engine's dominates. What they are good for is the shape of the fixed cost
 * a caller pays per statement, which is what decides whether a loop should
 * prepare once or ask twice. The cost of reading rows out of a result is
 * measured on its own in {@link ReadBench}, where the parse is not in the
 * way of it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
// JMH forks a JVM of its own with a command line it writes, so the manifest
// attribute on this jar never reaches the process that runs the benchmark
// and the grant has to be here. For the same reason the library is found
// through ZU_LIBRARY rather than -Dzu.library: a fork inherits the
// environment and does not inherit system properties.
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class QueryBench {

  private Database db;
  private Connection conn;
  private Statement prepared;
  private Statement preparedConstant;

  @Setup
  public void open() {
    db = Database.memory();
    conn = db.connect();
    prepared = conn.prepare("RETURN $v AS v");
    preparedConstant = conn.prepare("RETURN 1 AS v");
  }

  @TearDown
  public void close() {
    prepared.close();
    preparedConstant.close();
    conn.close();
    db.close();
  }

  /** Parse, plan, run, one row out. The floor for anything at all. */
  @Benchmark
  public long oneRowOneColumn() {
    try (Result r = conn.query("RETURN 1 AS v")) {
      return r.row(0).getLong("v");
    }
  }

  /** The same without the parse, which is what a loop should be doing. */
  @Benchmark
  public long preparedNoParameters() {
    try (Result r = preparedConstant.execute()) {
      return r.row(0).getLong("v");
    }
  }

  /** The same again with a value crossing in, which is what a loop needs. */
  @Benchmark
  public long preparedOneParameter() {
    try (Result r = prepared.bind("v", 1L).execute()) {
      return r.row(0).getLong("v");
    }
  }

  /** The bind on its own, so the line above splits into its two halves. */
  @Benchmark
  public Statement bindOnly() {
    return prepared.bind("v", 1L);
  }

  /**
   * A string literal end to end. Against {@link #oneRowOneColumn} this is
   * what a string costs over an integer, and most of it is the engine
   * making the value rather than this client copying it out.
   */
  @Benchmark
  public String oneStringCell() {
    try (Result r = conn.query("RETURN 'ada lovelace' AS v")) {
      return r.row(0).getString("v");
    }
  }

  /** The same statement with nothing read, which is the half above it. */
  @Benchmark
  public void oneStringCellUnread(Blackhole hole) {
    try (Result r = conn.query("RETURN 'ada lovelace' AS v")) {
      hole.consume(r.rows());
    }
  }

  /** What a connection costs, for anyone thinking about pooling one. */
  @Benchmark
  public void connectAndClose(Blackhole hole) {
    try (Connection c = db.connect()) {
      hole.consume(c);
    }
  }
}
