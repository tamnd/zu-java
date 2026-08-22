package dev.zudb.tck;

import dev.zudb.Appender;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Frame;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Statement;
import dev.zudb.Zu;
import dev.zudb.ZuException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every handle this client hands out, opened and closed until an allocator has
 * something to say about it.
 *
 * <p>This is a program rather than a test, because the thing that reads the
 * result is not an assertion, it is the leak checker the process was started
 * under. {@code scripts/leaks.sh} runs this with the sanitizer's allocator
 * interposed and then reads the report for blocks the engine allocated and
 * nobody gave back. A run of this on its own, printing its last line and
 * exiting zero, has said nothing at all.
 *
 * <p>What it is written against is the shape a leak has in a binding, which is
 * not the shape it has in a library. A handle here is memory the engine owns
 * and a Java object holds, and the way it goes missing is a close that was
 * never reached: a failure that returned before it, a value the engine refused
 * halfway through a row, a statement that threw where a result was being
 * built. So the failures run beside the successes below, because the happy
 * path is the one already covered by two hundred other tests.
 *
 * <p>There is no {@code Cleaner} in this client and nothing waits for a
 * collector, which is a decision the API documents rather than an oversight: a
 * handle that is dropped stays open until the process ends. That is what makes
 * this apparatus the only thing that can see the mistake, and it is what makes
 * the gate worth having. Run with {@code ZU_LEAK_GATE=1} and this drops one of
 * everything on the floor on purpose, so that a clean report from a run which
 * never interposed the allocator cannot pass for a clean report from one that
 * did.
 */
public final class Leaks {

  /** How many times round, when nobody says. Enough to be seen, quick enough to run. */
  private static final int ROUNDS = 25;

  private Leaks() {}

  public static void main(String[] args) throws IOException {
    int rounds = args.length > 0 ? Integer.parseInt(args[0]) : ROUNDS;
    Path dir = Files.createTempDirectory("zu-leaks");
    try {
      System.out.println("zu " + Zu.version() + " through the " + Zu.provider() + " provider");
      if (gate()) {
        drop(dir.resolve("gate.zu1"));
        System.out.println("one of everything dropped on the floor, which the report should say");
        return;
      }
      for (int round = 0; round < rounds; round++) {
        whereItLies();
        onDisk(dir.resolve("round" + round + ".zu1"));
        whatFails(dir.resolve("failing" + round + ".zu1"));
      }
      System.out.println(rounds + " rounds, every handle closed");
    } finally {
      remove(dir);
    }
  }

  /**
   * A database with no file under it and a frame the engine reads where it
   * lies, which is the shortest life any handle here has.
   */
  private static void whereItLies() {
    try (Database db = Database.memory();
        Connection conn = db.connect();
        Frame frame = Frame.of("Person", 3)) {
      frame.column("id", longs(1, 2, 3));
      conn.register(frame);
      try (Result r = conn.query("MATCH (p:Person) RETURN p.id AS id ORDER BY id")) {
        // A row at a time, a column at a time and a chunk at a time, because
        // the three take different paths out of the engine and only one of
        // them is a copy.
        long total = 0;
        for (var row : r) {
          total += row.getLong(0);
        }
        LongBuffer values = r.longs(0);
        ByteBuffer valid = r.valid(0);
        for (int i = 0; i < values.remaining(); i++) {
          total += valid.get(i) != 0 ? values.get(i) : 0;
        }
        r.chunks().forEach(chunk -> chunk.longs(0));
        if (total != 12) {
          throw new AssertionError("the engine answered " + total + " rather than 12");
        }
      }
      conn.unregister("Person");
    }
  }

