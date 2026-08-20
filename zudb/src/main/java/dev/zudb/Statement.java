package dev.zudb;

import dev.zudb.spi.ZuBinding;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A statement parsed and planned once and run as often as you like.
 *
 * <p>Bindings live on the statement and survive {@link #execute()}, so a loop
 * rebinds only what changed. Binding a name again replaces its value.
 *
 * <pre>{@code
 * try (Statement stmt = conn.prepare("MATCH (p:Person) WHERE p.age > $age RETURN p.name AS name")) {
 *     for (int age : new int[] {20, 30, 40}) {
 *         try (Result r = stmt.bind("age", age).execute()) {
 *             ...
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>A statement belongs to the connection it was prepared on. Using one
 * after that connection closes raises {@link ZuClosedException} rather than
 * following a dangling pointer, and the statement is still safe to close.
 */
public final class Statement implements AutoCloseable {

  private static final long NANOS = 1_000_000_000L;

  private final ZuBinding zu;
  private final AtomicLong handle;

  Statement(ZuBinding zu, long handle) {
    this.zu = zu;
    this.handle = new AtomicLong(handle);
  }

  /**
   * Binds an integer.
   *
   * @param name the parameter, written without its marker
   * @param value what to bind
   * @return this statement, so binds chain
   */
  public Statement bind(String name, long value) {
    zu.bindLong(open(), name, value);
    return this;
  }

  /**
   * Binds a float.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, double value) {
    zu.bindDouble(open(), name, value);
    return this;
  }

  /**
   * Binds a boolean.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, boolean value) {
    zu.bindBoolean(open(), name, value);
    return this;
  }

  /**
   * Binds a string.
   *
   * @param name the parameter
   * @param value what to bind, which may not be null: {@link #bindNull} is
   *     how to say that, so that a variable that turned out to be null is a
   *     failure at the bind rather than a query that quietly matched nothing
   * @return this statement
   */
  public Statement bind(String name, String value) {
    if (value == null) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE, "bind(" + name + ", null): call bindNull to bind no value"));
    }
    zu.bindString(open(), name, value);
    return this;
  }

  /**
   * Binds a date.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, LocalDate value) {
    return bind(name, Value.Temporal.Kind.DATE, value.toEpochDay(), 0);
  }

  /**
   * Binds a time of day.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, LocalTime value) {
    return bind(name, Value.Temporal.Kind.LOCAL_TIME, value.toNanoOfDay(), 0);
  }

  /**
   * Binds a time of day with an offset.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, OffsetTime value) {
    return bind(
        name,
        Value.Temporal.Kind.ZONED_TIME,
        value.toLocalTime().toNanoOfDay(),
        value.getOffset().getTotalSeconds() / 60);
  }

  /**
   * Binds a datetime.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, LocalDateTime value) {
    return bind(name, Value.Temporal.Kind.LOCAL_DATETIME, nanos(value.toEpochSecond(ZoneOffset.UTC), value.getNano()), 0);
  }

  /**
   * Binds a datetime with an offset.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, OffsetDateTime value) {
    return bind(
        name,
        Value.Temporal.Kind.ZONED_DATETIME,
        nanos(value.toEpochSecond(), value.getNano()),
        value.getOffset().getTotalSeconds() / 60);
  }

  /**
   * Binds a span of months.
   *
   * @param name the parameter
   * @param value what to bind, whose days are refused rather than turned into
   *     a length of time they do not have: a period of one month and one day
   *     is two spans and the standard keeps them apart
   * @return this statement
   */
  public Statement bind(String name, Period value) {
    if (value.getDays() != 0) {
      throw new ZuProgrammingException(
          Diagnostic.misuse(
              Status.MISUSE,
              "bind("
                  + name
                  + ", "
                  + value
                  + "): a year-month duration holds months, and days are a duration of their own"));
    }
    return bind(name, Value.Temporal.Kind.DURATION_YEAR_MONTH, value.toTotalMonths(), 0);
  }

  /**
   * Binds a span of time.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, Duration value) {
    return bind(name, Value.Temporal.Kind.DURATION_DAY_TIME, value.toNanos(), 0);
  }

  /**
   * Binds a temporal read out of another result, unchanged.
   *
   * @param name the parameter
   * @param value what to bind
   * @return this statement
   */
  public Statement bind(String name, Value.Temporal value) {
    return bind(name, value.kind(), value.count(), value.offsetMinutes());
  }

  /**
   * Binds a temporal as a kind and the count in the unit that kind implies,
   * for the caller who has both and no {@code java.time} value to make out of
   * them.
   *
   * @param name the parameter
   * @param kind which of the seven
   * @param count days for a date, months for a year-month duration,
   *     nanoseconds for the other five
   * @param offsetMinutes minutes east of UTC, ignored by every kind but the
   *     two zoned ones
   * @return this statement
   */
  public Statement bind(String name, Value.Temporal.Kind kind, long count, int offsetMinutes) {
    zu.bindTemporal(open(), name, kind.value(), count, offsetMinutes);
    return this;
  }

  /**
   * Binds no value.
   *
   * @param name the parameter
   * @return this statement
   */
  public Statement bindNull(String name) {
    zu.bindNull(open(), name);
    return this;
  }

  /**
   * Runs the statement with what is bound to it.
   *
   * @return the result, which the caller closes
   */
  public Result execute() {
    return new Result(zu, zu.execute(open()));
  }

  /**
   * Whether this statement has been closed.
   *
   * @return true once {@link #close()} has run
   */
  public boolean isClosed() {
    return handle.get() == 0;
  }

  /** Releases the statement. Closing twice does nothing the second time. */
  @Override
  public void close() {
    long h = handle.getAndSet(0);
    if (h != 0) {
      zu.stmtClose(h);
    }
  }

  private static long nanos(long seconds, int nano) {
    return Math.addExact(Math.multiplyExact(seconds, NANOS), nano);
  }

  private long open() {
    long h = handle.get();
    if (h == 0) {
      throw new ZuClosedException(
          Diagnostic.misuse(Status.MISUSE_CLOSED, "this statement is closed"));
    }
    return h;
  }
}
