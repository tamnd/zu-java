package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Chunk;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Result;
import dev.zudb.ZuProgrammingException;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The columnar reads, which are the reason to use this client over a socket.
 *
 * <p>Every buffer here is a view over the engine's own memory. What these
 * tests are for is that it is the right memory, in the right order, and that
 * it is read-only so that nobody writes into the result by accident.
 */
public class ColumnarTest {

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
  void aWholeColumnOfIntegersInOneCall() {
    try (Result r = conn.query("UNWIND [1, 2, 3, 4, 5] AS v RETURN v")) {
      LongBuffer b = r.longs(0);
      assertEquals(5, b.remaining());
      long total = 0;
      for (int i = 0; i < b.remaining(); i++) {
        total += b.get(i);
      }
      assertEquals(15, total);
    }
  }

  @Test
  void aWholeColumnOfFloats() {
    try (Result r = conn.query("UNWIND [1.5, 2.5] AS v RETURN v")) {
      DoubleBuffer b = r.doubles(0);
      assertEquals(2, b.remaining());
      assertEquals(1.5, b.get(0));
      assertEquals(2.5, b.get(1));
    }
  }

  @Test
  void integersReadAsFloatsAndBooleansReadAsIntegers() {
    try (Result r = conn.query("UNWIND [1, 2] AS v RETURN v")) {
      assertEquals(1.0, r.doubles(0).get(0));
    }
    try (Result r = conn.query("UNWIND [true, false] AS v RETURN v")) {
      assertEquals(1, r.longs(0).get(0));
      assertEquals(0, r.longs(0).get(1));
    }
  }

  @Test
  void nullsReadAsZeroAndValidityTellsThemApart() {
    try (Result r = conn.query("UNWIND [1, null, 3] AS v RETURN v")) {
      LongBuffer values = r.longs(0);
      ByteBuffer valid = r.valid(0);
      assertEquals(3, values.remaining());
      assertEquals(3, valid.remaining());
      assertEquals(1, values.get(0));
      assertEquals(0, values.get(1));
      assertEquals(3, values.get(2));
      assertTrue(valid.get(0) != 0);
      assertEquals(0, valid.get(1));
      assertTrue(valid.get(2) != 0);
    }
  }

  @Test
  void aBorrowedBufferIsReadOnlySoNobodyWritesIntoTheResult() {
    try (Result r = conn.query("UNWIND [1, 2] AS v RETURN v")) {
      LongBuffer b = r.longs(0);
      assertTrue(b.isReadOnly());
      assertThrows(java.nio.ReadOnlyBufferException.class, () -> b.put(0, 99));
      assertTrue(r.valid(0).isReadOnly());
    }
  }

  @Test
  void aColumnThatDoesNotHoldWhatTheAccessorReadsIsRefused() {
    try (Result r = conn.query("UNWIND ['a', 'b'] AS v RETURN v")) {
      // A string column is not an integer column, and reading it as one
      // would hand back a pointer as a number.
      assertThrows(RuntimeException.class, () -> r.longs(0));
    }
  }

  @Test
  void aColumnOffTheEndIsRefusedBeforeTheCall() {
    try (Result r = conn.query("UNWIND [1] AS v RETURN v")) {
      assertThrows(ZuProgrammingException.class, () -> r.longs(1));
      assertThrows(ZuProgrammingException.class, () -> r.valid(-1));
    }
  }

  @Test
  void anEmptyResultBorrowsNothingAndSaysSoAsAnEmptyBuffer() {
    try (Result r = conn.query("UNWIND [] AS v RETURN v")) {
      assertEquals(0, r.longs(0).remaining());
      assertEquals(0, r.doubles(0).remaining());
      assertEquals(0, r.valid(0).remaining());
      assertEquals(0, r.chunkCount());
      assertEquals(0, r.chunks().count());
    }
  }

  @Test
  void theChunksCoverEveryRowExactlyOnce() {
    try (Result r = conn.query(unwind(3000))) {
      assertEquals(3000, r.rows());
      List<Long> seen = new ArrayList<>();
      long expectedOffset = 0;
      for (Chunk c : r.chunks().toList()) {
        assertEquals(expectedOffset, c.offset());
        LongBuffer b = c.longs(0);
        for (int i = 0; i < c.rows(); i++) {
          seen.add(b.get(i));
        }
        expectedOffset += c.rows();
      }
      assertEquals(3000, seen.size());
      assertEquals(1L, seen.get(0));
      assertEquals(3000L, seen.get(2999));
      assertEquals(3000, expectedOffset);
    }
  }

  @Test
  void aChunkRowIsTheSameValueAsTheRowItsOffsetNames() {
    try (Result r = conn.query(unwind(100))) {
      Chunk first = r.chunk(0);
      assertEquals(first.longs(0).get(0), first.row(0).getLong(0));
      assertEquals(r.row(first.offset()).getLong(0), first.longs(0).get(0));
    }
  }

  @Test
  void aChunkOffTheEndIsRefused() {
    try (Result r = conn.query("UNWIND [1] AS v RETURN v")) {
      assertThrows(ZuProgrammingException.class, () -> r.chunk(r.chunkCount()));
      assertThrows(ZuProgrammingException.class, () -> r.chunk(-1));
    }
  }

  @Test
  void aWholeColumnAndAChunkOfItAgree() {
    try (Result r = conn.query(unwind(500))) {
      LongBuffer whole = r.longs(0);
      Chunk c = r.chunk(0);
      LongBuffer part = c.longs(0);
      for (int i = 0; i < c.rows(); i++) {
        assertEquals(whole.get((int) c.offset() + i), part.get(i));
      }
    }
  }

  /** A statement that answers with the numbers one to n, one a row. */
  private static String unwind(int n) {
    StringBuilder sb = new StringBuilder("UNWIND [");
    for (int i = 1; i <= n; i++) {
      if (i > 1) {
        sb.append(", ");
      }
      sb.append(i);
    }
    return sb.append("] AS v RETURN v").toString();
  }
}
