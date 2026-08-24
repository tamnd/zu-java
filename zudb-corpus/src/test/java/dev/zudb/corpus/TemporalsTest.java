package dev.zudb.corpus;

import static dev.zudb.corpus.Temporals.NANOS_PER_DAY;
import static dev.zudb.corpus.Temporals.NANOS_PER_HOUR;
import static dev.zudb.corpus.Temporals.NANOS_PER_MINUTE;
import static dev.zudb.corpus.Temporals.NANOS_PER_SECOND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Value;
import dev.zudb.Value.Temporal.Kind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The temporal half of the encoding.
 *
 * <p>These parsers exist so that the corpus reader is a second opinion
 * about the text rather than a second call into the same library the
 * client calls, and a second opinion is only worth having if it says no to
 * the same things. So most of this file is texts that look like temporal
 * values and are not: a basic-form date, a leap second, a fraction of ten
 * digits, an offset past the standard's own limit.
 *
 * <p>The other thing tested here is which of the two duration kinds a text
 * comes to. A month is not a number of days, the client has a kind for
 * each, and which one a case means is part of what the case asserts.
 */
class TemporalsTest {

  private static Value.Temporal at(Kind kind, long count) {
    return new Value.Temporal(kind, count, 0);
  }

  @Test
  @DisplayName("a date is the extended form and the calendar answers for February")
  void aDateIsTheExtendedFormAndTheCalendarAnswersForFebruary() {
    record Case(String text, long days) {}
    for (Case c : List.of(
        new Case("1970-01-01", 0),
        new Case("1970-01-02", 1),
        new Case("1969-12-31", -1),
        new Case("2024-02-29", 19782),
        new Case("0001-01-01", -719162),
        new Case("9999-12-31", 2932896))) {
      assertEquals(at(Kind.DATE, c.days()), Temporals.parseDate(c.text()),
          c.text() + " is " + c.days() + " days from the epoch");
      // And back out again, which is what a failure report prints.
      assertEquals(c.text(), Temporals.showDate(c.days()),
          c.days() + " days should print as it was written");
    }
    for (String text : List.of(
        "20240101", // the basic form, which the other runners would not read
        "2024-1-1", // fields that are not padded
        "2023-02-30", // a day February does not have
        "2023-13-01", // a month the year does not have
        "2023-00-01", // and the two that are zero
        "2023-01-00",
        "2024-02-29x", // something after the date
        "+2024-01-01", // a sign, which the field reader does not take
        "2024/01/01", // the wrong separator
        "")) {
      assertNull(Temporals.parseDate(text), Text.quote(text) + " is not a date");
    }
  }

  @Test
  @DisplayName("a time is seconds always and a fraction when there is one")
  void aTimeIsSecondsAlwaysAndAFractionWhenThereIsOne() {
    record Case(String text, long nanos) {}
    for (Case c : List.of(
        new Case("00:00:00", 0),
        new Case("23:59:59", 86399 * NANOS_PER_SECOND),
        new Case("12:34:56.789000000", 45296 * NANOS_PER_SECOND + 789000000),
        new Case("12:34:56.789", 45296 * NANOS_PER_SECOND + 789000000),
        new Case("12:34:56.1", 45296 * NANOS_PER_SECOND + 100000000),
        new Case("23:59:59.999999999", 86400 * NANOS_PER_SECOND - 1))) {
      assertEquals(at(Kind.LOCAL_TIME, c.nanos()), Temporals.parseLocalTime(c.text()),
          c.text() + " is " + c.nanos() + " nanoseconds");
    }
    // Printed with nine digits when there is a fraction and none when
    // there is not, which is what the reference runner writes and not what
    // the client's own toString gives.
    record Print(long nanos, String want) {}
    for (Print c : List.of(
        new Print(0, "00:00:00"),
        new Print(100000000, "00:00:00.100000000"),
        new Print(1, "00:00:00.000000001"),
        new Print(86400 * NANOS_PER_SECOND - 1, "23:59:59.999999999"))) {
      assertEquals(c.want(), Temporals.showClock(c.nanos()),
          c.nanos() + " nanoseconds should print that way");
    }
    for (String text : List.of(
        "123456", // the basic form
        "1:02:03", // fields that are not padded
        "24:00:00", // the hour a day does not have
        "23:60:00", // and the minute
        "23:59:60", // the leap second, which the count does not have
        "12:34:56.", // a point with nothing after it
        "12:34:56.1234567890", // ten digits, finer than the engine counts
        "12:34:56.-1", // a sign inside the fraction
        "12:34", // no seconds
        "12:34:56Z", // an offset, which a local time does not carry
        "")) {
      assertNull(Temporals.parseLocalTime(text), Text.quote(text) + " is not a time");
    }
  }

