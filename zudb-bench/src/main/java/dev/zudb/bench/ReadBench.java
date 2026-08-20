package dev.zudb.bench;

import dev.zudb.Chunk;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.Row;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What reading rows out of a result costs, with the statement already run.
 *
 * <p>This is the number the columnar surface exists for. The result is
 * executed once in setup and read over and over, so what is measured is the
 * read and nothing else, and the score is per row rather than per call.
 *
 * <p>Holding one result open across the whole run is the point rather than
 * a shortcut: a borrowed buffer is valid until its result closes, and the
 * shape a caller should copy is exactly this one.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class ReadBench {

  /**
   * How many rows each invocation reads. A constant rather than a
   * parameter because the per-row score below is scaled by it, and JMH
   * wants that scale as a literal in an annotation.
   */
  private static final int ROWS = 100_000;

  private Database db;
  private Connection conn;
  private Result result;

  @Setup
  public void open() {
    db = Database.memory();
    conn = db.connect();
    result = conn.query(unwind(ROWS));
    if (result.rows() != ROWS) {
      throw new IllegalStateException("wanted " + ROWS + " rows and got " + result.rows());
    }
  }

  @TearDown
  public void close() {
    result.close();
    conn.close();
    db.close();
  }

  /** A cell at a time, which is one boundary crossing a cell. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long cellAtATime() {
    long total = 0;
    for (long i = 0, n = result.rows(); i < n; i++) {
      total += result.row(i).getLong(0);
    }
    return total;
  }

  /** The same through the iterator, which is what a for loop compiles to. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long rowAtATime() {
    long total = 0;
    for (Row row : result) {
      total += row.getLong(0);
    }
    return total;
  }

  /** The same through the Stream, which is what most callers write. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long throughTheStream() {
    return result.stream().mapToLong(row -> row.getLong(0)).sum();
  }

  /** The same sum over one borrowed buffer, which is one crossing in total. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long columnAtATime() {
    LongBuffer b = result.longs(0);
    long total = 0;
    for (int i = 0, n = b.remaining(); i < n; i++) {
      total += b.get(i);
    }
    return total;
  }

  /** The same with the nulls skipped rather than counted, which is the real loop. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long columnAtATimeWithValidity() {
    LongBuffer b = result.longs(0);
    ByteBuffer valid = result.valid(0);
    long total = 0;
    for (int i = 0, n = b.remaining(); i < n; i++) {
      if (valid.get(i) != 0) {
        total += b.get(i);
      }
    }
    return total;
  }

  /** A chunk at a time, which is what does not need the whole column resident. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long chunkAtATime() {
    long total = 0;
    for (Chunk c : result.chunks().toList()) {
      LongBuffer b = c.longs(0);
      for (int i = 0, n = (int) c.rows(); i < n; i++) {
        total += b.get(i);
      }
    }
    return total;
  }

  /** Borrowing the buffer and reading nothing, so the loops above split in two. */
  @Benchmark
  public LongBuffer borrowOnly() {
    return result.longs(0);
  }

  /** A statement that answers with the numbers one to n, one a row. */
  private static String unwind(int n) {
    StringBuilder sb = new StringBuilder(n * 7 + 32).append("UNWIND [");
    for (int i = 1; i <= n; i++) {
      if (i > 1) {
        sb.append(", ");
      }
      sb.append(i);
    }
    return sb.append("] AS v RETURN v").toString();
  }
}
