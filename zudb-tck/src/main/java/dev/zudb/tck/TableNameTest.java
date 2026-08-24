package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Value;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Turning the number in a node back into the name the statement wrote.
 *
 * <p>A node is a table and a row of it, and the table comes over as the id the
 * engine keeps it under, because two tables number their rows from zero and
 * the number is what identifies the node. Every host that prints a node wants
 * the name, so the ABI has one call for it.
 */
public class TableNameTest {

  @TempDir Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  private Path people() {
    Path path = dir.resolve("named.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("person", "knows", 2);
      loader.column("uid", 1L, 2L);
      loader.edges(new int[] {0}, new int[] {1});
      loader.finish();
    }
    return path;
  }

  @Test
  void aNodesTableIdHasAName() {
    try (Database db = Database.open(people());
        Connection conn = db.connect();
        Result r = conn.query("MATCH (p:person) RETURN p ORDER BY p.uid")) {
      Value.Node node = assertInstanceOf(Value.Node.class, r.row(0).get(0));
      assertEquals("person", conn.tableName(node.table()));
    }
  }

  @Test
  void aRelsTableIdHasAName() {
    // Node and rel tables share one id space, so one call answers for both
    // kinds, and the two ids are not the same id.
    try (Database db = Database.open(people());
        Connection conn = db.connect();
        Result r = conn.query("MATCH (a:person)-[e:knows]->(b:person) RETURN a, e")) {
      Value.Node node = assertInstanceOf(Value.Node.class, r.row(0).get(0));
      Value.Rel rel = assertInstanceOf(Value.Rel.class, r.row(0).get(1));
      assertNotEquals(node.table(), rel.table());
      assertEquals("person", conn.tableName(node.table()));
      assertEquals("knows", conn.tableName(rel.table()));
    }
  }

  @Test
  void anIdNoTableHasIsNullRatherThanAThrow() {
    try (Database db = Database.open(people());
        Connection conn = db.connect()) {
      // Not an error: asking is how a host finds out, and a host walking ids
      // to see what a database holds would otherwise have to catch.
      assertNull(conn.tableName(9999));
    }
  }
}