  /**
   * A zoned time carries the clock as written rather than moved to UTC,
   * which is what makes {@code 12:00:00+07:00} and {@code 05:00:00Z} two
   * values here and not one.
   */
  @Test
  @DisplayName("a zoned time keeps the clock it was written with")
  void aZonedTimeKeepsTheClockItWasWrittenWith() {
    Value.Temporal got = Temporals.parseZonedTime("12:00:00+07:00");
    Value.Temporal want = new Value.Temporal(Kind.ZONED_TIME, 12 * NANOS_PER_HOUR, 7 * 60);
    assertEquals(want, got, "the clock is kept as written");
    assertNotEquals(Temporals.parseZonedTime("05:00:00Z"), got,
        "05:00:00Z and 12:00:00+07:00 are two values");
    assertEquals("12:00:00+07:00",
        Temporals.showClock(want.count()) + Temporals.showOffset(want.offsetMinutes()));
  }

  /**
   * A zoned datetime is held as the instant, so two texts an hour apart in
   * zones an hour apart are one instant and hold one count.
   */
  @Test
  @DisplayName("a zoned datetime is held as the instant and the offset beside it")
  void aZonedDateTimeIsHeldAsTheInstantAndTheOffsetBesideIt() {
    Value.Temporal east = Temporals.parseZonedDateTime("2024-01-01T07:00:00+07:00");
    Value.Temporal utc = Temporals.parseZonedDateTime("2024-01-01T00:00:00Z");
    assertEquals(utc.count(), east.count(),
        "the same instant should hold the same count");
    assertNotEquals(utc, east, "the offset is part of what a case asserts");
    // Printed back into the zone it was written in, which is the wall
    // clock a case reads.
    assertEquals("2024-01-01T07:00:00+07:00", stamp(east));
    // A zero offset prints as Z, whichever of the two spellings went in.
    assertEquals("2024-01-01T00:00:00Z",
        stamp(Temporals.parseZonedDateTime("2024-01-01T00:00:00+00:00")));
  }

  /** A zoned datetime back in the zone it was written in. */
  private static String stamp(Value.Temporal v) {
    return Temporals.showStamp(v.count() + v.offsetMinutes() * NANOS_PER_MINUTE)
        + Temporals.showOffset(v.offsetMinutes());
  }

  @Test
  @DisplayName("a local datetime counts from the epoch and reads back before it")
  void aLocalDateTimeCountsFromTheEpochAndReadsBackBeforeIt() {
    record Case(String text, long nanos) {}
    for (Case c : List.of(
        new Case("1970-01-01T00:00:00", 0),
        new Case("1970-01-02T00:00:00", NANOS_PER_DAY),
        new Case("1969-12-31T23:59:59", -NANOS_PER_SECOND),
        new Case("2024-01-15T10:00:00", 19737 * NANOS_PER_DAY + 10 * NANOS_PER_HOUR))) {
      assertEquals(at(Kind.LOCAL_DATETIME, c.nanos()), Temporals.parseLocalDateTime(c.text()),
          c.text() + " is " + c.nanos() + " nanoseconds");
      // The date of an instant before the epoch is the day it is on and not
      // the day after it, which is what the floored division is for.
      assertEquals(c.text(), Temporals.showStamp(c.nanos()),
          c.nanos() + " nanoseconds should print as it was written");
    }
    for (String text : List.of(
        "2024-01-15 10:00:00", // a space where the T goes
        "2024-01-15", // no time
        "10:00:00", // no date
        "2024-01-15T10:00:00Z", // an offset, which a local datetime does not carry
        "")) {
      assertNull(Temporals.parseLocalDateTime(text), Text.quote(text) + " is not a datetime");
    }
  }

