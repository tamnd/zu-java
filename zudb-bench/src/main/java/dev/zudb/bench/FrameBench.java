package dev.zudb.bench;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Frame;
import dev.zudb.Loader;
import dev.zudb.Result;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * What it costs to query a million rows the program is already holding,
 * against what the same million rows cost once they are in a database.
 *
 * <p>Two things are being measured here and they answer different questions.
 * {@code register} is how long it takes to make a million rows queryable,
 * which is the number to read against a load: a load writes a file and a frame
 * writes nothing. The scans are what a statement costs afterwards, and the
 * point of those is that a frame is not a slower way of reading, it is the
 * same read against somebody else's memory.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class FrameBench {

  /** How many rows both sides carry. */
  private static final int ROWS = 1_000_000;

  private Path dir;

  private LongBuffer ids;
  private IntBuffer narrow;
  private IntBuffer offsets;
  private ByteBuffer characters;

  private Database memory;
  private Connection frames;
  private Connection spare;
  private Frame frame;

  private Database stored;
  private Connection disk;

  @Setup
  public void fill() throws IOException {
    dir = Files.createTempDirectory("zu-frame-bench");

    ids = direct(ROWS * Long.BYTES).asLongBuffer();
    narrow = direct(ROWS * Integer.BYTES).asIntBuffer();
    List<String> names = new ArrayList<>(ROWS);
    for (int i = 0; i < ROWS; i++) {
      ids.put(i);
      narrow.put(i);
      names.add("n" + i);
    }
    ids.flip();
    narrow.flip();

    byte[] all = String.join("", names).getBytes(StandardCharsets.UTF_8);
    characters = direct(all.length);
    characters.put(all).flip();
    offsets = direct((ROWS + 1) * Integer.BYTES).asIntBuffer();
    int at = 0;
    offsets.put(0);
    for (String name : names) {
      at += name.length();
      offsets.put(at);
    }
    offsets.flip();

    memory = Database.memory();
    frames = memory.connect();
    spare = memory.connect();
    frame = describe();
    frames.register(frame);

    Path path = dir.resolve("people.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", ROWS);
      loader.column("id", copy());
      loader.column("name", names);
      loader.finish();
    }
    stored = Database.open(path);
    disk = stored.connect();
  }

  @TearDown
  public void clean() throws IOException {
    disk.close();
    stored.close();
    frames.close();
    spare.close();
    frame.close();
    memory.close();
    Temp.deleteTree(dir);
  }

  /**
   * Describing a million rows and naming them as a table, which is the whole
   * of what a frame costs before a statement can read it.
   */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public void register() {
    try (Frame one = describe()) {
      spare.register(one);
      spare.unregister("Person");
    }
  }

  /** A scan of the lane, which is the column the engine reads as it stands. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long scanFrame() {
    return sum(frames, "MATCH (p:Person) RETURN sum(p.id)");
  }

  /** The same scan over the same numbers, once they are in a database. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long scanStored() {
    return sum(disk, "MATCH (p:Person) RETURN sum(p.id)");
  }

  /** A scan of a 32-bit column, which is widened a value at a time as it is read. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long scanNarrowFrame() {
    return sum(frames, "MATCH (p:Person) RETURN sum(p.small)");
  }

  /** A scan of the string column, whose characters never move either way. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long scanFrameStrings() {
    return sum(frames, "MATCH (p:Person) WHERE p.name = 'n999999' RETURN count(p)");
  }

  /** The same, out of the database. */
  @Benchmark
  @OperationsPerInvocation(ROWS)
  public long scanStoredStrings() {
    return sum(disk, "MATCH (p:Person) WHERE p.name = 'n999999' RETURN count(p)");
  }

  private Frame describe() {
    Frame one = Frame.of("Person", ROWS);
    one.column("id", ids.duplicate());
    one.column("small", narrow.duplicate());
    one.strings("name", offsets.duplicate(), characters.duplicate());
    return one;
  }

  private static long sum(Connection conn, String statement) {
    try (Result r = conn.query(statement)) {
      return r.row(0).getLong(0);
    }
  }

  private long[] copy() {
    long[] out = new long[ROWS];
    ids.duplicate().get(out);
    return out;
  }

  private static ByteBuffer direct(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }
}
