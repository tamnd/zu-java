package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Config;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.ZuClosedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opening, connecting, and closing, in that order and the reverse. */
public class DatabaseTest {

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void aDatabaseInMemoryKnowsItIsOne() {
    try (Database db = Database.memory()) {
      assertTrue(db.isMemory());
      assertNotNull(db.path());
      assertFalse(db.isClosed());
    }
  }

  @Test
  void aDatabaseOnDiskKnowsItIsNot(@TempDir Path dir) {
    Path file = dir.resolve("graph.zu");
    try (Database db = Database.create(file)) {
      assertFalse(db.isMemory());
      assertEquals(file.toString(), db.path());
    }
    assertTrue(Files.isRegularFile(file));
    try (Database again = Database.open(file)) {
      assertFalse(again.isMemory());
    }
  }

  @Test
  void aConfigurationCrossesTheBoundary() {
    try (Database db = Database.memory(Config.defaults().withThreads(1).withMemoryLimit(1 << 20));
        Connection conn = db.connect();
        Result r = conn.query("RETURN 1 AS one")) {
      assertEquals(1, r.rows());
    }
  }

  @Test
  void closingTwiceIsNotAFailure() {
    Database db = Database.memory();
    db.close();
    db.close();
    assertTrue(db.isClosed());
  }

  @Test
  void usingAClosedDatabaseSaysSoRatherThanCrashing() {
    Database db = Database.memory();
    db.close();
    assertThrows(ZuClosedException.class, db::connect);
    assertThrows(ZuClosedException.class, db::isMemory);
  }

  @Test
  void usingAClosedConnectionSaysSoRatherThanCrashing() {
    try (Database db = Database.memory()) {
      Connection conn = db.connect();
      conn.close();
      assertThrows(ZuClosedException.class, () -> conn.query("RETURN 1 AS one"));
      assertTrue(conn.isClosed());
    }
  }

  @Test
  void aDuplicateIsASecondConnectionOnTheSameGraph() {
    try (Database db = Database.memory();
        Connection first = db.connect();
        Connection second = first.duplicate()) {
      assertFalse(second.isClosed());
      try (Result r = second.query("RETURN 2 AS two")) {
        assertEquals(2, r.row(0).getLong("two"));
      }
    }
  }

  @Test
  void aConnectionCountsTheRowsItHasRead() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      long before = conn.rowsRead();
      try (Result r = conn.query("UNWIND [1, 2, 3, 4] AS v RETURN v")) {
        assertEquals(4, r.rows());
      }
      assertTrue(conn.rowsRead() >= before);
    }
  }
}
