package dev.zudb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The seven temporals, and the java.time value each of them is. */
class TemporalTest {

  @Test
  void aDateIsDaysSinceTheEpoch() {
    assertEquals(
        LocalDate.of(2026, 8, 20),
        new Value.Temporal(Value.Temporal.Kind.DATE, LocalDate.of(2026, 8, 20).toEpochDay(), 0)
            .toLocalDate());
  }

  @Test
  void aDateBeforeTheEpochCountsBackwards() {
    assertEquals(
        LocalDate.of(1969, 12, 31),
        new Value.Temporal(Value.Temporal.Kind.DATE, -1, 0).toLocalDate());
  }

  @Test
  void aLocalTimeIsNanosecondsSinceMidnight() {
    LocalTime t = LocalTime.of(13, 45, 30, 123_456_789);
    assertEquals(
        t,
        new Value.Temporal(Value.Temporal.Kind.LOCAL_TIME, t.toNanoOfDay(), 0).toLocalTime());
  }

  @Test
  void aZonedTimeCarriesItsOffset() {
    OffsetTime t = OffsetTime.of(LocalTime.of(9, 30), ZoneOffset.ofHoursMinutes(5, 30));
    assertEquals(
        t,
        new Value.Temporal(
                Value.Temporal.Kind.ZONED_TIME, t.toLocalTime().toNanoOfDay(), 5 * 60 + 30)
            .toOffsetTime());
  }

  @Test
  void aLocalDatetimeIsNanosecondsSinceTheEpoch() {
    LocalDateTime d = LocalDateTime.of(2026, 8, 20, 11, 22, 33, 444_000_000);
    long nanos = d.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + d.getNano();
    assertEquals(
        d, new Value.Temporal(Value.Temporal.Kind.LOCAL_DATETIME, nanos, 0).toLocalDateTime());
  }

  @Test
  void aLocalDatetimeBeforeTheEpochRoundsTheRightWay() {
    // Integer division truncates towards zero and this has to floor, which
    // is the whole reason the conversion uses Math.floorDiv.
    LocalDateTime d = LocalDateTime.of(1960, 1, 1, 0, 0, 0, 1);
    long nanos = d.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L + d.getNano();
    assertEquals(
        d, new Value.Temporal(Value.Temporal.Kind.LOCAL_DATETIME, nanos, 0).toLocalDateTime());
  }

  @Test
  void aZonedDatetimeIsTheSameInstantInItsOwnOffset() {
    OffsetDateTime d =
        OffsetDateTime.of(LocalDateTime.of(2026, 8, 20, 11, 0), ZoneOffset.ofHours(-5));
    long nanos = d.toEpochSecond() * 1_000_000_000L + d.getNano();
    OffsetDateTime read =
        new Value.Temporal(Value.Temporal.Kind.ZONED_DATETIME, nanos, -5 * 60).toOffsetDateTime();
    assertEquals(d, read);
    assertEquals(ZoneOffset.ofHours(-5), read.getOffset());
  }

  @Test
  void aYearMonthDurationIsMonthsAndNormalises() {
    assertEquals(
        Period.of(1, 2, 0),
        new Value.Temporal(Value.Temporal.Kind.DURATION_YEAR_MONTH, 14, 0).toPeriod());
  }

  @Test
  void aDayTimeDurationIsNanoseconds() {
    assertEquals(
        Duration.ofHours(25).plusNanos(7),
        new Value.Temporal(
                Value.Temporal.Kind.DURATION_DAY_TIME, Duration.ofHours(25).toNanos() + 7, 0)
            .toDuration());
  }

  @Test
  void readingOneKindAsAnotherIsRefusedAndSaysWhichIsWhich() {
    Value.Temporal date = new Value.Temporal(Value.Temporal.Kind.DATE, 0, 0);
    ZuProgrammingException e = assertThrows(ZuProgrammingException.class, date::toDuration);
    assertEquals("this temporal is a DATE and not a DURATION_DAY_TIME", e.getMessage());
  }

  @Test
  void everyKindHasItsAbiNumberAndBackAgain() {
    for (Value.Temporal.Kind k : Value.Temporal.Kind.values()) {
      assertEquals(k, Value.Temporal.Kind.of(k.value()));
    }
  }

  @Test
  void aKindThisClientDoesNotKnowIsRefusedRatherThanGuessed() {
    assertThrows(ZuProgrammingException.class, () -> Value.Temporal.Kind.of(7));
  }

  @Test
  void theFiveKindsWithNoOffsetAnswerUtc() {
    assertEquals(ZoneOffset.UTC, new Value.Temporal(Value.Temporal.Kind.DATE, 0, 0).offset());
  }
}
