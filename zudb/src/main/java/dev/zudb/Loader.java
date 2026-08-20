package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds a database out of columns, which is the fastest way values get in and
 * the only way a table comes into being.
 *
 * <p>A loader writes a new file. It refuses a path that already exists,
 * because a bulk load builds a database rather than adding to one: what adds
 * to one is {@link Appender}. The order is fixed and short. Say what the table
 * is called and how many rows it has, hand over one column at a time and the
 * edges if there are any, then {@link #finish()}.
 *
 * <pre>{@code
 * try (Loader loader = Loader.create(Path.of("people.zu"))) {
 *   loader.table("Person", "Knows", 3);
 *   loader.column("id", 1L, 2L, 3L);
 *   loader.column("name", "ada", "grace", "alan");
 *   loader.finish();
 * }
 * }</pre>
 *
 * <p>Every column is passed as an array or as a {@link java.nio.Buffer}, and
 * which one you pass is the difference between a copy and no copy. A direct
 * buffer is read where it lies: the engine sees the memory your program
 * already filled and nothing crosses the boundary but a pointer. An array, or
 * a heap buffer, is memory nothing outside the JVM can address, so it is
 * copied off-heap first. For a few thousand rows that costs nothing worth
 * measuring. For a hundred million it is the whole cost, and a program at that
 * size should fill a {@link java.nio.ByteBuffer#allocateDirect} and view it.
 *
 * <p>The count each column carries has to match the row count the table was
 * declared with. A column with a value missing is an error rather than a
 * shorter table, which is the mistake this refuses to make quietly.
 *
 * <p>A loader that is closed without finishing wrote nothing, and leaves the
 * empty file it created for the caller to remove.
 */
public final class Loader implements AutoCloseable {

  private final ZuBinding zu;
  private final AtomicLong handle;
  private boolean finished;

  private Loader(ZuBinding zu, long handle) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
  }

  /**
   * Starts a load into a database that does not exist yet.
   *
   * @param path where to write it, which must not exist
   * @return the loader, which the caller closes
   * @throws ZuException if the path exists or cannot be written
   */
  public static Loader create(Path path) {
    ZuBinding zu = Zu.binding();
    return new Loader(zu, zu.loaderCreate(path.toString()));
  }

  /**
   * Names the one table this loader builds and says how many rows it has.
   *
   * <p>Both names are wanted, even for a load that adds no edges at all. A
   * node table comes with the edge table between its rows whether or not
   * anything is in it, and naming it here rather than guessing a name later
   * is the difference between a schema you chose and one that happened.
   *
   * @param nodes the node table
   * @param edges the edge table between its rows
   * @param rows how many rows every column of the node table will carry,
   *     which is given rather than counted so that a column with a value
   *     missing is an error and not a shorter table
   * @return this loader
   */
  public Loader table(String nodes, String edges, long rows) {
    zu.loaderTable(open(), nodes, edges, rows);
    return this;
  }

  /**
   * Adds edges as the row each one starts at and the row it ends at.
   *
   * <p>This appends, so call it as often as you like. The loader sorts and
   * deduplicates at {@link #finish()}, so neither order nor a repeat matters.
   *
   * @param from the row each edge starts at
   * @param to the row each edge ends at
   * @return this loader
   */
  public Loader edges(int[] from, int[] to) {
    return edges(IntBuffer.wrap(from), IntBuffer.wrap(to));
  }

  /**
   * Adds edges from two buffers, which are read where they lie when they are
   * direct.
   *
   * @param from the row each edge starts at
   * @param to the row each edge ends at
   * @return this loader
   */
  public Loader edges(IntBuffer from, IntBuffer to) {
    zu.loaderEdges(open(), from, to);
    return this;
  }

  /**
   * A column of integers.
   *
   * @param name the column
   * @param values one a row
   * @return this loader
   */
  public Loader column(String name, long... values) {
    return column(name, LongBuffer.wrap(values));
  }

  /**
   * A column of integers, read where they lie when the buffer is direct.
   *
   * @param name the column
   * @param values one a row, between the buffer's position and its limit
   * @return this loader
   */
  public Loader column(String name, LongBuffer values) {
    zu.loaderColumnLongs(open(), name, values);
    return this;
  }

  /**
   * A column of doubles.
   *
   * @param name the column
   * @param values one a row
   * @return this loader
   */
  public Loader column(String name, double... values) {
    return column(name, DoubleBuffer.wrap(values));
  }

  /**
   * A column of doubles, read where they lie when the buffer is direct.
   *
   * @param name the column
   * @param values one a row, between the buffer's position and its limit
   * @return this loader
   */
  public Loader column(String name, DoubleBuffer values) {
    zu.loaderColumnDoubles(open(), name, values);
    return this;
  }

  /**
   * A column of booleans.
   *
   * @param name the column
   * @param values one a row
   * @return this loader
   */
  public Loader column(String name, boolean... values) {
    int[] ints = new int[values.length];
    for (int i = 0; i < values.length; i++) {
      ints[i] = values[i] ? 1 : 0;
    }
    return booleanColumn(name, IntBuffer.wrap(ints));
  }

  /**
   * A column of booleans as the ints the C ABI carries them as, where anything
   * that is not nought is true.
   *
   * <p>Separately named because a buffer of ints could as easily be meant as a
   * column of integers, and guessing which is not something a bulk load should
   * do.
   *
   * @param name the column
   * @param values one a row, between the buffer's position and its limit
   * @return this loader
   */
  public Loader booleanColumn(String name, IntBuffer values) {
    zu.loaderColumnBooleans(open(), name, values);
    return this;
  }

  /**
   * A column of strings.
   *
   * @param name the column
   * @param values one a row, none of them null
   * @return this loader
   */
  public Loader column(String name, String... values) {
    return column(name, Arrays.asList(values));
  }

  /**
   * A column of strings.
   *
   * <p>There is no zero-copy shape for this one. Every string is encoded and
   * checked for UTF-8 on the way in, which is the price of never reading back
   * a value no query could have returned.
   *
   * @param name the column
   * @param values one a row, none of them null
   * @return this loader
   */
  public Loader column(String name, List<String> values) {
    zu.loaderColumnStrings(open(), name, values);
    return this;
  }

  /**
   * A column of temporals, all of one kind, each row the count in the unit
   * that kind implies.
   *
   * <p>This is {@link Row#getTemporal(int)} read backwards: a value that came
   * out as 19782 days goes back in as 19782 days.
   * {@link Value.Temporal.Kind#ZONED_TIME} and
   * {@link Value.Temporal.Kind#ZONED_DATETIME} are refused, because a stored
   * column has nowhere to keep the offset that makes those two what they are.
   *
   * @param name the column
   * @param kind which of the seven every row of it is
   * @param counts one a row
   * @return this loader
   */
  public Loader temporalColumn(String name, Value.Temporal.Kind kind, long... counts) {
    return temporalColumn(name, kind, LongBuffer.wrap(counts));
  }

  /**
   * A column of temporals, read where they lie when the buffer is direct.
   *
   * @param name the column
   * @param kind which of the seven every row of it is
   * @param counts one a row, between the buffer's position and its limit
   * @return this loader
   */
  public Loader temporalColumn(String name, Value.Temporal.Kind kind, LongBuffer counts) {
    zu.loaderColumnTemporal(open(), name, kind.value(), counts);
    return this;
  }

  /**
   * Writes it all.
   *
   * <p>The database is on disk when this returns, and {@link Database#open}
   * on the same path reads it. The loader is spent afterwards and the only
   * thing left to do with it is close it.
   */
  public void finish() {
    zu.loaderFinish(open());
    finished = true;
  }

  /**
   * Whether {@link #finish()} has run.
   *
   * @return true once the database is on disk
   */
  public boolean isFinished() {
    return finished;
  }

  /**
   * Frees the loader, which writes nothing that {@link #finish()} did not
   * already write.
   *
   * <p>So a try-with-resources whose body threw leaves the empty file the
   * loader created and no half-built database, which is the outcome that
   * cannot be misread. Closing twice does nothing the second time.
   */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.loaderFree(h);
    }
  }

  private long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(
          Diagnostic.misuse(Status.MISUSE_CLOSED, "this loader is closed"));
    }
    return h;
  }
}