  @Test
  @DisplayName("an offset is Z or the extended form within the standard's limit")
  void anOffsetIsZOrTheExtendedFormWithinTheStandardsLimit() {
    record Case(String text, String rest, int minutes) {}
    for (Case c : List.of(
        new Case("12:00:00Z", "12:00:00", 0),
        new Case("12:00:00+00:00", "12:00:00", 0),
        new Case("12:00:00+07:00", "12:00:00", 420),
        new Case("12:00:00-05:30", "12:00:00", -330),
        new Case("12:00:00+18:00", "12:00:00", 1080),
        new Case("12:00:00-18:00", "12:00:00", -1080))) {
      assertEquals(new Temporals.Offset(c.rest(), c.minutes()), Temporals.splitOffset(c.text()),
          c.text() + " splits that way");
    }
    for (String text : List.of(
        "12:00:00+18:01", // past the standard's own limit
        "12:00:00+19:00",
        "12:00:00+0700", // the basic form
        "12:00:00+07", // hours alone
        "12:00:00+07:60", // a minute an hour does not have
        "12:00:00", // no offset at all
        "12:00:00 07:00", // no sign
        "+07:00")) { // an offset and nothing before it, which is too short to split
      Temporals.Offset got = Temporals.splitOffset(text);
      assertTrue(got == null || got.rest().isEmpty(),
          Text.quote(text) + " carries no offset this reader takes");
    }
    record Print(int minutes, String want) {}
    for (Print c : List.of(
        new Print(0, "Z"),
        new Print(420, "+07:00"),
        new Print(-330, "-05:30"),
        new Print(1080, "+18:00"))) {
      assertEquals(c.want(), Temporals.showOffset(c.minutes()),
          c.minutes() + " should print that way");
    }
  }

  /**
   * A duration is months or it is nanoseconds and never both, because
   * adding a month to a date is a different operation from adding thirty
   * days and a kind holding both would have to say which happens first.
   */
  @Test
  @DisplayName("a duration is one of the two kinds the engine keeps apart")
  void aDurationIsOneOfTheTwoKindsTheEngineKeepsApart() {
    record Case(String text, Value.Temporal want) {}
    for (Case c : List.of(
        new Case("P1Y", months(12)),
        new Case("P1Y2M", months(14)),
        new Case("P2M", months(2)),
        new Case("-P1Y2M", months(-14)),
        // The one text the fields decide and the numbers cannot: no months
        // and no nanoseconds, told apart by what was written.
        new Case("P0M", months(0)),
        new Case("P1D", nanos(NANOS_PER_DAY)),
        new Case("P1W", nanos(7 * NANOS_PER_DAY)),
        new Case("PT1H", nanos(NANOS_PER_HOUR)),
        new Case("PT1M", nanos(NANOS_PER_MINUTE)),
        new Case("PT1S", nanos(NANOS_PER_SECOND)),
        new Case("PT0S", nanos(0)),
        new Case("PT0.250000000S", nanos(250 * 1_000_000L)),
        new Case("PT0.5S", nanos(500 * 1_000_000L)),
        new Case("P1DT2H3M4S", nanos(NANOS_PER_DAY + 2 * NANOS_PER_HOUR
            + 3 * NANOS_PER_MINUTE + 4 * NANOS_PER_SECOND)),
        new Case("-PT1H", nanos(-NANOS_PER_HOUR)),
        new Case("+PT1H", nanos(NANOS_PER_HOUR)))) {
      assertEquals(c.want(), Temporals.parseDuration(c.text()),
          c.text() + " is that duration");
    }
    for (String text : List.of(
        "P1Y1D", // a field of each kind, refused rather than guessed at
        "P1M1S", // likewise, through the time part
        "P", // a P with nothing under it
        "PT", // a T with nothing after it
        "P1DT", // and a T on the end of a date part
        "1Y", // no P
        "P1X", // a unit nothing here knows
        "P1", // digits with no unit after them
        "PT1", // likewise in the time part
        "P0.5Y", // a fraction of a year, whose length depends on which one
        "P0.5M", // and of a month
        "P0.5D", // and of a day, which a leap second makes not quite exact
        "PT0.5H", // a fraction anywhere but on the seconds
        "PT0.5M",
        "PT1.S", // a point with nothing after it
        "PT0.1234567890S", // ten digits
        "P1Y2M3W4DT5H6M7.8S", // every field at once, which is both kinds
        "")) {
      assertNull(Temporals.parseDuration(text),
          Text.quote(text) + " is not a duration this reader takes");
    }
  }

