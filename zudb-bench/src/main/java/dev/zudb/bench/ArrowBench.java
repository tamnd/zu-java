package dev.zudb.bench;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Row;
import dev.zudb.arrow.Arrow;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.ipc.ArrowReader;
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
 * What handing a hundred thousand rows to Arrow costs, against reading the
 * same rows here.
 *
 * <p>Every benchmark below runs the statement as well, because an export
 * spends its result and there is no way to export the same one twice. That
 * makes these four comparable to each other and not to {@link ReadBench},
 * which reads a result it was handed and never runs anything.
 *
 * <p>The table is on disk and the statement is a scan of stored values with
 * nothing above it, which is the plan whose columns the executor fills. That
 * is the case worth measuring: the arrays that cross into Arrow are those
 * buffers, so the export is a schema and a handful of pointers and does not
 * grow with the row count. Summing through the reader afterwards is the JVM
 * walking memory the engine wrote, which is what a real consumer does and
 * what makes {@code exportAndSum} more than a pointer swap.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow"})
public class ArrowBench {

  /**
   * How many rows the table holds. A constant rather than a parameter because
   * the per-row score is scaled by it, and JMH wants that scale as a literal
   * in an annotation.
   */
  private static final int ROWS = 100_000;

  private static final String SCAN = "MATCH (r:Row) RETURN r.id AS id";

  private Path dir;
  private Database db;
  private Connection conn;
  private BufferAllocator allocator;

  @Setup
  public void build() throws IOException {
    dir = Files.createTempDirectory("zu-arrow-bench");
    Path path = dir.resolve("rows.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Row", "Near", ROWS);
      long[] ids = new long[ROWS];
      for (int i = 0; i < ROWS; i++) {
        ids[i] = i;
      }
      loader.column("id", ids);
      loader.finish();
    }
    db = Database.open(path);
    conn = db.connect();
    allocator = new RootAllocator();
  }

  @TearDown
  public void close() throws IOException {
    allocator.close();
    conn.close();
    db.close();
    Temp.deleteTree(dir);
  }

  /** The statement and nothing else, so the three below split in two. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long queryOnly() {
    try (Result r = conn.query(SCAN)) {
      return r.rows();
    }
  }

  /** The statement, the export, and a sum over every batch the reader gives back. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long exportAndSum() throws IOException {
    long total = 0;
    try (ArrowReader reader = Arrow.query(allocator, conn, SCAN)) {
      while (reader.loadNextBatch()) {
        BigIntVector id = (BigIntVector) reader.getVectorSchemaRoot().getVector(0);
        for (int i = 0, n = id.getValueCount(); i < n; i++) {
          total += id.get(i);
        }
      }
    }
    return total;
  }

  /** The statement and the same sum over the borrowed column, which stays here. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long borrowAndSum() {
    long total = 0;
    try (Result r = conn.query(SCAN)) {
      LongBuffer b = r.longs(0);
      for (int i = 0, n = b.remaining(); i < n; i++) {
        total += b.get(i);
      }
    }
    return total;
  }

  /** The statement and the same sum a row at a time, which is what it costs to not do either. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long rowAtATime() {
    long total = 0;
    try (Result r = conn.query(SCAN)) {
      for (Row row : r) {
        total += row.getLong(0);
      }
    }
    return total;
  }
}
