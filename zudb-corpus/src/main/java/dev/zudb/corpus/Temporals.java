package dev.zudb.corpus;

import dev.zudb.Value;
import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * The temporal half of the encoding, written out rather than handed to
 * {@code java.time.format}.
 *
 * <p>A corpus reader is a second opinion about the text, and a second
 * opinion that calls the same library the client calls is not one. A
 * formatter also takes a great deal this encoding does not: a pattern with
 * a fractional second matches text with none, a numeric zone matches
 * {@code Z} under some patterns and not others, and none of that is
 * visible at the call site. Writing the four spellings out says exactly
 * what is accepted.
 *
 * <p>The spellings are the ones the engine prints, which is the extended
 * ISO 8601 form and nothing else: {@code 2024-01-01} for a date, {@code
 * 12:34:56} with an optional fraction of one to nine digits for a time,
 * the two joined with a {@code T} for a datetime, and {@code Z} or {@code
 * +07:00} for an offset. A basic-form {@code 20240101} is refused, because
 * a case that writes one is a case the other runners would read
 * differently or not at all.
 *
 * <p>The JVM holds all of it exactly. A date is a count of days and
 * everything else is a count of nanoseconds, which is the resolution the
 * engine keeps, so unlike the Python runner this one has no notion of a
 * value written too finely to hold.
 */
final class Temporals {

  private Temporals() {}

  static final long NANOS_PER_SECOND = 1_000_000_000L;
  static final long NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;
  static final long NANOS_PER_HOUR = 60 * NANOS_PER_MINUTE;
  static final long NANOS_PER_DAY = 24 * NANOS_PER_HOUR;

  /** A date, as the count of days from 1970-01-01 the client holds one as. */
  static Value.Temporal parseDate(String text) {
    Long days = dateDays(text);
    return days == null ? null : new Value.Temporal(Value.Temporal.Kind.DATE, days, 0);
  }

  /** A time of day with no offset on the end. */
  static Value.Temporal parseLocalTime(String text) {
    Long nanos = clockNanos(text);
    return nanos == null ? null : new Value.Temporal(Value.Temporal.Kind.LOCAL_TIME, nanos, 0);
  }

  /**
   * A time of day with an offset, which it carries as written: the count of
   * nanoseconds is midnight in the offset's own day rather than midnight
   * UTC, which is what the client's zoned time holds and what makes
   * {@code 12:00:00+07:00} and {@code 05:00:00Z} two values rather than
   * one.
   */
  static Value.Temporal parseZonedTime(String text) {
    Offset split = splitOffset(text);
    if (split == null) {
      return null;
    }
    Long nanos = clockNanos(split.rest());
    return nanos == null
        ? null
        : new Value.Temporal(Value.Temporal.Kind.ZONED_TIME, nanos, split.minutes());
  }

  /**
   * A date and a time with no offset, as the count of nanoseconds from
   * 1970-01-01T00:00:00 read with no zone at all.
   */
  static Value.Temporal parseLocalDateTime(String text) {
    Long nanos = stampNanos(text);
    return nanos == null
        ? null
        : new Value.Temporal(Value.Temporal.Kind.LOCAL_DATETIME, nanos, 0);
  }

  /**
   * An instant and the offset it was written with.
   *
   * <p>The client holds the instant in UTC and the offset beside it, so the
   * wall clock that was written is moved back by the offset to get there.
   * Two texts an hour apart in zones an hour apart are the same instant and
   * hold the same count, which is the point of keeping it that way.
   */
  static Value.Temporal parseZonedDateTime(String text) {
    Offset split = splitOffset(text);
    if (split == null) {
      return null;
    }
    Long nanos = stampNanos(split.rest());
    return nanos == null
        ? null
        : new Value.Temporal(Value.Temporal.Kind.ZONED_DATETIME,
            nanos - split.minutes() * NANOS_PER_MINUTE, split.minutes());
  }

