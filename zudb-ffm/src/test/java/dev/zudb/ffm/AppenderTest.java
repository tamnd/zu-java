package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Appender;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Value;
import dev.zudb.ZuException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adding rows to a table that already exists.
 *
 * <p>Every test here builds its own database first, because the engine has no
 * DDL and a bulk load is the only thing that makes a table for an appender to
 * append to.
 */
class AppenderTest {

  @TempDir Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  /** A two column Person table with three rows in it, at a path of its own. */
  private Path people(String name) {
    Path path = dir.resolve(name + ".zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      loader.column("id", 1L, 2L, 3L);
      loader.column("name", "ada", "grace", "alan");
      loader.finish();
    }
    return path;
  }

  @Test
  void rowsGoInAndComeBack() {
    Path path = people("rows");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        rows.append(4L).append("hedy").endRow();
        rows.append(5L).append("katherine").endRow();
        assertEquals(2L, rows.finish());
        assertTrue(rows.isFinished());
      }

      try (Result r = conn.query("MATCH (p:Person) RETURN p.name ORDER BY p.id")) {
        assertEquals(
            List.of("ada", "grace", "alan", "hedy", "katherine"),
            r.stream().map(row -> row.getString(0)).collect(Collectors.toList()));
      }
    }
  }

  @Test
  void aRowIsARowOnceEndRowHasEndedIt() {
    Path path = people("ended");
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Appender rows = conn.appender("Person")) {
      assertEquals(0L, rows.buffered());
      rows.append(4L).append("hedy");
      assertEquals(0L, rows.buffered());
      rows.endRow();
      assertEquals(1L, rows.buffered());
      assertEquals(0L, rows.committed());
      rows.flush();
      assertEquals(0L, rows.buffered());
      assertEquals(1L, rows.committed());
    }
  }

  @Test
  void theAppenderSaysWhatItIsWriting() {
    Path path = people("columns");
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Appender rows = conn.appender("Person")) {
      assertEquals(2, rows.columns());
      assertEquals("id", rows.columnName(0));
      assertEquals("name", rows.columnName(1));
      ZuException e = assertThrows(ZuException.class, () -> rows.columnName(2));
      assertTrue(e.getMessage().contains("only 2"), e.getMessage());
    }
  }

  @Test
  void whatIsDiscardedNeverArrives() {
    Path path = people("discard");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        rows.append(4L).append("hedy").endRow();
        rows.flush();
        rows.append(5L).append("katherine").endRow();
        assertEquals(1L, rows.discard());
        assertEquals(0L, rows.buffered());
        // What an earlier flush wrote is written, and a discard does not
        // reach back to it.
        assertEquals(1L, rows.committed());
      }

      try (Result r = conn.query("MATCH (p:Person) RETURN count(*)")) {
        assertEquals(4L, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void closingWithoutFinishingKeepsWhatWasWritten() {
    // A loop that threw halfway keeps the rows it managed. Throwing away work
    // that succeeded is not a decision a close gets to make on its own.
    Path path = people("kept");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        rows.append(4L).append("hedy").endRow();
        assertFalse(rows.isFinished());
      }

      try (Result r = conn.query("MATCH (p:Person) RETURN count(*)")) {
        assertEquals(4L, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void aValueTheColumnWillNotTakeEndsItsRowAndLeavesNoHalfOfIt() {
    Path path = people("refused");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        rows.append(4L);
        // The second column holds strings, and a double is not one.
        assertThrows(ZuException.class, () -> rows.append(1.5));
        assertEquals(0L, rows.buffered());
        rows.append(4L).append("hedy").endRow();
        assertEquals(1L, rows.finish());
      }

      try (Result r = conn.query("MATCH (p:Person) RETURN count(*)")) {
        assertEquals(4L, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void aRowOfObjectsIsARowOfValues() {
    Path path = people("objects");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        rows.row(4, "hedy");
        rows.row(5L, "katherine");
        assertEquals(2L, rows.finish());
      }

      try (Result r = conn.query("MATCH (p:Person) WHERE p.id > 3 RETURN count(*)")) {
        assertEquals(2L, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void aRowOfSomethingNoColumnHoldsSaysWhichClass() {
    Path path = people("wrongclass");
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Appender rows = conn.appender("Person")) {
      ZuException e = assertThrows(ZuException.class, () -> rows.row(4L, List.of("hedy")));
      assertTrue(e.getMessage().contains("no column holds one of those"), e.getMessage());
    }
  }

  @Test
  void thereIsNoNullToAppend() {
    Path path = people("nulls");
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Appender rows = conn.appender("Person")) {
      assertThrows(ZuException.class, () -> rows.append((String) null));
      ZuException e = assertThrows(ZuException.class, () -> rows.row(4L, null));
      assertTrue(e.getMessage().contains("no null to append"), e.getMessage());
    }
  }

  @Test
  void aTableThatIsNotThereIsRefusedAtTheOpen() {
    Path path = people("missing");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      assertThrows(ZuException.class, () -> conn.appender("Nobody"));
    }
  }

  @Test
  void aClosedAppenderSaysSoRatherThanCrashing() {
    Path path = people("closed");
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      Appender rows = conn.appender("Person");
      rows.close();
      rows.close();
      assertThrows(ZuException.class, () -> rows.append(4L));
    }
  }

  @Test
  void aTemporalGoesInAsTheCountItsKindImplies() {
    Path path = dir.resolve("dates.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Event", "Before", 1);
      loader.column("id", 1L);
      loader.temporalColumn("on", Value.Temporal.Kind.DATE, java.time.LocalDate.EPOCH.toEpochDay());
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Event")) {
        rows.append(2L).append(java.time.LocalDate.of(1843, 8, 10)).endRow();
        assertEquals(1L, rows.finish());
      }

      try (Result r = conn.query("MATCH (e:Event) RETURN e.on ORDER BY e.id")) {
        assertEquals(2, r.rows());
        assertEquals(java.time.LocalDate.EPOCH, r.row(0).getTemporal(0).toLocalDate());
        assertEquals(java.time.LocalDate.of(1843, 8, 10), r.row(1).getTemporal(0).toLocalDate());
      }
    }
  }

  @Test
  void manyRowsAcrossManyFlushes() {
    Path path = people("many");
    int count = 5_000;
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        for (int i = 0; i < count; i++) {
          rows.append(100L + i).append("n" + i).endRow();
        }
        assertEquals(count, rows.finish());
      }

      try (Result r = conn.query("MATCH (p:Person) WHERE p.id >= 100 RETURN count(*)")) {
        assertEquals(count, r.row(0).getLong(0));
      }
    }
  }

  @Test
  void anAppenderAndAQueryOnOneConnectionDoNotTreadOnEachOther() {
    Path path = people("interleaved");
    List<Long> counts = new ArrayList<>();
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Appender rows = conn.appender("Person")) {
        for (int i = 0; i < 3; i++) {
          rows.append(10L + i).append("n" + i).endRow();
          rows.flush();
          try (Result r = conn.query("MATCH (p:Person) RETURN count(*)")) {
            counts.add(r.row(0).getLong(0));
          }
        }
        rows.finish();
      }
    }
    assertEquals(List.of(4L, 5L, 6L), counts);
  }
}
