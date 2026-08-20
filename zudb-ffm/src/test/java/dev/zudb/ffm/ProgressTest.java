package dev.zudb.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Frame;
import dev.zudb.Progress;
import dev.zudb.ZuException;
import dev.zudb.ZuInterruptedException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Being told how a statement is getting on, and telling it to stop.
 *
 * <p>Every test here needs a statement that runs long enough to be reported
 * on, which frames make harder rather than easier: a scan of ten million rows
 * is ten milliseconds. So what is watched here is a nested loop over three
 * thousand rows against themselves, which is a third of a second and is not
 * something the planner can fold into a count.
 */
class ProgressTest {

  private static final int ROWS = 3_000;

  /**
   * Nine million pairs, which is what there is to watch.
   *
   * <p>A plain scan of a frame is too fast to be reported on at all, which is
   * a nice problem to have and an awkward one to write a test against. A pair
   * of patterns with a predicate over them is a nested loop the planner cannot
   * fold into a count, and three thousand rows of it is a third of a second.
   */
  private static final String SLOW =
      "MATCH (a:Person), (b:Person) WHERE a.id < b.id RETURN count(*)";

  private static Database db;
  private static Connection conn;
  private static Frame frame;

  @BeforeAll
  static void engine() {
    Libzu.require();
    LongBuffer ids =
        ByteBuffer.allocateDirect(ROWS * Long.BYTES).order(ByteOrder.nativeOrder()).asLongBuffer();
    for (int i = 0; i < ROWS; i++) {
      ids.put(i);
    }
    ids.flip();
    db = Database.memory();
    conn = db.connect();
    frame = Frame.of("Person", ROWS);
    frame.column("id", ids);
    conn.register(frame);
  }

  @AfterAll
  static void done() {
    conn.close();
    frame.close();
    db.close();
  }

  @BeforeEach
  void quiet() {
    conn.clearProgress();
  }

  @Test
  void theWatcherIsToldHowFarTheStatementHasGot() {
    List<long[]> calls = new CopyOnWriteArrayList<>();
    conn.onProgress(
        Duration.ofMillis(1),
        (rows, millis) -> {
          calls.add(new long[] {rows, millis});
          return true;
        });
    conn.query(SLOW).close();
    assertFalse(calls.isEmpty(), "a third of a second went by without a word");
    long rows = 0;
    long millis = 0;
    for (long[] call : calls) {
      assertTrue(call[0] >= rows, "the rows read went backwards");
      assertTrue(call[1] >= millis, "the clock went backwards");
      rows = call[0];
      millis = call[1];
    }
    assertTrue(rows > 0, "the rows read never moved off nought");
  }

  @Test
  void theWatcherRunsOnAThreadOfTheLibrarys() {
    Thread asked = Thread.currentThread();
    List<Thread> threads = new CopyOnWriteArrayList<>();
    conn.onProgress(
        Duration.ofMillis(1),
        (rows, millis) -> {
          threads.add(Thread.currentThread());
          return true;
        });
    conn.query(SLOW).close();
    assertFalse(threads.isEmpty());
    for (Thread thread : threads) {
      assertFalse(thread == asked, "the callback ran on the thread that asked for the statement");
    }
  }

  @Test
  void aWatcherThatSaysNoStopsTheStatement() {
    conn.onProgress(Duration.ofMillis(1), (rows, millis) -> false);
    assertThrows(ZuInterruptedException.class, () -> conn.query(SLOW).close());
  }

  @Test
  void theConnectionRunsTheNextStatementNormallyAfterOneWasStopped() {
    conn.onProgress(Duration.ofMillis(1), (rows, millis) -> false);
    assertThrows(ZuInterruptedException.class, () -> conn.query(SLOW).close());
    conn.clearProgress();
    assertEquals(1L, one("RETURN 1 AS v"));
  }

  @Test
  void aWatcherThatThrowsStopsTheStatementRatherThanTheJvm() {
    // An exception crossing an upcall would take the JVM down, so the binding
    // catches it, logs it and answers as though the watcher had asked for the
    // statement to stop. The one thrown here carries no stack trace, because
    // printing one takes a fair share of the scan being watched and a test
    // should not be timing the logger.
    conn.onProgress(Duration.ofMillis(1), (rows, millis) -> {
      throw new Quiet();
    });
    assertThrows(ZuInterruptedException.class, () -> conn.query(SLOW).close());
  }

  @Test
  void takingTheArrangementBackStopsTheCalls() {
    AtomicInteger calls = new AtomicInteger();
    conn.onProgress(
        Duration.ofMillis(1),
        (rows, millis) -> {
          calls.incrementAndGet();
          return true;
        });
    conn.query(SLOW).close();
    assertTrue(calls.get() > 0);
    conn.clearProgress();
    int seen = calls.get();
    conn.query(SLOW).close();
    assertEquals(seen, calls.get(), "the watcher was called after it was taken back");
  }

  @Test
  void anArrangementIsReplacedRatherThanAddedTo() {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    conn.onProgress(Duration.ofMillis(1), counting(first));
    conn.onProgress(Duration.ofMillis(1), counting(second));
    conn.query(SLOW).close();
    assertEquals(0, first.get(), "the watcher that was replaced was called anyway");
    assertTrue(second.get() > 0);
  }

  @Test
  void anIntervalOfNothingIsRefused() {
    assertThrows(
        ZuException.class, () -> conn.onProgress(Duration.ZERO, (rows, millis) -> true));
  }

  @Test
  void anIntervalLongerThanTheStatementMeansNothingIsSaidBeforeItEnds() {
    AtomicInteger calls = new AtomicInteger();
    conn.onProgress(Duration.ofSeconds(30), counting(calls));
    assertEquals(1L, one("RETURN 1 AS v"));
    assertEquals(0, calls.get());
  }

  @Test
  void aConnectionThatCloseWithAWatcherOnItLetsGoOfIt() {
    // Nothing to assert but that this does not crash: the stub outlives the
    // connection and something has to free it.
    try (Connection other = db.connect()) {
      other.onProgress(Duration.ofMillis(1), (rows, millis) -> true);
    }
    Connection another = db.connect();
    another.onProgress(Duration.ofMillis(5), (rows, millis) -> true);
    another.close();
    assertEquals(1L, one("RETURN 1 AS v"));
  }

  private static Progress counting(AtomicInteger calls) {
    return (rows, millis) -> {
      calls.incrementAndGet();
      return true;
    };
  }

  /** An exception that costs nothing to log. */
  private static final class Quiet extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Quiet() {
      super("the program has stopped wanting this answer", null, false, false);
    }
  }

  private static long one(String statement) {
    try (var r = conn.query(statement)) {
      return r.row(0).getLong(0);
    }
  }
}
