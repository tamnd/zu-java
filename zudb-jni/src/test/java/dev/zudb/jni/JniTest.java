package dev.zudb.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Frame;
import dev.zudb.Result;
import dev.zudb.Statement;
import dev.zudb.ZuException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The JNI provider, end to end.
 *
 * <p>The behaviour every provider owes is checked by the suite both of them
 * run, and is not repeated here. What is here is the handful of things that
 * are true of this provider and of no other: that a string survives a
 * crossing JNI's own conversions would mangle, that a column is a window onto
 * the engine's memory rather than a copy, that a callback arriving on a
 * thread the JVM has never seen finds its way back into Java, and that a
 * frame refuses a buffer it could not keep.
 */
class JniTest {

  private static Database db;
  private static Connection conn;

  @BeforeAll
  static void engine() {
    Path library = locate();
    assumeTrue(library != null, "no libzu: set -Dzu.library to run these");
    System.setProperty("zu.library", library.toString());
    // Panama wins on priority wherever it runs, and this suite is about
    // the other one, so it is named rather than left to the contest.
    System.setProperty("zu.provider", "jni");
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

  /**
   * The reason every string in this provider is a byte array.
   *
   * <p>JNI's own {@code NewStringUTF} and {@code GetStringUTFChars} speak
   * modified UTF-8, in which a character outside the basic multilingual plane
   * is a surrogate pair in six bytes rather than the four the standard says.
   * Hand one of those to an engine that validates real UTF-8 and it is
   * refused; read one back through the same conversion and it comes out
   * mangled. This is the test that would fail the day somebody decided a
   * jstring would be tidier.
   */
  @Test
  void aStringOutsideTheBasicMultilingualPlaneCrossesIntact() {
    String[] awkward = {
      "ada", // plain
      "héllo wörld", // two bytes a character
      "日本語", // three
      "🜛 alchemy 🝗", // four, which is where modified UTF-8 goes wrong
      "👩‍💻", // a surrogate pair inside a longer sequence
    };
    for (String s : awkward) {
      try (Statement stmt = conn.prepare("RETURN $s AS echoed")) {
        try (Result r = stmt.bind("s", s).execute()) {
          assertEquals(s, r.row(0).getString(0), s);
        }
      }
    }
  }

  @Test
  void aColumnIsAWindowOntoTheEnginesOwnMemory() {
    try (Result r = conn.query("UNWIND [1, 2, 3, 4, 5] AS n RETURN n")) {
      LongBuffer column = r.longs(0);
      assertNotNull(column, "an integer column reads as a buffer");
      assertTrue(column.isDirect(), "and the buffer is the engine's, not a copy of it");
      assertEquals(5, column.remaining());
      long[] read = new long[5];
      column.get(read);
      assertArrayEquals(new long[] {1, 2, 3, 4, 5}, read);
    }
  }

  /**
   * A callback arrives on a thread of the engine's, which the JVM has never
   * seen, so the shim attaches it for the call and detaches after. A thread
   * that exits while attached takes the process with it, so a green run of
   * this test is also the check that it does not.
   */
  @Test
  void progressArrivesFromAThreadTheJvmHasNeverSeen() {
    long rows = 3000;
    ByteBuffer bytes = ByteBuffer.allocateDirect((int) rows * 8).order(ByteOrder.nativeOrder());
    LongBuffer ids = bytes.asLongBuffer();
    for (long i = 0; i < rows; i++) {
      ids.put((int) i, i);
    }
    AtomicLong seen = new AtomicLong();
    Set<Thread> threads = ConcurrentHashMap.newKeySet();
    Thread asked = Thread.currentThread();
    try (Frame frame = Frame.of("Person", rows)) {
      frame.column("id", ids);
      conn.register(frame);
      conn.onProgress(
          Duration.ofMillis(1),
          (read, millis) -> {
            seen.incrementAndGet();
            threads.add(Thread.currentThread());
            return true;
          });
      try (Result r =
          conn.query("MATCH (a:Person), (b:Person) WHERE a.id < b.id RETURN count(*)")) {
        assertTrue(r.row(0).getLong(0) > 0);
      } finally {
        conn.clearProgress();
        conn.unregister("Person");
      }
    }
    assertTrue(seen.get() > 0, "a cross product of nine million rows went by without a word");
    assertFalse(threads.contains(asked), "the callback ran on the thread that asked");
  }

  /**
   * A frame keeps the pointer it is given for as long as it is registered,
   * and a heap buffer has no pointer anything outside the JVM can keep. A
   * copy would be a frame that is not a frame, so it is refused instead.
   */
  @Test
  void aFrameRefusesABufferOnTheHeap() {
    try (Frame frame = Frame.of("Heaped", 2)) {
      ZuException e =
          assertThrows(
              ZuException.class,
              () -> frame.column("n", ByteBuffer.allocate(16).asLongBuffer()));
      assertTrue(e.getMessage().contains("allocateDirect"), e.getMessage());
    }
  }

  @Test
  void aFrameOverDirectMemoryIsQueriedWhereItLies() {
    ByteBuffer bytes = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
    LongBuffer values = bytes.asLongBuffer();
    values.put(new long[] {7, 8, 9}).flip();
    try (Frame frame = Frame.of("Lent", 3)) {
      frame.column("n", values);
      conn.register(frame);
      try (Result r = conn.query("MATCH (l:Lent) RETURN l.n AS n ORDER BY n")) {
        assertEquals(List.of(7L, 8L, 9L), r.stream().map(row -> row.getLong(0)).toList());
      } finally {
        conn.unregister("Lent");
      }
    }
  }

  private static Path locate() {
    String named = System.getProperty("zu.library");
    if (named == null || named.isBlank()) {
      named = System.getenv("ZU_LIBRARY");
    }
    if (named != null && !named.isBlank()) {
      Path p = Paths.get(named);
      return Files.isRegularFile(p) ? p : null;
    }
    String name = System.mapLibraryName("zu");
    Path here = Paths.get("").toAbsolutePath();
    for (Path root = here; root != null; root = root.getParent()) {
      for (String sibling : new String[] {"zu", "zu-dx", "zu-g0"}) {
        Path candidate = root.resolveSibling(sibling).resolve("target/release").resolve(name);
        if (Files.isRegularFile(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }
}
