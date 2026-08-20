package dev.zudb.bench;

import dev.zudb.Loader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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
 * What building a database out of columns costs, per row of a hundred
 * thousand.
 *
 * <p>The loader itself is made in an invocation fixture rather than in the
 * measured region, because a whole load writes a file and the file would
 * drown out everything else. What is measured is handing a column over, which
 * is where the difference between an array and a direct buffer lives, and
 * {@code wholeLoad} is there so the rest can be read against what a load
 * really costs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class LoadBench {

  /**
   * How many rows one load carries. A constant rather than a parameter
   * because the per-row score is scaled by it, and JMH wants that scale as a
   * literal in an annotation.
   */
  private static final int ROWS = 100_000;

  private Path dir;
  private long counter;

  private long[] ids;
  private LongBuffer direct;
  private List<String> names;

  private Loader loader;
  private Path path;

  @Setup
  public void fill() throws IOException {
    dir = Files.createTempDirectory("zu-load-bench");
    ids = new long[ROWS];
    for (int i = 0; i < ROWS; i++) {
      ids[i] = i;
    }
    direct =
        ByteBuffer.allocateDirect(ROWS * Long.BYTES).order(ByteOrder.nativeOrder()).asLongBuffer();
    direct.put(ids);
    direct.flip();
    names = new ArrayList<>(ROWS);
    for (int i = 0; i < ROWS; i++) {
      names.add("n" + i);
    }
  }

  @TearDown
  public void clean() throws IOException {
    Temp.deleteTree(dir);
  }

  /** A loader with its table named and no column in it yet. */
  @Setup(Level.Invocation)
  public void open() {
    path = dir.resolve("load-" + counter++ + ".zu");
    loader = Loader.create(path);
    loader.table("Person", "Knows", ROWS);
  }

  @TearDown(Level.Invocation)
  public void close() throws IOException {
    if (loader != null) {
      loader.close();
      loader = null;
    }
    if (path != null) {
      Files.deleteIfExists(path);
      path = null;
    }
  }

  /** One integer column as a Java array, which has to be copied off-heap. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public void columnFromArray() {
    loader.column("id", ids);
  }

  /** The same column as a direct buffer, which is read where it lies. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public void columnFromDirectBuffer() {
    loader.column("id", direct.duplicate());
  }

  /** A string column, which has no zero-copy shape and is checked for UTF-8 besides. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public void columnOfStrings() {
    loader.column("name", names);
  }

  /** Two columns and the write, which is what a load costs a caller. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public void wholeLoad() {
    loader.column("id", ids);
    loader.column("name", names);
    loader.finish();
  }
}
