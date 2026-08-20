package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.Type;
import dev.zudb.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The values that have no column to be read into: lists, records and the
 * trees they make.
 */
public class ValueTest {

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
  void aListIsAListOfValues() {
    try (Result r = conn.query("RETURN [1, 2, 3] AS v")) {
      assertEquals(Type.LIST, r.row(0).type("v"));
      Value.List list = assertInstanceOf(Value.List.class, r.row(0).get("v"));
      assertEquals(3, list.items().size());
      assertEquals(new Value.Int(1), list.items().get(0));
      assertEquals(new Value.Int(3), list.items().get(2));
    }
  }

  @Test
  void aListWithAHoleInItKeepsTheHole() {
    try (Result r = conn.query("RETURN [1, null, 3] AS v")) {
      Value.List list = assertInstanceOf(Value.List.class, r.row(0).get("v"));
      assertEquals(Value.Null.instance(), list.items().get(1));
    }
  }

  @Test
  void aListOfListsRecurses() {
    try (Result r = conn.query("RETURN [[1, 2], [3]] AS v")) {
      Value.List outer = assertInstanceOf(Value.List.class, r.row(0).get("v"));
      Value.List inner = assertInstanceOf(Value.List.class, outer.items().get(0));
      assertEquals(2, inner.items().size());
      assertEquals(new Value.Int(2), inner.items().get(1));
    }
  }

  @Test
  void aRecordCarriesItsFieldNames() {
    try (Result r = conn.query("RETURN {a: 1, b: 'x'} AS v")) {
      assertEquals(Type.RECORD, r.row(0).type("v"));
      Value.Record rec = assertInstanceOf(Value.Record.class, r.row(0).get("v"));
      assertEquals(2, rec.fields().size());
      // Fields come in name order, which is what makes two records written
      // in different orders one value.
      assertEquals("a", rec.fields().get(0).name());
      assertEquals(new Value.Int(1), rec.fields().get(0).value());
      assertEquals("b", rec.fields().get(1).name());
      assertEquals(new Value.Str("x"), rec.fields().get(1).value());
    }
  }

  @Test
  void aRecordOfAListOfARecord() {
    try (Result r = conn.query("RETURN {a: [{b: 1}]} AS v")) {
      Value.Record outer = assertInstanceOf(Value.Record.class, r.row(0).get("v"));
      Value.List list = assertInstanceOf(Value.List.class, outer.fields().get(0).value());
      Value.Record inner = assertInstanceOf(Value.Record.class, list.items().get(0));
      assertEquals("b", inner.fields().get(0).name());
      assertEquals(new Value.Int(1), inner.fields().get(0).value());
    }
  }

  @Test
  void aStringInATreeIsCopiedOutAndOutlivesTheResult() {
    Value value;
    try (Result r = conn.query("RETURN ['ada', 'grace'] AS v")) {
      value = r.row(0).get("v");
    }
    Value.List list = assertInstanceOf(Value.List.class, value);
    assertEquals(new Value.Str("ada"), list.items().get(0));
    assertEquals(new Value.Str("grace"), list.items().get(1));
  }

  @Test
  void twoValuesWithTheSameContentsAreOneValue() {
    // Records all the way down, so equality is structural and a test can
    // write the value it expects rather than walking it.
    try (Result a = conn.query("RETURN [1, 'x'] AS v");
        Result b = conn.query("RETURN [1, 'x'] AS v")) {
      assertEquals(a.row(0).get("v"), b.row(0).get("v"));
    }
  }

  @Test
  void everyValueIsOneOfTheSealedArms() {
    try (Result r = conn.query("RETURN 1 AS i, 1.5 AS f, 'x' AS s, true AS b, null AS n")) {
      for (int c = 0; c < r.columns(); c++) {
        Value v = r.row(0).get(c);
        assertTrue(v instanceof Value.Int
                || v instanceof Value.Float
                || v instanceof Value.Str
                || v instanceof Value.Bool
                || v instanceof Value.Null,
            "unexpected arm: " + v.getClass());
      }
    }
  }

  @Test
  void aTypeThisClientKnowsComesBackForEveryCell() {
    try (Result r = conn.query("RETURN 1 AS i, [1] AS l, {a: 1} AS m")) {
      assertEquals(Type.INT, r.cellType(0, 0));
      assertEquals(Type.LIST, r.cellType(0, 1));
      assertEquals(Type.RECORD, r.cellType(0, 2));
    }
  }
}