  /**
   * A duration prints as the text that parses back to it, which is what
   * lets a failure report be pasted into a case.
   */
  @Test
  @DisplayName("a duration prints as the text that reads back to it")
  void aDurationPrintsAsTheTextThatReadsBackToIt() {
    record Case(Value.Temporal value, String want) {}
    for (Case c : List.of(
        new Case(months(0), "P0M"),
        new Case(months(1), "P1M"),
        new Case(months(12), "P1Y"),
        new Case(months(14), "P1Y2M"),
        new Case(months(-14), "-P1Y2M"),
        new Case(nanos(0), "PT0S"),
        new Case(nanos(NANOS_PER_SECOND), "PT1S"),
        new Case(nanos(NANOS_PER_MINUTE), "PT1M"),
        new Case(nanos(NANOS_PER_HOUR), "PT1H"),
        new Case(nanos(NANOS_PER_DAY), "P1D"),
        new Case(nanos(25 * NANOS_PER_HOUR), "P1DT1H"),
        new Case(nanos(250 * 1_000_000L), "PT0.250000000S"),
        new Case(nanos(NANOS_PER_SECOND + 1), "PT1.000000001S"),
        new Case(nanos(-NANOS_PER_HOUR), "-PT1H"),
        new Case(nanos(NANOS_PER_DAY + 2 * NANOS_PER_HOUR
            + 3 * NANOS_PER_MINUTE + 4 * NANOS_PER_SECOND), "P1DT2H3M4S"),
        new Case(nanos(NANOS_PER_HOUR + NANOS_PER_MINUTE), "PT1H1M"))) {
      String got = c.value().kind() == Kind.DURATION_YEAR_MONTH
          ? Temporals.showMonths(c.value().count())
          : Temporals.showNanos(c.value().count());
      assertEquals(c.want(), got, c.value() + " should print that way");
      // And back, which is the half that says the spelling is the one this
      // reader takes and not merely one that looks right.
      assertEquals(c.value(), Temporals.parseDuration(c.want()),
          Text.quote(c.want()) + " should read back to it");
    }
  }

  private static Value.Temporal months(long count) {
    return at(Kind.DURATION_YEAR_MONTH, count);
  }

  private static Value.Temporal nanos(long count) {
    return at(Kind.DURATION_DAY_TIME, count);
  }

  /**
   * The field reader every parser above goes through, which is not
   * {@code Long.parseLong}: a sign or a grouping mark inside a temporal
   * field is a text some other runner would refuse.
   */
  @Test
  @DisplayName("a temporal field is digits and nothing else")
  void aTemporalFieldIsDigitsAndNothingElse() {
    record Case(String text, long want) {}
    for (Case c : List.of(
        new Case("0", 0),
        new Case("07", 7),
        new Case("2024", 2024))) {
      assertEquals(c.want(), Temporals.number(c.text()), c.text() + " is that number");
    }
    for (String text : List.of("", "+1", "-1", "1_0", " 1", "1 ", "0x1", "١٢")) {
      assertNull(Temporals.number(text), Text.quote(text) + " is not a field");
    }
  }
}
