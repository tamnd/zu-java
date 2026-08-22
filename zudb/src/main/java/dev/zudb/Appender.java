package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adds rows to a table that already exists, a value at a time, without a
 * statement anywhere near it.
 *
 * <p>A statement is the wrong shape for bulk writes. Every row would be
 * parsed, bound, planned and run, and none of that work says anything the row
 * before it did not already say. An appender skips all of it: values go
 * straight into the column they belong to, buffered until there are enough to
 * write, and the cost of a row is the cost of the values in it.
 *
 * <pre>{@code
 * try (Appender rows = conn.appender("Person")) {
 *   rows.append(4L).append("hedy").endRow();
 *   rows.append(5L).append("katherine").endRow();
 * }
 * }</pre>
 *
 * <p>Values are written in the order the table declares its columns, which
 * {@link #columnName(int)} will tell you, and a row is a row once
 * {@link #endRow()} has ended it. A value the column will not take ends its
 * row there and rolls back the values already written into it, so a refused
 * append never leaves half a row behind.
 *
 * <p>There is no way to append no value. The C ABI has no null to append and
 * this offers none rather than inventing one.
 *
 * <p>Rows are written in batches, so a row that has been ended is not yet a
 * row anybody else can see. {@link #flush()} writes what is buffered,
 * {@link #finish()} writes the rest and says how many rows went in altogether,
 * and {@link #close()} on an appender that was never finished writes what it
 * has anyway. That last one is deliberate: a loop that threw halfway keeps the
 * rows it managed, because throwing away work that succeeded is not a decision
 * a close should make on its own. Call {@link #discard()} to actually throw
 * them away.
 */
public final class Appender implements AutoCloseable {

  private static final long NANOS = 1_000_000_000L;

  private final ZuBinding zu;
  private final AtomicLong handle;
  private final Connection conn;
  private long finished = -1;

  Appender(ZuBinding zu, long handle, Connection conn) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
    this.conn = conn;
  }

  /**
   * Appends a boolean.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(boolean value) {
    zu.appendBoolean(open(), value);
    return this;
  }

  /**
   * Appends an integer.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(long value) {
    zu.appendLong(open(), value);
    return this;
  }

  /**
   * Appends a double.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(double value) {
    zu.appendDouble(open(), value);
    return this;
  }

  /**
   * Appends a string.
   *
   * @param value what to write, which is not allowed to be null because there
   *     is nothing this could write for it
   * @return this appender
   */
  public Appender append(String value) {
    if (value == null) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(Status.MISUSE, "append(null): there is no null to append"));
    }
    zu.appendString(open(), value);
    return this;
  }

  /**
   * Appends bytes.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(byte[] value) {
    return append(ByteBuffer.wrap(value));
  }

  /**
   * Appends bytes, read where they lie when the buffer is direct.
   *
   * @param value what to write, between the buffer's position and its limit
   * @return this appender
   */
  public Appender append(ByteBuffer value) {
    zu.appendBytes(open(), value);
    return this;
  }

  /**
   * Appends a date.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(LocalDate value) {
    return append(Value.Temporal.Kind.DATE, value.toEpochDay());
  }

  /**
   * Appends a time of day.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(LocalTime value) {
    return append(Value.Temporal.Kind.LOCAL_TIME, value.toNanoOfDay());
  }

  /**
   * Appends a datetime.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(LocalDateTime value) {
    return append(
        Value.Temporal.Kind.LOCAL_DATETIME,
        nanos(value.toEpochSecond(ZoneOffset.UTC), value.getNano()));
  }

  /**
   * Appends a span of months.
   *
   * @param value what to write, whose days are refused rather than turned into
   *     a length of time they do not have
   * @return this appender
   */
  public Appender append(Period value) {
    if (value.getDays() != 0) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE,
              "append("
                  + value
                  + "): a year-month duration holds months, and days are a duration of their own"));
    }
    return append(Value.Temporal.Kind.DURATION_YEAR_MONTH, value.toTotalMonths());
  }

  /**
   * Appends a span of time.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(Duration value) {
    return append(Value.Temporal.Kind.DURATION_DAY_TIME, value.toNanos());
  }

  /**
   * Appends a temporal read out of a result, unchanged.
   *
   * @param value what to write
   * @return this appender
   */
  public Appender append(Value.Temporal value) {
    return append(value.kind(), value.count());
  }

  /**
   * Appends a temporal as a kind and the count in the unit that kind implies.
   *
   * <p>{@link Value.Temporal.Kind#ZONED_TIME} and
   * {@link Value.Temporal.Kind#ZONED_DATETIME} are refused, because a stored
   * column has nowhere to keep the offset that makes those two what they are.
   *
   * @param kind which of the seven
   * @param count days for a date, months for a year-month duration,
   *     nanoseconds for the other five
   * @return this appender
   */
  public Appender append(Value.Temporal.Kind kind, long count) {
    zu.appendTemporal(open(), kind.value(), count);
    return this;
  }

  /**
   * Ends the row being written, which is what makes it a row.
   *
   * @return this appender
   */
  public Appender endRow() {
    zu.appendEndRow(open());
    return this;
  }

  /**
   * One whole row, for the caller who has it as objects already.
   *
   * <p>Each value is dispatched on the class it turns out to be, which costs a
   * type test a value and is the shape a program reading from somewhere
   * dynamic wants. A loop that knows what it is writing calls the
   * {@code append} overloads and pays nothing.
   *
   * @param values one a column, in the order the table declares them
   * @return this appender
   * @throws ZuProgrammingException if one of them is a class no column holds
   */
  public Appender row(Object... values) {
    for (Object value : values) {
      if (value instanceof Long v) {
        append(v.longValue());
      } else if (value instanceof Integer v) {
        append(v.longValue());
      } else if (value instanceof Short v) {
        append(v.longValue());
      } else if (value instanceof Byte v) {
        append(v.longValue());
      } else if (value instanceof Double v) {
        append(v.doubleValue());
      } else if (value instanceof Float v) {
        append(v.doubleValue());
      } else if (value instanceof Boolean v) {
        append(v.booleanValue());
      } else if (value instanceof String v) {
        append(v);
      } else if (value instanceof byte[] v) {
        append(v);
      } else if (value instanceof ByteBuffer v) {
        append(v);
      } else if (value instanceof LocalDate v) {
        append(v);
      } else if (value instanceof LocalTime v) {
        append(v);
      } else if (value instanceof LocalDateTime v) {
        append(v);
      } else if (value instanceof Period v) {
        append(v);
      } else if (value instanceof Duration v) {
        append(v);
      } else if (value instanceof Value.Temporal v) {
        append(v);
      } else if (value == null) {
        throw new ZuProgrammingException(
            Diagnostic.misuse(Status.MISUSE, "row(..., null, ...): there is no null to append"));
      } else {
        throw new ZuProgrammingException(
            Diagnostic.misuse(
                Status.MISUSE,
                "row(..., " + value.getClass().getName() + ", ...): no column holds one of those"));
      }
    }
    return endRow();
  }

  /**
   * Writes the rows that have been ended and not yet written.
   *
   * @return this appender
   */
  public Appender flush() {
    zu.appenderFlush(open());
    return this;
  }

  /**
   * Rows that have been ended and not yet written.
   *
   * @return the count, which a flush takes back to nought
   */
  public long buffered() {
    return zu.appenderBuffered(open());
  }

  /**
   * Rows written across every flush so far.
   *
   * @return the count
   */
  public long committed() {
    return zu.appenderCommitted(open());
  }

  /**
   * How many columns a row has.
   *
   * @return the count
   */
  public int columns() {
    return zu.appenderColumns(open());
  }

  /**
   * What a column is called, so a program can check it is writing what it
   * thinks it is.
   *
   * @param column the index, from nought
   * @return the name
   * @throws ZuProgrammingException if there is no such column
   */
  public String columnName(int column) {
    String name = zu.appenderColumnName(open(), column);
    if (name == null) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE,
              "there is no column " + column + " here, only " + columns() + " of them"));
    }
    return name;
  }

  /**
   * Throws away the rows that have been ended and not yet written.
   *
   * <p>Rows an earlier flush wrote are written and this does not reach them.
   *
   * @return how many rows were thrown away
   */
  public long discard() {
    return zu.appenderDiscard(open());
  }

  /**
   * Writes what is left and spends the appender.
   *
   * <p>The only thing left to do with it afterwards is close it, and closing
   * an appender that was finished frees it and writes nothing more.
   *
   * @return how many rows this appender wrote in all
   */
  public long finish() {
    finished = zu.appenderClose(open());
    return finished;
  }

  /**
   * Whether {@link #finish()} has run.
   *
   * @return true once the appender has been spent
   */
  public boolean isFinished() {
    return finished >= 0;
  }

  /**
   * Frees the appender, writing what is still buffered if
   * {@link #finish()} never ran.
   *
   * <p>What it cannot do is tell you whether that last write worked, because
   * a close has nowhere to report to. A program that needs to know calls
   * {@link #finish()} and closes afterwards, which is what the count it hands
   * back is for. Closing twice does nothing the second time.
   */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.appenderFree(h);
    }
  }

  private static long nanos(long seconds, int nano) {
    return Math.addExact(Math.multiplyExact(seconds, NANOS), nano);
  }

  private long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(
          Diagnostic.misuse(Status.MISUSE_CLOSED, "this appender is closed"));
    }
    // The engine refuses this too, and refuses it without an error record
    // attached, so what a caller would otherwise be told is the name of a C
    // function. An appender outliving its connection is an ordinary mistake
    // in a program that closes things in the wrong order, and the sentence
    // that names it is worth more than the one that names us.
    if (conn != null && conn.isClosed()) {
      throw new ZuClosedException(
          Diagnostic.misuse(
              Status.MISUSE_CLOSED, "the connection this appender writes through is closed"));
    }
    return h;
  }
}
