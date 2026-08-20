package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Columns your program already holds, named as a table and queried where they
 * lie.
 *
 * <p>This is the other direction from {@link Loader}. A loader takes your
 * columns and writes a database out of them. A frame writes nothing at all:
 * you describe the memory you are already holding, register it on a
 * {@link Connection}, and statements read your buffers directly. Nothing is
 * copied at registration and nothing is copied at read, so a frame of ten
 * million rows is ready in the time it takes to walk its columns.
 *
 * <pre>{@code
 * LongBuffer ids = ByteBuffer.allocateDirect(3 * 8).order(ByteOrder.nativeOrder()).asLongBuffer();
 * ids.put(new long[] {1, 2, 3}).flip();
 * try (Frame frame = Frame.of("Person", 3)) {
 *   frame.column("id", ids);
 *   conn.register(frame);
 *   try (Result r = conn.query("MATCH (p:Person) RETURN sum(p.id) AS total")) {
 *     System.out.println(r.row(0).getLong(0));
 *   }
 * }
 * }</pre>
 *
 * <p>Every buffer has to be direct, and one that is not is refused rather than
 * copied. Everywhere else in this API a heap buffer costs a memcpy and nothing
 * else, because the call reads it and is finished. A frame keeps the pointer
 * for as long as it is registered, so a copy here would mean the engine
 * reading a copy of your data for the rest of the frame's life. That is a
 * frame that is not a frame, and it is better said out loud than done quietly.
 *
 * <p>The buffers stay yours and this library never writes one. What it asks is
 * that each stays where it is, unwritten and unfreed, until the engine is
 * finished with it, which is after the last statement reading the frame ends
 * and is neither the unregister that preceded it nor {@link #close()}.
 *
 * <p>For a direct buffer that is looked after for you. A frame keeps a
 * reference to everything handed to it and lets go only when the engine says
 * it is finished, so a buffer cannot be collected out from under a running
 * statement and you do not have to keep a field alive to prevent it. The
 * optional release callback is for the other kind of buffer: one over memory
 * you allocated yourself, or one a lock has to be taken to let go of. It runs
 * once, on a thread of the library's, at that same moment.
 *
 * <p>The order is fixed: make the frame, describe one column at a time, then
 * register it. A column whose count does not match the frame's row count is
 * refused at that column, where you still know which one you were describing.
 * Everything else that can go wrong is settled at
 * {@link Connection#register(Frame)}: alignment, an unsigned value too large
 * for the signed lane, a scale that would overflow, an offset that leaves its
 * buffer. A read of a registered frame cannot fail, which is what lets a scan
 * be a loop.
 *
 * <p>A frame is described once and registered as often as you like, on as many
 * connections as you like. It is read only and has no edges: a statement that
 * would insert into, set on or delete from a registered name is refused, a
 * name a stored table already holds is refused, and a name another frame holds
 * replaces that frame.
 *
 * <p>Which lane a column takes decides what it costs to read. Sixty-four
 * signed bits, doubles, one bit a row for a boolean, and characters end to end
 * with offsets cutting them up are what this engine keeps natively, and those
 * cost nothing. A narrower integer, an unsigned one, a single-precision float
 * and Arrow's microseconds against the nanoseconds this engine keeps time in
 * are widened a value at a time as a statement reaches them, so they cost
 * something, but only for the columns the statement actually named.
 */
public final class Frame implements AutoCloseable {

  /** What the C ABI calls a column of numbers and nothing else. */
  private static final int PLAIN = -1;

  private final ZuBinding zu;
  private final AtomicLong handle;
  private final String name;
  private final long rows;
  private final List<Buffer> lent;

  private Frame(ZuBinding zu, long handle, String name, long rows, List<Buffer> lent) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
    this.name = name;
    this.rows = rows;
    this.lent = lent;
  }

  /**
   * A frame of buffers a garbage collector already looks after.
   *
   * @param name what the table is called in a statement
   * @param rows how many rows every column of it carries
   * @return the frame, which the caller closes
   */
  public static Frame of(String name, long rows) {
    return of(name, rows, null);
  }

  /**
   * A frame whose buffers something has to be told about.
   *
   * @param name what the table is called in a statement
   * @param rows how many rows every column of it carries
   * @param release run once, on a thread of the library's, after the last
   *     statement reading this frame ends, which is where a host that has to
   *     take a lock to let go of what it lent takes it
   * @return the frame, which the caller closes
   */
  public static Frame of(String name, long rows, Runnable release) {
    ZuBinding zu = Zu.binding();
    // The list is what keeps the caller's buffers reachable, and the callback
    // is what holds the list, so a direct buffer nothing else refers to lives
    // exactly as long as the engine may still read it. That is why there is a
    // callback even when the caller asked for none.
    List<Buffer> lent = new ArrayList<>();
    Runnable body = new Keep(lent, release);
    return new Frame(zu, zu.frameNew(name, rows, body), name, rows, lent);
  }

  /** What the engine calls when it has finished, and what holds the buffers until then. */
  private static final class Keep implements Runnable {

    private final List<Buffer> lent;
    private final Runnable body;

    Keep(List<Buffer> lent, Runnable body) {
      this.lent = lent;
      this.body = body;
    }

    @Override
    public void run() {
      lent.clear();
      if (body != null) {
        body.run();
      }
    }
  }

  /**
   * A column of 64-bit signed integers, which is the lane and costs nothing.
   *
   * @param column the column
   * @param values one a row, between the buffer's position and its limit
   * @return this frame
   */
  public Frame column(String column, LongBuffer values) {
    return integers(column, values, values.remaining(), 64, true, 1, null);
  }

  /**
   * A column of 32-bit signed integers, widened as a statement reaches them.
   *
   * @param column the column
   * @param values one a row, between the buffer's position and its limit
   * @return this frame
   */
  public Frame column(String column, IntBuffer values) {
    return integers(column, values, values.remaining(), 32, true, 1, null);
  }

  /**
   * A column of 16-bit signed integers, widened as a statement reaches them.
   *
   * @param column the column
   * @param values one a row, between the buffer's position and its limit
   * @return this frame
   */
  public Frame column(String column, ShortBuffer values) {
    return integers(column, values, values.remaining(), 16, true, 1, null);
  }

  /**
   * A column of doubles, which is the lane and costs nothing.
   *
   * @param column the column
   * @param values one a row, between the buffer's position and its limit
   * @return this frame
   */
  public Frame column(String column, DoubleBuffer values) {
    zu.frameColumnFloats(open(), column, values, values.remaining(), 64);
    return keep(values);
  }

  /**
   * A column of single-precision floats, widened as a statement reaches them.
   *
   * @param column the column
   * @param values one a row, between the buffer's position and its limit
   * @return this frame
   */
  public Frame column(String column, FloatBuffer values) {
    zu.frameColumnFloats(open(), column, values, values.remaining(), 32);
    return keep(values);
  }

  /**
   * A column of integers in whatever width and sign the host holds them in.
   *
   * <p>This is the full form the six calls above are shorthand for, and it is
   * what an Arrow array of any integer type maps onto.
   *
   * @param column the column
   * @param values the buffer, which has to be direct
   * @param count how many values are in it, which has to be the frame's row
   *     count
   * @param bits 8, 16, 32 or 64
   * @param signed whether they are signed
   * @param scale what one value is multiplied by to reach the unit its
   *     meaning counts in, so 1 for an integer and a date, and 1000 for the
   *     microseconds Arrow keeps a time or a timestamp in
   * @param kind what the counts mean, or null for a column of numbers and
   *     nothing else
   * @return this frame
   */
  public Frame integers(
      String column,
      Buffer values,
      long count,
      int bits,
      boolean signed,
      long scale,
      Value.Temporal.Kind kind) {
    zu.frameColumnInts(
        open(), column, values, count, bits, signed, scale, kind == null ? PLAIN : kind.value());
    return keep(values);
  }

  /**
   * A column of booleans, one bit a row, low bit of the first byte first,
   * which is Arrow's bitmap and this engine's alike.
   *
   * <p>A host holding a slice with a bit offset of its own owes the shift
   * before it gets here: a bitmap that starts partway into a byte is not a
   * thing a pointer can say.
   *
   * @param column the column
   * @param bitmap the bits, which has to be direct
   * @param count how many rows are in it, which the bitmap cannot say
   * @return this frame
   */
  public Frame booleans(String column, ByteBuffer bitmap, long count) {
    zu.frameColumnBooleans(open(), column, bitmap, count);
    return keep(bitmap);
  }

  /**
   * A column of strings as Arrow's Utf8 keeps them.
   *
   * <p>The characters never move, either now or at read: what a scan builds
   * is a view pointing back into your data buffer.
   *
   * @param column the column
   * @param offsets where each string starts, of which there are one more than
   *     there are rows, the last being how much of data is used
   * @param data the characters, end to end
   * @return this frame
   */
  public Frame strings(String column, IntBuffer offsets, ByteBuffer data) {
    zu.frameColumnStrings(open(), column, offsets, false, data, offsets.remaining() - 1L);
    return keep(offsets).keep(data);
  }

  /**
   * A column of strings as Arrow's LargeUtf8 keeps them, which is the same
   * with 64-bit offsets.
   *
   * @param column the column
   * @param offsets where each string starts, of which there are one more than
   *     there are rows
   * @param data the characters, end to end
   * @return this frame
   */
  public Frame strings(String column, LongBuffer offsets, ByteBuffer data) {
    zu.frameColumnStrings(open(), column, offsets, true, data, offsets.remaining() - 1L);
    return keep(offsets).keep(data);
  }

  /**
   * A column of strings as Arrow's Utf8View keeps them, sixteen bytes a row
   * over one or more data buffers.
   *
   * <p>A short string in that layout is already this engine's own view, byte
   * for byte, so this is the cheapest string column there is.
   *
   * @param column the column
   * @param views sixteen bytes a row
   * @param data the buffers they point into
   * @return this frame
   */
  public Frame views(String column, ByteBuffer views, List<ByteBuffer> data) {
    List<Buffer> buffers = new ArrayList<>(data);
    zu.frameColumnViews(open(), column, views, buffers, views.remaining() / 16L);
    keep(views);
    lent.addAll(buffers);
    return this;
  }

  /**
   * A column of dates as Arrow's Date32 keeps them, days since the epoch.
   *
   * @param column the column
   * @param epochDays one a row
   * @return this frame
   */
  public Frame dates(String column, IntBuffer epochDays) {
    return integers(
        column, epochDays, epochDays.remaining(), 32, true, 1, Value.Temporal.Kind.DATE);
  }

  /**
   * A column of timestamps as Arrow keeps them at microsecond precision,
   * scaled to the nanoseconds this engine counts time in.
   *
   * @param column the column
   * @param epochMicros one a row
   * @return this frame
   */
  public Frame timestamps(String column, LongBuffer epochMicros) {
    return integers(
        column,
        epochMicros,
        epochMicros.remaining(),
        64,
        true,
        1000,
        Value.Temporal.Kind.LOCAL_DATETIME);
  }

  /**
   * What the table is called in a statement.
   *
   * @return the name
   */
  public String name() {
    return name;
  }

  /**
   * How many rows every column of it carries.
   *
   * @return the count
   */
  public long rows() {
    return rows;
  }

  /**
   * Whether this frame has been closed.
   *
   * @return true once {@link #close()} has run
   */
  public boolean isClosed() {
    return handle.get() == 0;
  }

  /**
   * Ends the frame.
   *
   * <p>This is not what tells you the engine has let go of your buffers. A
   * statement that started before this may still be reading them, and the
   * release callback is what runs when the last one has finished. Closing
   * twice does nothing the second time.
   */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.frameFree(h);
    }
  }

  long handle() {
    return open();
  }

  /**
   * Holds on to a buffer the engine now points at, so that a collector cannot
   * free it while a statement is reading it.
   */
  private Frame keep(Buffer buffer) {
    lent.add(buffer);
    return this;
  }

  private long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(Diagnostic.misuse(Status.MISUSE_CLOSED, "this frame is closed"));
    }
    return h;
  }
}
