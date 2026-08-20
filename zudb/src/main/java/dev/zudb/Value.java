package dev.zudb;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * One value out of a result, as the type it actually is.
 *
 * <p>Sealed, so a switch over it is exhaustive and the compiler is the thing
 * that tells you a case is missing when zu grows a type:
 *
 * <pre>{@code
 * String show(Value v) {
 *     return switch (v) {
 *         case Value.Null ignored -> "null";
 *         case Value.Int i        -> Long.toString(i.value());
 *         case Value.Str s        -> s.value();
 *         case Value.Node n       -> "node " + n.table() + ":" + n.offset();
 *         case Value.List l       -> l.items().toString();
 *         ...
 *     };
 * }
 * }</pre>
 *
 * <p>This is the path a value takes that a column cannot express. A temporal
 * is a count and a unit, a list recurses, a node is a table and a row of it,
 * and none of the three fits a {@code long[]}. For a column of integers or
 * floats the columnar readers on {@link Result} are the path, and they hand
 * back the engine's own memory rather than building any of these.
 *
 * <p>Three of the names here are also names in {@code java.lang} or
 * {@code java.util}: {@link Value.List}, {@link Value.Record} and
 * {@link Value.Path}. Write them qualified, as {@code Value.List}, which is
 * how they read in a switch anyway. Importing the nested one is what would
 * hurt.
 */
public sealed interface Value {

  /** No value. Not an empty string and not a zero. */
  record Null() implements Value {
    private static final Null INSTANCE = new Null();

    /**
     * The one of these there is, since a null has nothing to tell two of
     * them apart by.
     *
     * @return the instance
     */
    public static Null instance() {
      return INSTANCE;
    }
  }

  /**
   * A boolean.
   *
   * @param value what it is
   */
  record Bool(boolean value) implements Value {}

  /**
   * A 64-bit signed integer.
   *
   * @param value what it is
   */
  record Int(long value) implements Value {}

  /**
   * A double.
   *
   * @param value what it is
   */
  record Float(double value) implements Value {}

  /**
   * A string.
   *
   * @param value what it is
   */
  record Str(String value) implements Value {}

  /**
   * A node, which is a table and a row of it, because neither identifies a
   * node on its own: two tables number their rows from zero.
   *
   * @param table which table, as the number the engine keeps it under. The C
   *     ABI has no call that turns that number into a name, so this client
   *     hands over the number rather than a guess
   * @param offset which row of it
   */
  record Node(int table, long offset) implements Value {}

  /**
   * A relationship, as the table it is in and the two rows it runs between.
   *
   * @param table which table
   * @param source the row it starts at
   * @param target the row it ends at
   */
  record Rel(int table, long source, long target) implements Value {}

  /**
   * A list, which recurses.
   *
   * @param items what is in it, in order
   */
  record List(java.util.List<Value> items) implements Value {}

  /**
   * A path, as the nodes and relationships along it in order.
   *
   * @param items what is on it
   */
  record Path(java.util.List<Value> items) implements Value {}

  /**
   * A record, whose fields are in name order and whose names appear once,
   * which is what makes two records written in different orders one value.
   *
   * @param fields what is in it
   */
  record Record(java.util.List<Field> fields) implements Value {}

  /**
   * One field of a {@link Value.Record}.
   *
   * @param name what it is called
   * @param value what it holds
   */
  record Field(String name, Value value) {}

  /**
   * A date, a time, a datetime or a duration, as one count and the unit that
   * count is in.
   *
   * <p>One shape rather than seven, because a program that reads temporals
   * reads all of them and a switch over seven kinds is what it wants. The
   * {@code to} methods below turn one into the {@code java.time} type it is,
   * and each refuses a kind that is not its own rather than reinterpreting
   * the count.
   *
   * @param kind which of the seven it is
   * @param count days for a date, months for a year-month duration,
   *     nanoseconds for the other five
   * @param offsetMinutes minutes east of UTC, and 0 for the five kinds that
   *     carry none
   */
  record Temporal(Kind kind, long count, int offsetMinutes) implements Value {

    /** Which temporal a temporal is. The unit follows the kind. */
    public enum Kind {
      /** Days since 1970-01-01. */
      DATE(0),
      /** Nanoseconds since midnight. */
      LOCAL_TIME(1),
      /** Nanoseconds since midnight, with an offset. */
      ZONED_TIME(2),
      /** Nanoseconds since 1970-01-01T00:00. */
      LOCAL_DATETIME(3),
      /** Nanoseconds since the epoch, with an offset. */
      ZONED_DATETIME(4),
      /** Months. */
      DURATION_YEAR_MONTH(5),
      /** Nanoseconds. */
      DURATION_DAY_TIME(6);