  /** A database on a disk, and the handles that only exist for one. */
  private static void onDisk(Path path) {
    people(path);
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      try (Statement stmt = conn.prepare("MATCH (p:Person) WHERE p.id = $id RETURN p.name AS n")) {
        for (long id = 1; id <= 3; id++) {
          try (Result r = stmt.bind("id", id).execute()) {
            r.rows();
          }
        }
      }
      try (Appender rows = conn.appender("Person")) {
        rows.append(4L).append("hedy").endRow();
        rows.append(5L).append("katherine").endRow();
        rows.finish();
      }
      // An appender closed without being finished, and one told to throw
      // away what it has, because the three endings free different things.
      try (Appender rows = conn.appender("Person")) {
        rows.append(6L).append("mary").endRow();
      }
      try (Appender rows = conn.appender("Person")) {
        rows.append(7L).append("dorothy").endRow();
        rows.discard();
      }
      conn.transaction(
          () -> {
            try (Result r = conn.query("MATCH (p:Person) RETURN p.id AS id")) {
              r.rows();
            }
          });
      try (Connection second = conn.duplicate();
          Result r = second.query("MATCH (p:Person) RETURN p.name AS name")) {
        r.rows();
      }
    }
  }

  /**
   * The same handles, on the paths that never reach the end.
   *
   * <p>This is where a leak lives. A statement that fails has allocated on the
   * way to failing, a value the engine refuses has half a row behind it, and a
   * caller who catches all of that never reaches the line that would have
   * freed anything.
   */
  private static void whatFails(Path path) {
    people(path);
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      raises(() -> conn.execute("MATCH (p:Person) RETRUN p.id"));
      raises(() -> conn.execute("RETURN nobody"));
      raises(() -> conn.prepare("MATCH (p:Person RETURN p"));
      try (Result r = conn.query("MATCH (p:Person) RETURN p.id AS id, p.name AS name")) {
        raises(() -> r.longs(1));
        raises(() -> r.row(99));
        raises(() -> r.row(0).getLong("nope"));
      }
      // A fresh appender for each of these, because an appender that has
      // already refused a value is not the state the next one is testing.
      try (Appender rows = conn.appender("Person")) {
        raises(() -> rows.append("four").append("hedy").endRow());
      }
      try (Appender rows = conn.appender("Person")) {
        raises(() -> rows.row(4L, List.of("hedy")));
      }
      try (Appender rows = conn.appender("Person")) {
        raises(() -> rows.append(4L).endRow().flush());
      }
      raises(() -> Database.open(path.resolveSibling("never-was-a-database.zu1")).close());
    }
  }

  /**
   * One of everything, opened and never closed.
   *
   * <p>Nothing below is a mistake this client would make. It is the mistake a
   * user makes, written down once, so that the apparatus which is meant to
   * catch it can be seen catching it.
   */
  private static void drop(Path path) {
    people(path);
    Database db = Database.open(path);
    Connection conn = db.connect();
    Statement stmt = conn.prepare("MATCH (p:Person) RETURN p.id AS id");
    Result result = stmt.execute();
    Appender rows = conn.appender("Person");
    rows.append(9L).append("gate").endRow();
    Frame frame = Frame.of("Held", 1);
    frame.column("id", longs(1));
    conn.register(frame);
    // Every one of those is still open, and this client has no cleaner, so
    // none of it is coming back. The condition is here so that a compiler
    // cannot decide any of the above was work nobody wanted.
    if (result.rows() < 0 || db.isClosed() || stmt.isClosed() || rows.isFinished()
        || frame.isClosed()) {
      throw new AssertionError("unreachable, and here to keep the handles above alive");
    }
  }

  /** Three people, in a database that did not exist a moment ago. */
  private static void people(Path path) {
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      loader.column("id", 1L, 2L, 3L);
      loader.column("name", "ada", "grace", "lynn");
      loader.finish();
    }
  }

  /** Runs a call that is here because it fails, and insists that it failed. */
  private static void raises(Runnable wrong) {
    try {
      wrong.run();
    } catch (ZuException expected) {
      return;
    }
    throw new AssertionError("a call that is here because it fails did not fail");
  }

  /** A direct buffer of longs, which is the only kind a frame reads in place. */
  private static LongBuffer longs(long... values) {
    LongBuffer buffer =
        ByteBuffer.allocateDirect(values.length * 8).order(ByteOrder.nativeOrder()).asLongBuffer();
    buffer.put(values).flip();
    return buffer;
  }

  /** Whether this is the run that is meant to leak. */
  private static boolean gate() {
    String asked = System.getenv("ZU_LEAK_GATE");
    return asked != null && !asked.isBlank() && !asked.equals("0");
  }

  /** The temporary directory, and everything under it. */
  private static void remove(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(each);
      }
    }
  }
}