  /**
   * {@code YYYY-MM-DD} as a count of days from the epoch, or null.
   *
   * <p>The date is handed to the calendar rather than checked field by
   * field, because that is the calendar answering the question about
   * February rather than this file having an opinion about it.
   */
  private static Long dateDays(String text) {
    if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
      return null;
    }
    Long year = number(text.substring(0, 4));
    Long month = number(text.substring(5, 7));
    Long day = number(text.substring(8, 10));
    if (year == null || month == null || day == null) {
      return null;
    }
    try {
      return LocalDate.of((int) (long) year, (int) (long) month, (int) (long) day).toEpochDay();
    } catch (DateTimeException e) {
      // A date the calendar does not have, such as 2023-02-30.
      return null;
    }
  }

  /**
   * {@code HH:MM:SS}, with a fraction of one to nine digits when there is
   * one, as nanoseconds since midnight, or null.
   */
  private static Long clockNanos(String text) {
    int dot = text.indexOf('.');
    String head = dot < 0 ? text : text.substring(0, dot);
    String frac = dot < 0 ? null : text.substring(dot + 1);
    if (head.length() != 8 || head.charAt(2) != ':' || head.charAt(5) != ':') {
      return null;
    }
    Long hours = number(head.substring(0, 2));
    Long minutes = number(head.substring(3, 5));
    Long seconds = number(head.substring(6, 8));
    if (hours == null || minutes == null || seconds == null) {
      return null;
    }
    // No leap second, because the engine has no value for one: a time is
    // nanoseconds since midnight and 23:59:60 is a second the count does
    // not have.
    if (hours > 23 || minutes > 59 || seconds > 59) {
      return null;
    }
    long nanos = hours * NANOS_PER_HOUR + minutes * NANOS_PER_MINUTE + seconds * NANOS_PER_SECOND;
    if (frac == null) {
      return nanos;
    }
    // A point with nothing after it is not a fraction, and ten digits is
    // finer than the engine counts, so neither is read as the number it
    // resembles.
    if (frac.isEmpty() || frac.length() > 9) {
      return null;
    }
    Long part = number(frac);
    if (part == null) {
      return null;
    }
    long scaled = part;
    for (int i = frac.length(); i < 9; i++) {
      scaled *= 10;
    }
    return nanos + scaled;
  }

  /**
   * A date and a time joined with a {@code T}, as nanoseconds from
   * 1970-01-01T00:00:00, or null.
   */
  private static Long stampNanos(String text) {
    int at = text.indexOf('T');
    if (at < 0) {
      return null;
    }
    Long days = dateDays(text.substring(0, at));
    Long nanos = clockNanos(text.substring(at + 1));
    if (days == null || nanos == null) {
      return null;
    }
    return days * NANOS_PER_DAY + nanos;
  }

  /** What came before an offset, and the offset in minutes east of UTC. */
  record Offset(String rest, int minutes) {}

  /**
   * The offset taken off the end of a zoned value, or null when there is
   * none to take.
   *
   * <p>Zero is written {@code Z} rather than {@code +00:00}, which is what
   * the engine prints, and both are read here because a case may assert
   * either. Which one it was is not kept, since it is not part of the
   * value: the engine holds an offset in minutes and prints zero as
   * {@code Z} whichever way it went in.
   */
  static Offset splitOffset(String text) {
    if (text.endsWith("Z")) {
      return new Offset(text.substring(0, text.length() - 1), 0);
    }
    if (text.length() < 7) {
      return null;
    }
    char mark = text.charAt(text.length() - 6);
    if (mark != '+' && mark != '-') {
      return null;
    }
    String zone = text.substring(text.length() - 6);
    if (zone.charAt(3) != ':') {
      return null;
    }
    Long hours = number(zone.substring(1, 3));
    Long minutes = number(zone.substring(4, 6));
    if (hours == null || minutes == null || minutes > 59) {
      return null;
    }
    long total = hours * 60 + minutes;
    // The standard's own limit, which is wider than any zone in use and is
    // here so that a typo lands as a refusal rather than as a date a day
    // away from the one that was meant.
    if (total > 18 * 60) {
      return null;
    }
    if (mark == '-') {
      total = -total;
    }
    return new Offset(text.substring(0, text.length() - 6), (int) total);
  }

  /**
   * A run of ASCII digits as the number it spells, or null.
   *
   * <p>Not a parser from the library, which takes a sign and grouping,
   * neither of which belongs inside a temporal field.
   */
  static Long number(String text) {
    if (text.isEmpty()) {
      return null;
    }
    long out = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
      out = out * 10 + (c - '0');
    }
    return out;
  }

  /**
   * An ISO 8601 duration, in the two kinds the engine keeps apart.
   *
   * <p>A duration is months or it is nanoseconds and never both, because a
   * month is not a number of days: adding one to a date is a different
   * operation from adding thirty of them, and a type that held both would
   * have to say which happens first. The client has a kind for each, so
   * which one this is is part of what the case asserts.
   *
   * <p>The text says which. A duration whose fields are years and months is
   * the month kind and everything else is the nanosecond kind, and a
   * duration with a field of each is refused rather than guessed at. That
   * leaves one text the fields decide and the numbers cannot, which is a
   * duration of nothing: {@code P0M} is no months and {@code PT0S} is no
   * nanoseconds, and they are two values here where the Python runner has
   * to call them one.
   */
  static Value.Temporal parseDuration(String text) {
    String rest = text;
    boolean negative = rest.startsWith("-");
    if (negative || rest.startsWith("+")) {
      rest = rest.substring(1);
    }
    if (!rest.startsWith("P")) {
      return null;
    }
    String body = rest.substring(1);
    int at = body.indexOf('T');
    boolean dated = at >= 0;
    String day = dated ? body.substring(0, at) : body;
    String clock = dated ? body.substring(at + 1) : "";
    // A P with nothing under it is not a duration, and neither is a T with
    // nothing after it.
    if (day.isEmpty() && clock.isEmpty()) {
      return null;
    }
    if (dated && clock.isEmpty()) {
      return null;
    }

    long months = 0;
    long nanos = 0;
    boolean sawMonths = !dated;
    for (Piece field : pieces(day)) {
      if (!field.ok()) {
        return null;
      }
      switch (field.unit()) {
        case 'Y':
          months += field.whole() * 12;
          break;
        case 'M':
          months += field.whole();
          break;
        case 'W':
          nanos += field.whole() * 7 * NANOS_PER_DAY;
          sawMonths = false;
          break;
        case 'D':
          nanos += field.whole() * NANOS_PER_DAY;
          sawMonths = false;
          break;
        default:
          return null;
      }
      // A fraction of a year, a month, a week or a day is a length that
      // depends on which one it lands on, so it is refused here rather than
      // turned into a number of nanoseconds that is right for some of them.
      if (field.frac() != 0) {
        return null;
      }
    }
    for (Piece field : pieces(clock)) {
      if (!field.ok()) {
        return null;
      }
      switch (field.unit()) {
        case 'H':
          nanos += field.whole() * NANOS_PER_HOUR;
          break;
        case 'M':
          nanos += field.whole() * NANOS_PER_MINUTE;
          break;
        case 'S':
          nanos += field.whole() * NANOS_PER_SECOND + field.frac();
          break;
        default:
          return null;
      }
      if (field.frac() != 0 && field.unit() != 'S') {
        return null;
      }
    }
    if (months != 0 && nanos != 0) {
      return null;
    }
    if (negative) {
      months = -months;
      nanos = -nanos;
    }
    if (months != 0 || (nanos == 0 && sawMonths)) {
      return new Value.Temporal(Value.Temporal.Kind.DURATION_YEAR_MONTH, months, 0);
    }
    return new Value.Temporal(Value.Temporal.Kind.DURATION_DAY_TIME, nanos, 0);
  }

  /**
   * One number and the letter after it, which is what a duration is a run
   * of. The fraction is of a second, in nanoseconds, for the one field that
   * is allowed one.
   */
  private record Piece(long whole, long frac, char unit, boolean ok) {}

  /**
   * Half a duration split into its fields.
   *
   * <p>A half that does not split gives back one field that is not ok, so
   * that the caller refuses the text at the same place it refuses a unit it
   * does not know.
   */
  private static java.util.List<Piece> pieces(String text) {
    java.util.List<Piece> out = new java.util.ArrayList<>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c >= '0' && c <= '9') || c == '.') {
        continue;
      }
      String run = text.substring(start, i);
      int dot = run.indexOf('.');
      String head = dot < 0 ? run : run.substring(0, dot);
      String frac = dot < 0 ? null : run.substring(dot + 1);
      boolean ok = true;
      Long whole = number(head);
      if (whole == null) {
        ok = false;
        whole = 0L;
      }
      long scaled = 0;
      if (frac != null) {
        if (frac.isEmpty() || frac.length() > 9) {
          ok = false;
        } else {
          Long part = number(frac);
          if (part == null) {
            ok = false;
          } else {
            scaled = part;
            for (int n = frac.length(); n < 9; n++) {
              scaled *= 10;
            }
          }
        }
      }
      out.add(new Piece(whole, scaled, c, ok));
      start = i + 1;
    }
    // Digits with no unit after them, which is the one thing left over that
    // a caller has to hear about.
    if (start != text.length()) {
      out.add(new Piece(0, 0, '\0', false));
    }
    return out;
  }

  /** A date the way the engine prints one. */
  static String showDate(long days) {
    LocalDate when = LocalDate.ofEpochDay(days);
    return pad(when.getYear(), 4) + "-" + pad(when.getMonthValue(), 2) + "-"
        + pad(when.getDayOfMonth(), 2);
  }

  /**
   * A count of nanoseconds since midnight the way the engine prints one,
   * which is seconds always and a fraction of nine digits when there is
   * one.
   *
   * <p>Nine and not the shortest that reads back: the report this goes into
   * is diffed against the one the reference runner writes, and that one
   * writes nine.
   */
  static String showClock(long nanos) {
    long hours = nanos / NANOS_PER_HOUR;
    long minutes = nanos % NANOS_PER_HOUR / NANOS_PER_MINUTE;
    long seconds = nanos % NANOS_PER_MINUTE / NANOS_PER_SECOND;
    long frac = nanos % NANOS_PER_SECOND;
    String out = pad(hours, 2) + ":" + pad(minutes, 2) + ":" + pad(seconds, 2);
    return frac == 0 ? out : out + "." + pad(frac, 9);
  }

  /**
   * A count of nanoseconds from the epoch as a date and a time joined with
   * a {@code T}.
   */
  static String showStamp(long nanos) {
    long days = Math.floorDiv(nanos, NANOS_PER_DAY);
    return showDate(days) + "T" + showClock(nanos - days * NANOS_PER_DAY);
  }

  /**
   * An offset in minutes east of UTC, which is {@code Z} at zero rather
   * than {@code +00:00}.
   */
  static String showOffset(int offset) {
    if (offset == 0) {
      return "Z";
    }
    String sign = "+";
    int east = offset;
    if (east < 0) {
      sign = "-";
      east = -east;
    }
    return sign + pad(east / 60, 2) + ":" + pad(east % 60, 2);
  }

  /**
   * A month duration as the text that parses back to it: a field that is
   * zero is left out, and a duration with nothing left in it is
   * {@code P0M}, because {@code P} on its own is not a value.
   */
  static String showMonths(long count) {
    String sign = "";
    long left = count;
    if (left < 0) {
      sign = "-";
      left = -left;
    }
    long years = left / 12;
    long months = left % 12;
    StringBuilder out = new StringBuilder(sign).append('P');
    if (years != 0) {
      out.append(years).append('Y');
    }
    if (months != 0 || years == 0) {
      out.append(months).append('M');
    }
    return out.toString();
  }

  /**
   * A nanosecond duration as the text that parses back to it, under the
   * same rule, with {@code PT0S} for the one that is empty.
   */
  static String showNanos(long count) {
    String sign = "";
    long left = count;
    if (left < 0) {
      sign = "-";
      left = -left;
    }
    long days = left / NANOS_PER_DAY;
    long rest = left % NANOS_PER_DAY;
    StringBuilder out = new StringBuilder(sign).append('P');
    if (days != 0) {
      out.append(days).append('D');
    }
    if (rest == 0 && days != 0) {
      return out.toString();
    }
    out.append('T');
    long hours = rest / NANOS_PER_HOUR;
    long minutes = rest % NANOS_PER_HOUR / NANOS_PER_MINUTE;
    long seconds = rest % NANOS_PER_MINUTE / NANOS_PER_SECOND;
    long frac = rest % NANOS_PER_SECOND;
    if (hours != 0) {
      out.append(hours).append('H');
    }
    if (minutes != 0) {
      out.append(minutes).append('M');
    }
    if (seconds != 0 || frac != 0 || (hours == 0 && minutes == 0)) {
      out.append(seconds);
      if (frac != 0) {
        out.append('.').append(pad(frac, 9));
      }
      out.append('S');
    }
    return out.toString();
  }

  /** A number in at least width digits, zeroes in front of it. */
  static String pad(long n, int width) {
    StringBuilder text = new StringBuilder(Long.toString(n));
    while (text.length() < width) {
      text.insert(0, '0');
    }
    return text.toString();
  }
}