      private final int value;

      Kind(int value) {
        this.value = value;
      }

      /**
       * The number this kind is in the C ABI.
       *
       * @return the {@code ZU_TEMPORAL_} value
       */
      public int value() {
        return value;
      }

      /**
       * The kind a {@code ZU_TEMPORAL_} value names.
       *
       * @param value what the C ABI returned
       * @return the kind
       * @throws ZuProgrammingException if it is not one of the seven
       */
      public static Kind of(int value) {
        for (Kind k : values()) {
          if (k.value == value) {
            return k;
          }
        }
        throw new ZuProgrammingException(
            Diagnostic.misuse(Status.MISUSE, "no temporal kind is " + value));
      }
    }

    /**
     * This as a date.
     *
     * @return the date
     * @throws ZuProgrammingException unless the kind is {@link Kind#DATE}
     */
    public LocalDate toLocalDate() {
      expect(Kind.DATE);
      return LocalDate.ofEpochDay(count);
    }

    /**
     * This as a time of day.
     *
     * @return the time
     * @throws ZuProgrammingException unless the kind is {@link Kind#LOCAL_TIME}
     */
    public LocalTime toLocalTime() {
      expect(Kind.LOCAL_TIME);
      return LocalTime.ofNanoOfDay(count);
    }

    /**
     * This as a time of day with an offset.
     *
     * @return the time
     * @throws ZuProgrammingException unless the kind is {@link Kind#ZONED_TIME}
     */
    public OffsetTime toOffsetTime() {
      expect(Kind.ZONED_TIME);
      return OffsetTime.of(LocalTime.ofNanoOfDay(count), offset());
    }

    /**
     * This as a datetime.
     *
     * @return the datetime
     * @throws ZuProgrammingException unless the kind is {@link Kind#LOCAL_DATETIME}
     */
    public LocalDateTime toLocalDateTime() {
      expect(Kind.LOCAL_DATETIME);
      return LocalDateTime.ofEpochSecond(
          Math.floorDiv(count, 1_000_000_000L),
          (int) Math.floorMod(count, 1_000_000_000L),
          ZoneOffset.UTC);
    }

    /**
     * This as a datetime with an offset.
     *
     * @return the datetime
     * @throws ZuProgrammingException unless the kind is {@link Kind#ZONED_DATETIME}
     */
    public OffsetDateTime toOffsetDateTime() {
      expect(Kind.ZONED_DATETIME);
      return OffsetDateTime.of(
          LocalDateTime.ofEpochSecond(
              Math.floorDiv(count, 1_000_000_000L),
              (int) Math.floorMod(count, 1_000_000_000L),
              ZoneOffset.UTC),
          ZoneOffset.UTC)
          .withOffsetSameInstant(offset());
    }

    /**
     * This as a span of months.
     *
     * <p>A {@link Period} and not a {@link Duration}, because months are the
     * unit whose length depends on when you start counting, which is the
     * whole reason the standard keeps the two durations apart.
     *
     * @return the period, normalised into years and months
     * @throws ZuProgrammingException unless the kind is {@link Kind#DURATION_YEAR_MONTH}
     */
    public Period toPeriod() {
      expect(Kind.DURATION_YEAR_MONTH);
      return Period.ofMonths(Math.toIntExact(count)).normalized();
    }

    /**
     * This as a span of time.
     *
     * @return the duration
     * @throws ZuProgrammingException unless the kind is {@link Kind#DURATION_DAY_TIME}
     */
    public Duration toDuration() {
      expect(Kind.DURATION_DAY_TIME);
      return Duration.ofNanos(count);
    }

    /**
     * The offset as {@code java.time} spells it.
     *
     * @return the offset, which is {@link ZoneOffset#UTC} for the five kinds
     *     that carry none
     */
    public ZoneOffset offset() {
      return ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
    }

    private void expect(Kind wanted) {
      if (kind != wanted) {
        throw new ZuProgrammingException(
            Diagnostic.misuse(Status.MISUSE, "this temporal is a " + kind + " and not a " + wanted));
      }
    }
  }

  /**
   * A graph, one of the two reference values the standard names.
   *
   * <p>It has no contents to hand over: a handle is a handle, and the tag is
   * the whole of what a binding can say about the cell.
   */
  record Graph() implements Value {}

  /**
   * A binding table, the other reference value, and empty for the same
   * reason.
   */
  record BindingTable() implements Value {}
}
