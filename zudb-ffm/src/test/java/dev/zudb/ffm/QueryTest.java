package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.Row;
import dev.zudb.Type;
import dev.zudb.Value;
import dev.zudb.ZuClosedException;
import dev.zudb.ZuProgrammingException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reading rows, which is what almost every program does with this client. */
class QueryTest {

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
  void oneRowOneColumn() {
    try (Result r = conn.query("RETURN 1 AS one")) {
      assertEquals(1, r.rows());
      assertEquals(1, r.columns());
      assertEquals(List.of("one"), r.columnNames());
      assertEquals(1, r.row(0).getLong(0));
      assertEquals(1, r.row(0).getLong("one"));
    }
  }

  @Test
  void everyScalarComesBackAsTheTypeItIs() {
    try (Result r =
        conn.query("RETURN 1 AS i, 1.5 AS f, 'ada' AS s, true AS b, null AS n")) {
      Row row = r.row(0);
      assertEquals(Type.INT, row.type("i"));
      assertEquals(Type.FLOAT, row.type("f"));
      assertEquals(Type.STR, row.type("s"));
      assertEquals(Type.BOOL, row.type("b"));
      assertEquals(Type.NULL, row.type("n"));

      assertEquals(1, row.getLong("i"));
      assertEquals(1.5, row.getDouble("f"));
      assertEquals("ada", row.getString("s"));
      assertTrue(row.getBoolean("b"));
      assertTrue(row.isNull("n"));
    }
  }

  @Test
  void anIntegerWidensToAFloatAndNothingElseConverts() {
    try (Result r = conn.query("RETURN 7 AS i")) {
      assertEquals(7.0, r.row(0).getDouble("i"));
      assertThrows(ZuProgrammingException.class, () -> r.row(0).getString("i"));
      assertThrows(ZuProgrammingException.class, () -> r.row(0).getBoolean("i"));
    }
  }

  @Test
  void aNullStringIsNullRatherThanAThrow() {
    try (Result r = conn.query("RETURN null AS s")) {
      assertNull(r.row(0).getString("s"));
      assertEquals(Value.Null.instance(), r.row(0).get("s"));
    }
  }

  @Test
  void aNullIntegerIsAThrowBecauseALongCannotSayNothing() {
    try (Result r = conn.query("RETURN null AS i")) {
      ZuProgrammingException e =
          assertThrows(ZuProgrammingException.class, () -> r.row(0).getLong("i"));
      assertTrue(e.getMessage().contains("nothing"), e.getMessage());
    }
  }

  @Test
  void manyRowsInOrder() {
    try (Result r = conn.query("UNWIND [10, 20, 30] AS v RETURN v")) {
      assertEquals(3, r.rows());
      assertEquals(List.of(10L, 20L, 30L), r.stream().map(row -> row.getLong(0)).toList());
    }
  }

  @Test
  void theIterableIsTheSameRowsAsTheStream() {
    try (Result r = conn.query("UNWIND ['a', 'b'] AS v RETURN v")) {
      StringBuilder sb = new StringBuilder();
      for (Row row : r) {
        sb.append(row.getString(0));
      }
      assertEquals("ab", sb.toString());
    }
  }

  @Test
  void aStatementWithNoRowsIsAnEmptyResultRatherThanAFailure() {
    try (Result r = conn.query("UNWIND [] AS v RETURN v")) {
      assertEquals(0, r.rows());
      assertEquals(0, r.stream().count());
      assertEquals(0, r.longs(0).remaining());
    }
  }

  @Test
  void aColumnNobodyNamedIsAFailureThatListsTheOnesThereAre() {
    try (Result r = conn.query("RETURN 1 AS one")) {
      ZuProgrammingException e =
          assertThrows(ZuProgrammingException.class, () -> r.row(0).getLong("won"));
      assertTrue(e.getMessage().contains("one"), e.getMessage());
    }
  }

  @Test
  void aColumnOffTheEndIsAFailureRatherThanAReadOfSomethingElse() {
    try (Result r = conn.query("RETURN 1 AS one")) {
      assertThrows(ZuProgrammingException.class, () -> r.row(0).get(1));
      assertThrows(ZuProgrammingException.class, () -> r.row(0).get(-1));
      assertThrows(ZuProgrammingException.class, () -> r.row(1));
    }
  }

  @Test
  void twoColumnsOfOneNameResolveToTheFirst() {
    try (Result r = conn.query("RETURN 1 AS v, 2 AS w")) {
      assertEquals(0, r.columnIndex("v"));
      assertEquals(1, r.columnIndex("w"));
    }
  }

  @Test
  void aClosedResultSaysSoRatherThanReadingFreedMemory() {
    Result r = conn.query("RETURN 1 AS one");
    Row row = r.row(0);
    r.close();
    assertTrue(r.isClosed());
    assertThrows(ZuClosedException.class, () -> row.getLong(0));
    r.close();
  }

  @Test
  void aStatementSaysHowItCompleted() {
    try (Result r = conn.query("RETURN 1 AS one")) {
      assertEquals("00000", r.gqlstatus());
      assertTrue(r.notices().isEmpty());
    }
  }

  @Test
  void aRowPrintsItselfWithItsColumnNames() {
    try (Result r = conn.query("RETURN 1 AS one, 'x' AS two")) {
      String text = r.row(0).toString();
      assertTrue(text.contains("one="), text);
      assertTrue(text.contains("two="), text);
    }
  }

  @Test
  void executeRunsAStatementAndKeepsNothing() {
    conn.execute("RETURN 1 AS one");
    assertFalse(conn.isClosed());
  }

  @Test
  void theRowsOfAResultOutliveNothingButTheResult() {
    List<String> names;
    try (Result r = conn.query("UNWIND ['ada', 'grace'] AS v RETURN v")) {
      names = r.stream().map(row -> row.getString(0)).collect(Collectors.toList());
    }
    // The strings were copied out on the way, so this is still readable.
    assertEquals(List.of("ada", "grace"), names);
  }
}
