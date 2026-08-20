package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Value;
import dev.zudb.ZuException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Building a database out of columns, which is the only way a table comes into being. */
class LoaderTest {

  @TempDir Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @Test
  void aTableOfTwoColumnsReadsBack() {
    Path path = dir.resolve("people.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      loader.column("id", 1L, 2L, 3L);
      loader.column("name", "ada", "grace", "alan");
      loader.finish();
      assertTrue(loader.isFinished());
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (p:Person) RETURN p.id, p.name ORDER BY p.id")) {
      List<Long> ids = new ArrayList<>();
      List<String> names = new ArrayList<>();
      r.forEach(
          row -> {
            ids.add(row.getLong(0));
            names.add(row.getString(1));
          });
      assertEquals(List.of(1L, 2L, 3L), ids);
      assertEquals(List.of("ada", "grace", "alan"), names);
    }
  }

  @Test
  void everyColumnKindGoesInAndComesBack() {
    Path path = dir.resolve("kinds.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Thing", "Near", 2);
      loader.column("n", 7L, 8L);
      loader.column("d", 1.5, 2.5);
      loader.column("ok", true, false);
      loader.column("s", "one", "two");
      loader.temporalColumn(
          "born",
          Value.Temporal.Kind.DATE,
          LocalDate.of(1815, 12, 10).toEpochDay(),
          LocalDate.of(1906, 12, 9).toEpochDay());
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r =
            conn.query("MATCH (t:Thing) RETURN t.n, t.d, t.ok, t.s, t.born ORDER BY t.n")) {
      assertEquals(2, r.rows());
      assertEquals(7L, r.row(0).getLong(0));
      assertEquals(1.5, r.row(0).getDouble(1));
      assertTrue(r.row(0).getBoolean(2));
      assertEquals("one", r.row(0).getString(3));
      assertEquals(LocalDate.of(1815, 12, 10), r.row(0).getTemporal(4).toLocalDate());
      assertEquals(8L, r.row(1).getLong(0));
      assertEquals(2.5, r.row(1).getDouble(1));
      assertFalse(r.row(1).getBoolean(2));
      assertEquals("two", r.row(1).getString(3));
      assertEquals(LocalDate.of(1906, 12, 9), r.row(1).getTemporal(4).toLocalDate());
    }
  }

  @Test
  void aDirectBufferIsReadWhereItLies() {
    // The same table filled from both shapes, once through an array that has
    // to be copied off-heap and once through a direct buffer that does not,
    // so the path this whole surface exists for is the one under test.
    Path path = dir.resolve("direct.zu");
    LongBuffer values =
        ByteBuffer.allocateDirect(4 * Long.BYTES).order(ByteOrder.nativeOrder()).asLongBuffer();
    values.put(new long[] {10L, 20L, 30L, 40L});
    values.flip();

    try (Loader loader = Loader.create(path)) {
      loader.table("Point", "Near", 4);
      loader.column("x", values);
      loader.column("y", 1L, 2L, 3L, 4L);
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (p:Point) RETURN sum(p.x), sum(p.y)")) {
      assertEquals(100L, r.row(0).getLong(0));
      assertEquals(10L, r.row(0).getLong(1));
    }
  }

  @Test
  void aSliceOfABufferIsTheSliceAndNotTheWholeThing() {
    Path path = dir.resolve("slice.zu");
    LongBuffer all = LongBuffer.wrap(new long[] {99L, 1L, 2L, 3L, 99L});
    all.position(1);
    all.limit(4);

    try (Loader loader = Loader.create(path)) {
      loader.table("Slice", "Near", 3);
      loader.column("v", all);
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (s:Slice) RETURN sum(s.v)")) {
      assertEquals(6L, r.row(0).getLong(0));
    }
  }

  @Test
  void edgesJoinRowsToRows() {
    Path path = dir.resolve("follows.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("User", "Follows", 3);
      loader.column("id", 1L, 2L, 3L);
      loader.edges(new int[] {0, 1}, new int[] {1, 2});
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r =
            conn.query("MATCH (a:User)-[:Follows]->(b:User) RETURN a.id, b.id ORDER BY a.id")) {
      assertEquals(2, r.rows());
      assertEquals(1L, r.row(0).getLong(0));
      assertEquals(2L, r.row(0).getLong(1));
      assertEquals(2L, r.row(1).getLong(0));
      assertEquals(3L, r.row(1).getLong(1));
    }
  }

  @Test
  void edgesAppendAcrossCalls() {
    Path path = dir.resolve("appended.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Node", "Link", 4);
      loader.column("id", 0L, 1L, 2L, 3L);
      loader.edges(new int[] {0}, new int[] {1});
      loader.edges(new int[] {1, 2}, new int[] {2, 3});
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (:Node)-[:Link]->(:Node) RETURN count(*)")) {
      assertEquals(3L, r.row(0).getLong(0));
    }
  }

  @Test
  void aColumnWithAValueMissingIsRefused() {
    Path path = dir.resolve("short.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      assertThrows(ZuException.class, () -> loader.column("id", 1L, 2L));
    }
  }

  @Test
  void anEdgeThatStartsSomewhereHasToEndSomewhere() {
    Path path = dir.resolve("lopsided.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Node", "Link", 2);
      ZuException e =
          assertThrows(ZuException.class, () -> loader.edges(new int[] {0, 1}, new int[] {1}));
      assertTrue(e.getMessage().contains("2 starts against 1 ends"), e.getMessage());
    }
  }

  @Test
  void aPathThatExistsIsRefused() throws Exception {
    Path path = dir.resolve("taken.zu");
    Files.writeString(path, "not a database");
    assertThrows(ZuException.class, () -> Loader.create(path));
  }

  @Test
  void aLoaderClosedWithoutFinishingWroteNothing() {
    Path path = dir.resolve("abandoned.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 1);
      loader.column("id", 1L);
      assertFalse(loader.isFinished());
    }
    // What is left is the empty file the loader created and nothing else. No
    // half of a table got in, and there is no Person to find.
    assertTrue(Files.exists(path));
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (p:Person) RETURN p.id")) {
      assertEquals(0, r.rows());
    }
  }

  @Test
  void aClosedLoaderSaysSoRatherThanCrashing() {
    Loader loader = Loader.create(dir.resolve("closed.zu"));
    loader.close();
    loader.close();
    assertThrows(ZuException.class, () -> loader.table("Person", "Knows", 1));
  }

  @Test
  void aStringColumnTakesWhatUtf8Takes() {
    Path path = dir.resolve("utf8.zu");
    List<String> names = List.of("", "ada", "éàü", "🐍", "a".repeat(300));
    try (Loader loader = Loader.create(path)) {
      loader.table("Name", "Alias", names.size());
      loader.column("s", names);
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (n:Name) RETURN n.s")) {
      assertEquals(
          new HashSet<>(names),
          r.stream().map(row -> row.getString(0)).collect(Collectors.toSet()));
    }
  }

  @Test
  void aStringColumnWithAHoleInItIsRefusedBeforeTheCall() {
    Path path = dir.resolve("hole.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Name", "Alias", 2);
      List<String> withHole = new ArrayList<>();
      withHole.add("ada");
      withHole.add(null);
      ZuException e = assertThrows(ZuException.class, () -> loader.column("s", withHole));
      assertTrue(e.getMessage().contains("row 1"), e.getMessage());
    }
  }
}
