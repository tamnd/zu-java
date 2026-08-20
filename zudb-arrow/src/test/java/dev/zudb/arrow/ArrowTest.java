package dev.zudb.arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Statement;
import dev.zudb.Value;
import dev.zudb.ZuClosedException;
import dev.zudb.ZuException;
import dev.zudb.ZuProgrammingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A result crossing into Arrow, over the C Data Interface.
 *
 * <p>What these are for is that the values arrive intact, that the batching is
 * what was asked for, and that the result is spent exactly when the engine
 * spends it: after an export, whether the export worked or not, and not before
 * a call this client refused on its own.
 *
 * <p>The allocator is closed after every test, which fails the test if a buffer
 * was left behind, so leaking the stream or the reader is caught here rather
 * than showing up as memory a long-running program never gets back.
 */
class ArrowTest {

  @TempDir Path dir;

  private BufferAllocator allocator;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  @BeforeEach
  void allocator() {
    allocator = new RootAllocator();
  }

  @AfterEach
  void balanced() {
    // Closing a RootAllocator with anything outstanding throws, so this is
    // the assertion that every reader above released what it was handed.
    allocator.close();
  }

  @Test
  void aStoredColumnCrossesAsArrow() throws Exception {
    Path path = dir.resolve("people.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      loader.column("id", 1L, 2L, 3L);
      loader.column("name", "ada", "grace", "alan");
      loader.finish();
    }

    // A projection of stored values with nothing above it, which is the plan
    // whose columns the executor fills. The arrays that cross are those
    // buffers, so nothing here is proportional to the row count.
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        ArrowReader reader =
            Arrow.query(allocator, conn, "MATCH (p:Person) RETURN p.id AS id, p.name AS name")) {
      VectorSchemaRoot root = reader.getVectorSchemaRoot();
      assertEquals(List.of("id", "name"), root.getSchema().getFields().stream().map(f -> f.getName()).toList());

      List<Long> ids = new ArrayList<>();
      List<String> names = new ArrayList<>();
      while (reader.loadNextBatch()) {
        BigIntVector id = (BigIntVector) root.getVector("id");
        VarCharVector name = (VarCharVector) root.getVector("name");
        for (int i = 0; i < root.getRowCount(); i++) {
          ids.add(id.get(i));
          names.add(new String(name.get(i), StandardCharsets.UTF_8));
        }
      }
      assertEquals(List.of(1L, 2L, 3L), ids);
      assertEquals(List.of("ada", "grace", "alan"), names);
    }
  }

  @Test
  void aRowBuiltResultCrossesThroughTheFallback() throws Exception {
    Path path = dir.resolve("ordered.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      loader.column("id", 2L, 3L, 1L);
      loader.finish();
    }

    // An ORDER BY leaves the engine with rows rather than columns, so this is
    // the other path out: read into buffers on the way rather than handing
    // over ones that already existed. Same answer, and the caller cannot tell
    // which happened except by timing it.
    try (Database db = Database.open(path);
        Connection conn = db.connect();
        ArrowReader reader =
            Arrow.query(allocator, conn, "MATCH (p:Person) RETURN p.id AS id ORDER BY p.id")) {
      List<Long> ids = new ArrayList<>();
      while (reader.loadNextBatch()) {
        BigIntVector id = (BigIntVector) reader.getVectorSchemaRoot().getVector("id");
        for (int i = 0; i < id.getValueCount(); i++) {
          ids.add(id.get(i));
        }
      }
      assertEquals(List.of(1L, 2L, 3L), ids);
    }
  }

  @Test
  void aNullIsANullAndNotAZero() throws Exception {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        ArrowReader reader =
            Arrow.query(allocator, conn, "UNWIND [1, null, 3] AS n RETURN n, n * 1.5 AS f")) {
      long batches = 0;
      while (reader.loadNextBatch()) {
        batches++;
        VectorSchemaRoot root = reader.getVectorSchemaRoot();
        BigIntVector n = (BigIntVector) root.getVector("n");
        Float8Vector f = (Float8Vector) root.getVector("f");
        assertEquals(3, root.getRowCount());
        assertEquals(1L, n.get(0));
        assertTrue(n.isNull(1), "the validity bitmap is the only place a null lives");
        assertEquals(3L, n.get(2));
        assertEquals(1.5, f.get(0));
        assertTrue(f.isNull(1));
        assertEquals(4.5, f.get(2));
      }
      assertEquals(1, batches);
    }
  }

  @Test
  void aStringColumnArrivesAsUtf8WithItsHolesIntact() throws Exception {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        ArrowReader reader =
            Arrow.query(allocator, conn, "UNWIND ['ada', null, '🐍'] AS s RETURN s")) {
      List<String> strings = new ArrayList<>();
      while (reader.loadNextBatch()) {
        VarCharVector s = (VarCharVector) reader.getVectorSchemaRoot().getVector("s");
        for (int i = 0; i < s.getValueCount(); i++) {
          strings.add(s.isNull(i) ? null : new String(s.get(i), StandardCharsets.UTF_8));
        }
      }
      assertEquals(java.util.Arrays.asList("ada", null, "🐍"), strings);
    }
  }

  @Test
  void aNodeColumnNamesItsTableOutOfTheCatalogTheConnectionHolds() throws Exception {
    Path path = dir.resolve("nodes.zu");
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 2);
      loader.column("id", 1L, 2L);
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        ArrowReader reader = Arrow.query(allocator, conn, "MATCH (p:Person) RETURN p AS n")) {
      List<String> tables = new ArrayList<>();
      while (reader.loadNextBatch()) {
        StructVector n = (StructVector) reader.getVectorSchemaRoot().getVector("n");
        VarCharVector table = n.getChild("table", VarCharVector.class);
        for (int i = 0; i < n.getValueCount(); i++) {
          tables.add(new String(table.get(i), StandardCharsets.UTF_8));
        }
      }
      assertEquals(List.of("Person", "Person"), tables);
    }
  }

  @Test
  void theBatchSizeIsWhatTheConsumerAskedFor() throws Exception {
    Path path = dir.resolve("many.zu");
    long[] ids = new long[3000];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = i;
    }
    try (Loader loader = Loader.create(path)) {
      loader.table("Row", "Near", ids.length);
      loader.column("id", ids);
      loader.finish();
    }

    try (Database db = Database.open(path);
        Connection conn = db.connect();
        Result r = conn.query("MATCH (x:Row) RETURN x.id AS id");
        ArrowReader reader = Arrow.reader(allocator, r, 1000)) {
      List<Integer> sizes = new ArrayList<>();
      long total = 0;
      while (reader.loadNextBatch()) {
        VectorSchemaRoot root = reader.getVectorSchemaRoot();
        sizes.add(root.getRowCount());
        BigIntVector id = (BigIntVector) root.getVector("id");
        for (int i = 0; i < root.getRowCount(); i++) {
          total += id.get(i);
        }
      }
      // Three batches of a thousand, and the batches are slices of arrays
      // that were already in memory rather than three copies of anything.
      assertEquals(List.of(1000, 1000, 1000), sizes);
      assertEquals(3000L * 2999 / 2, total);
    }
  }

  @Test
  void aResultWithNoRowsCrossesAsOneEmptyBatch() throws Exception {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        ArrowReader reader = Arrow.query(allocator, conn, "UNWIND [] AS v RETURN v")) {
      assertEquals(1, reader.getVectorSchemaRoot().getSchema().getFields().size());
      // One batch of nothing rather than nothing at all, so that a consumer
      // reading batches and not the schema still learns what the columns were
      // going to be.
      assertTrue(reader.loadNextBatch());
      assertEquals(0, reader.getVectorSchemaRoot().getRowCount());
      assertFalse(reader.loadNextBatch());
    }
  }

  @Test
  void theExportSpendsTheResult() throws Exception {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      Result r = conn.query("UNWIND [1, 2] AS v RETURN v");
      assertFalse(r.isClosed());
      try (ArrowReader reader = Arrow.reader(allocator, r)) {
        assertTrue(r.isClosed(), "the buffers have left, so there is nothing here to read again");
        assertThrows(ZuClosedException.class, () -> r.row(0).getLong(0));
        // Closing it is still the right shape to write, and still a no-op.
        r.close();
        assertTrue(reader.loadNextBatch());
        assertEquals(2, reader.getVectorSchemaRoot().getRowCount());
      }
    }
  }

  @Test
  void aStreamThatIsNowhereIsRefusedBeforeTheEngineSeesIt() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Result r = conn.query("UNWIND [1, 2] AS v RETURN v")) {
      assertThrows(ZuProgrammingException.class, () -> r.exportArrow(0));
      assertThrows(ZuProgrammingException.class, () -> r.exportArrow(1024, -1));
      // Nothing was handed over, so nothing was spent, and the result is
      // still there to read the ordinary way.
      assertFalse(r.isClosed());
      assertEquals(1L, r.row(0).getLong(0));
    }
  }

  @Test
  void aColumnArrowHasNoTypeForIsRefusedAndSpendsTheResult() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Statement stmt = conn.prepare("RETURN $v AS v")) {
      // A time of day with an offset and no date to apply it to. Arrow has a
      // timestamp with a zone and a time without one, and nothing in between,
      // so this is a column that cannot cross and the refusal names it.
      stmt.bind("v", Value.Temporal.Kind.ZONED_TIME, 45_296_000_000_000L, 420);
      Result r = stmt.execute();
      ZuException e = assertThrows(ZuException.class, () -> Arrow.reader(allocator, r));
      assertTrue(e.getMessage().contains("v"), e.getMessage());
      // The engine nulls the result on every path it takes, this one
      // included, so a caller who fixes the statement cannot hand the same
      // handle over twice.
      assertTrue(r.isClosed());
      r.close();
    }
  }

  @Test
  void aClosedResultIsRefusedRatherThanExported() {
    try (Database db = Database.memory();
        Connection conn = db.connect()) {
      Result r = conn.query("UNWIND [1] AS v RETURN v");
      r.close();
      assertThrows(ZuClosedException.class, () -> Arrow.reader(allocator, r));
    }
  }

  @Test
  void theSchemaSaysWhatTheColumnsHold() throws Exception {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        ArrowReader reader =
            Arrow.query(allocator, conn, "UNWIND [1] AS n RETURN n, 1.5 AS f, 'a' AS s, true AS b")) {
      List<ArrowType> types =
          reader.getVectorSchemaRoot().getSchema().getFields().stream()
              .map(f -> f.getType())
              .toList();
      assertEquals(
          List.of(
              new ArrowType.Int(64, true),
              new ArrowType.FloatingPoint(
                  org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE),
              ArrowType.Utf8.INSTANCE,
              ArrowType.Bool.INSTANCE),
          types);
    }
  }
}
