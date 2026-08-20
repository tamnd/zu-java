package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.Statement;
import dev.zudb.Value;
import dev.zudb.ZuClosedException;
import dev.zudb.ZuProgrammingException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Preparing once and running many times, which is what a loop wants. */
public class StatementTest {

  private static Database db;
  private static Connection conn;

  @BeforeAll
  static void engine() {
    Libzu.require();
    db = Database.memory();
    conn = db.connect();
  }

  @AfterAll
  static void done() {
    if (conn != null) {
      conn.close();
    }
    if (db != null) {
      db.close();
    }
  }

  @Test
  void aParameterCrossesAndComesBack() {
    try (Statement stmt = conn.prepare("RETURN $v AS v");
        Result r = stmt.bind("v", 42L).execute()) {
      assertEquals(42, r.row(0).getLong("v"));
    }
  }

  @Test
  void everyScalarKindOfParameter() {
    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      try (Result r = stmt.bind("v", 1.5).execute()) {
        assertEquals(1.5, r.row(0).getDouble("v"));
      }
      try (Result r = stmt.bind("v", true).execute()) {
        assertTrue(r.row(0).getBoolean("v"));
      }
      try (Result r = stmt.bind("v", "ada").execute()) {
        assertEquals("ada", r.row(0).getString("v"));
      }
      try (Result r = stmt.bindNull("v").execute()) {
        assertTrue(r.row(0).isNull("v"));
      }
    }
  }

  @Test
  void aBindingSurvivesAnExecuteAndRebindingReplacesIt() {
    List<Long> seen = new ArrayList<>();
    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      for (long v : new long[] {1, 2, 3}) {
        try (Result r = stmt.bind("v", v).execute()) {
          seen.add(r.row(0).getLong("v"));
        }
      }
      // Nothing was rebound, so the last value is still there.
      try (Result r = stmt.execute()) {
        seen.add(r.row(0).getLong("v"));
      }
    }
    assertEquals(List.of(1L, 2L, 3L, 3L), seen);
  }

  @Test
  void bindsChain() {
    try (Statement stmt = conn.prepare("RETURN $a AS a, $b AS b");
        Result r = stmt.bind("a", 1L).bind("b", "two").execute()) {
      assertEquals(1, r.row(0).getLong("a"));
      assertEquals("two", r.row(0).getString("b"));
    }
  }

  @Test
  void aStringParameterOfNullSaysWhichCallToUse() {
    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      ZuProgrammingException e =
          assertThrows(ZuProgrammingException.class, () -> stmt.bind("v", (String) null));
      assertTrue(e.getMessage().contains("bindNull"), e.getMessage());
    }
  }

  @Test
  void aDateGoesOutAndComesBack() {
    LocalDate date = LocalDate.of(2026, 8, 20);
    try (Statement stmt = conn.prepare("RETURN $v AS v");
        Result r = stmt.bind("v", date).execute()) {
      assertEquals(date, r.row(0).getTemporal("v").toLocalDate());
    }
  }

  @Test
  void everyTemporalKindGoesOutAndComesBack() {
    LocalTime time = LocalTime.of(13, 45, 30, 123_456_789);
    OffsetTime zonedTime = OffsetTime.of(LocalTime.of(9, 30), ZoneOffset.ofHours(2));
    LocalDateTime datetime = LocalDateTime.of(2026, 8, 20, 11, 22, 33);
    OffsetDateTime zoned =
        OffsetDateTime.of(LocalDateTime.of(2026, 8, 20, 11, 0), ZoneOffset.ofHours(-5));

    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      try (Result r = stmt.bind("v", time).execute()) {
        assertEquals(time, r.row(0).getTemporal("v").toLocalTime());
      }
      try (Result r = stmt.bind("v", zonedTime).execute()) {
        assertEquals(zonedTime, r.row(0).getTemporal("v").toOffsetTime());
      }
      try (Result r = stmt.bind("v", datetime).execute()) {
        assertEquals(datetime, r.row(0).getTemporal("v").toLocalDateTime());
      }
      try (Result r = stmt.bind("v", zoned).execute()) {
        assertEquals(zoned.toInstant(), r.row(0).getTemporal("v").toOffsetDateTime().toInstant());
      }
      try (Result r = stmt.bind("v", Period.ofMonths(14)).execute()) {
        assertEquals(Period.of(1, 2, 0), r.row(0).getTemporal("v").toPeriod());
      }
      try (Result r = stmt.bind("v", Duration.ofHours(25)).execute()) {
        assertEquals(Duration.ofHours(25), r.row(0).getTemporal("v").toDuration());
      }
    }
  }

  @Test
  void aPeriodWithDaysIsRefusedRatherThanRounded() {
    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      ZuProgrammingException e =
          assertThrows(
              ZuProgrammingException.class, () -> stmt.bind("v", Period.of(0, 1, 1)));
      assertTrue(e.getMessage().contains("year-month"), e.getMessage());
    }
  }

  @Test
  void aTemporalReadOutOfOneResultBindsIntoTheNext() {
    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      Value.Temporal out;
      try (Result r = stmt.bind("v", LocalDate.of(1999, 12, 31)).execute()) {
        out = r.row(0).getTemporal("v");
      }
      try (Result r = stmt.bind("v", out).execute()) {
        assertEquals(LocalDate.of(1999, 12, 31), r.row(0).getTemporal("v").toLocalDate());
      }
    }
  }

  @Test
  void aClosedStatementSaysSoAndIsStillSafeToClose() {
    Statement stmt = conn.prepare("RETURN $v AS v");
    stmt.close();
    assertTrue(stmt.isClosed());
    assertThrows(ZuClosedException.class, () -> stmt.bind("v", 1L));
    assertThrows(ZuClosedException.class, stmt::execute);
    stmt.close();
  }

  @Test
  void aResultOutlivesTheStatementThatMadeIt() {
    Result r;
    try (Statement stmt = conn.prepare("RETURN $v AS v")) {
      r = stmt.bind("v", 7L).execute();
    }
    try (Result open = r) {
      assertEquals(7, open.row(0).getLong("v"));
    }
  }
}
