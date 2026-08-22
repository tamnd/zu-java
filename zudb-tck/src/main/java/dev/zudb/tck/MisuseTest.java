package dev.zudb.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.zudb.Appender;
import dev.zudb.Config;
import dev.zudb.Connection;
import dev.zudb.Database;
import dev.zudb.Frame;
import dev.zudb.Loader;
import dev.zudb.Result;
import dev.zudb.Statement;
import dev.zudb.ZuClosedException;
import dev.zudb.ZuConnectionException;
import dev.zudb.ZuException;
import dev.zudb.ZuProgrammingException;
import dev.zudb.ZuSyntaxException;
import dev.zudb.ZuTransactionException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.io.TempDir;

/**
 * Programs that are wrong, and what each one is told.
 *
 * <p>Every case here is a mistake somebody makes: a path that is not a
 * database, a handle used after it closed, a column read as the type it is
 * not, a row with the wrong number of values in it. The bar for all of them is
 * the same and it has three parts. The process does not die. Nothing is left
 * open behind it. And the message names what the caller did, in their own
 * terms, rather than naming a C function they have never heard of.
 *
 * <p>That third part is the reason this file asserts on message text at all.
 * Asserting on the class alone would pass for a client that answers every
 * mistake with the same sentence, and a sentence like {@code zu_result_col_i64
 * answered MISUSE} is exactly the sentence that passes. The text is matched as
 * a substring, because most of these carry a temporary path through them, and
 * the substring is chosen to be the part a reader would act on.
 *
 * <p>The second half of the file is the lifecycle half: hundreds of failures
 * in a row, and a count of open file descriptors either side of them. A client
 * that leaks a handle per failed open is a client that works in a test and
 * falls over in a server, and no single wrong program catches that.
 */
public class MisuseTest {

  @TempDir Path dir;

  @BeforeAll
  static void engine() {
    Libzu.require();
  }

  /** One wrong program, what it should raise, and what it should say. */
  private record Wrong(
      String what, Class<? extends Throwable> raises, String says, ThrowingConsumer<Path> run) {}

  private static final List<Wrong> WRONG =
      List.of(
          // Paths, and files that are not databases.
          new Wrong(
              "opening a database that is not there",
              ZuConnectionException.class,
              "gone.zu1",
              where -> Database.open(where.resolve("gone.zu1")).close()),
          new Wrong(
              "creating a database where one already is",
              ZuException.class,
              "twice.zu1",
              where -> {
                people(where.resolve("twice.zu1"));
                Database.create(where.resolve("twice.zu1")).close();
              }),
          new Wrong(
              "opening a file too small to hold a header",
              ZuConnectionException.class,
              "too short to be a zu1 database",
              where -> {
                Path path = where.resolve("small.zu1");
                Files.writeString(path, "not a database at all", StandardCharsets.UTF_8);
                Database.open(path).close();
              }),
          new Wrong(
              "opening a file that is not a database",
              ZuConnectionException.class,
              "bad magic, not a zu1 file",
              where -> {
                Path path = where.resolve("big.zu1");
                Files.write(path, new byte[4096]);
                Database.open(path).close();
              }),
          new Wrong(
              "opening a database named by nothing at all",
              NullPointerException.class,
              "path",
              where -> Database.open((Path) null).close()),

          // A connection that refuses writes.
          new Wrong(
              "writing through a read-only connection",
              ZuException.class,
              "which is open read-only",
              where -> {
                Path path = people(where.resolve("reader.zu1"));
                try (Database db = Database.open(path, Config.defaults().withReadOnly(true));
                    Connection conn = db.connect()) {
                  conn.execute("INSERT (:Person {id: 4, name: 'hedy'})");
                }
              }),
          new Wrong(
              "an appender on a read-only connection",
              ZuException.class,
              "an appender writes and the connection is read-only",
              where -> {
                Path path = people(where.resolve("reader.zu1"));
                try (Database db = Database.open(path, Config.defaults().withReadOnly(true));
                    Connection conn = db.connect()) {
                  conn.appender("Person").close();
                }
              }),

          // Statements.
          new Wrong(
              "a statement with a typo in it",
              ZuSyntaxException.class,
              "found 'RETRUN'",
              where -> scratch(where, conn -> conn.execute("MATCH (p:Person) RETRUN p.id"))),
          new Wrong(
              "a parameter that was never bound",
              ZuSyntaxException.class,
              "missing parameter $id",
              where ->
                  scratch(
                      where,
                      conn -> {
                        try (Statement s =
                            conn.prepare("MATCH (p:Person) WHERE p.id = $id RETURN p.name")) {
                          s.execute().close();
                        }
                      })),
          new Wrong(
              // A MATCH on a label that does not exist is not here, because
              // today it answers no rows rather than saying so. An appender
              // is the call that does refuse, and it is the one a program
              // gets wrong most often anyway.
              "a table that is not there",
              ZuException.class,
              "no node table or rel table 'Nobody'",
              where -> scratch(where, conn -> conn.appender("Nobody").close())),
          new Wrong(
              "a variable that was never defined",
              ZuSyntaxException.class,
              "variable 'nobody' is not defined",
              where -> scratch(where, conn -> conn.execute("RETURN nobody"))),
          new Wrong(
              "a statement that is nothing at all",
              NullPointerException.class,
              "statement",
              where -> scratch(where, conn -> conn.execute(null))),
          new Wrong(
              "a statement on a connection that was closed",
              ZuClosedException.class,
              "this connection is closed",
              where -> {
                Connection conn = Connection.create(where.resolve("shut.zu1"));
                conn.close();
                conn.execute("RETURN 1");
              }),

          // Reading a result.
          new Wrong(
              "a row past the end of the result",
              ZuProgrammingException.class,
              "row 99 of a result with 3 of them",
              where -> read(where, result -> result.row(99))),
          new Wrong(
              "a column the result does not have",
              ZuProgrammingException.class,
              "no column called nope",
              where -> read(where, result -> result.row(0).getLong("nope"))),
          new Wrong(
              "a column of strings read as integers",
              ZuProgrammingException.class,
              "longs(1) reads a column of INT",
              where -> read(where, result -> result.longs(1))),
          new Wrong(
              "a result read after it closed",
              ZuClosedException.class,
              "this result is closed",
              where ->
                  scratch(
                      where,
                      conn -> {
                        Result result = conn.query("RETURN 1 AS one");
                        result.close();
                        result.row(0);
                      })),

          // Writing through an appender.
          new Wrong(
              "a value of a type the column does not hold",
              ZuException.class,
              "column 'id' of 'Person' holds integers",
              where -> append(where, rows -> rows.append("four").append("hedy").endRow())),
          new Wrong(
              "a row with a value missing from it",
              ZuException.class,
              "and 'Person' takes 2: id, name",
              where -> append(where, rows -> rows.append(4L).endRow().flush())),
          new Wrong(
              "a row with a value too many in it",
              ZuException.class,
              "already carries the 2 values 'Person' takes",
              where -> append(where, rows -> rows.append(4L).append("hedy").append(5L))),
          new Wrong(
              "a null in a row",
              ZuProgrammingException.class,
              "there is no null to append",
              where -> append(where, rows -> rows.row(4L, null))),
          new Wrong(
              "a value of a class no column holds",
              ZuProgrammingException.class,
              "no column holds one of those",
              where -> append(where, rows -> rows.row(4L, List.of("hedy")))),
          new Wrong(
              "an appender used after it closed",
              ZuClosedException.class,
              "this appender is closed",
              where ->
                  append(
                      where,
                      rows -> {
                        rows.close();
                        rows.append(4L);
                      })),
          new Wrong(
              "an appender used after its connection closed",
              ZuClosedException.class,
              "the connection this appender writes through is closed",
              where -> {
                Path path = people(where.resolve("orphan.zu1"));
                Database db = Database.open(path);
                Connection conn = db.connect();
                Appender rows = conn.appender("Person");
                conn.close();
                db.close();
                try {
                  rows.append(4L).append("hedy").endRow().flush();
                } finally {
                  rows.close();
                }
              }),

          // Building a database.
          new Wrong(
              "a column with the wrong number of values in it",
              ZuException.class,
              "against a table of 3 rows",
              where -> {
                try (Loader loader = Loader.create(where.resolve("short.zu1"))) {
                  loader.table("Person", "Knows", 3);
                  loader.column("id", 1L, 2L);
                }
              }),
          new Wrong(
              "an edge to a row that is not there",
              ZuException.class,
              "a table with 2 rows in it",
              where -> {
                try (Loader loader = Loader.create(where.resolve("edge.zu1"))) {
                  loader.table("Person", "Knows", 2);
                  loader.column("id", 1L, 2L);
                  loader.column("name", "ada", "grace");
                  loader.edges(new int[] {0}, new int[] {9});
                }
              }),
          new Wrong(
              "a column before there is a table for it to be in",
              ZuException.class,
              "the loader has no table yet",
              where -> {
                try (Loader loader = Loader.create(where.resolve("early.zu1"))) {
                  loader.column("id", 1L, 2L);
                }
              }),
          new Wrong(
              "the same table twice",
              ZuException.class,
              "this loader already has a table",
              where -> {
                try (Loader loader = Loader.create(where.resolve("again.zu1"))) {
                  loader.table("Person", "Knows", 2);
                  loader.table("Person", "Knows", 2);
                }
              }),
          new Wrong(
              "a loader used after it closed",
              ZuClosedException.class,
              "this loader is closed",
              where -> {
                Loader loader = Loader.create(where.resolve("shut.zu1"));
                loader.table("Person", "Knows", 1);
                loader.column("id", 1L);
                loader.column("name", "ada");
                loader.finish();
                loader.close();
                loader.table("Other", "Knows", 1);
              }),

          // Transactions.
          new Wrong(
              "committing when nothing is running",
              ZuTransactionException.class,
              "no transaction running on this session to commit",
              where -> scratch(where, Connection::commit)),
          new Wrong(
              "rolling back when nothing is running",
              ZuTransactionException.class,
              "no transaction running on this session to roll back",
              where -> scratch(where, Connection::rollback)),
          new Wrong(
              "a transaction inside a transaction",
              ZuTransactionException.class,
              "one does not start inside another",
              where ->
                  scratch(
                      where,
                      conn -> {
                        conn.begin();
                        conn.begin();
                      })),

          // Frames.
          new Wrong(
              "a frame whose columns are on the heap",
              ZuException.class,
              "ByteBuffer.allocateDirect",
              where -> {
                try (Frame frame = Frame.of("Heap", 2)) {
                  frame.column("id", LongBuffer.allocate(2));
                }
              }),
          new Wrong(
              "a frame registered inside a transaction",
              ZuTransactionException.class,
              "a frame is registered on the session",
              where ->
                  scratch(
                      where,
                      conn -> {
                        try (Frame frame = Frame.of("Heap", 2)) {
                          frame.column("id", direct(1L, 2L));
                          conn.begin();
                          conn.register(frame);
                        }
                      })));

  @Test
  void thereAreWrongProgramsToRun() {
    assertTrue(WRONG.size() >= 30, "the suite is meant to be a wide table, not a sample");
  }

  @TestFactory
  List<DynamicTest> everyWrongProgramSaysWhatIsWrong() {
    List<DynamicTest> tests = new ArrayList<>();
    for (int i = 0; i < WRONG.size(); i++) {
      Wrong wrong = WRONG.get(i);
      Path where = dir.resolve("case" + i);
      tests.add(
          DynamicTest.dynamicTest(
              wrong.what(),
              () -> {
                Files.createDirectories(where);
                Throwable thrown =
                    assertThrows(
                        wrong.raises(),
                        () -> wrong.run().accept(where),
                        wrong.what() + " raised nothing at all");
                String said = thrown.getMessage();
                assertNotNull(said, wrong.what() + " raised " + thrown.getClass() + " with no words");
                assertTrue(
                    said.contains(wrong.says()),
                    wrong.what()
                        + " said\n  "
                        + said
                        + "\nwhich does not contain\n  "
                        + wrong.says());
              }));
    }
    return tests;
  }

  @Test
  void fiveHundredFailedOpensLeaveNothingOpen() throws IOException {
    Path gone = dir.resolve("never-existed.zu1");
    for (int i = 0; i < 20; i++) {
      assertThrows(ZuException.class, () -> Database.open(gone).close());
    }
    long before = openFiles();
    assumeTrue(before > 0, "no way to count open files here");
    for (int i = 0; i < 500; i++) {
      assertThrows(ZuException.class, () -> Database.open(gone).close());
    }
    long after = openFiles();
    assertTrue(
        after - before <= 8,
        "500 failed opens went from " + before + " open files to " + after);
  }

  @Test
  void aThousandConnectionsOpenedAndClosedLeaveNothingBehind() throws IOException {
    Path path = people(dir.resolve("thousand.zu1"));
    try (Database db = Database.open(path)) {
      for (int i = 0; i < 20; i++) {
        db.connect().close();
      }
      long before = openFiles();
      assumeTrue(before > 0, "no way to count open files here");
      for (int i = 0; i < 1000; i++) {
        try (Connection conn = db.connect();
            Result result = conn.query("MATCH (p:Person) RETURN p.id")) {
          assertEquals(3L, result.rows());
        }
      }
      long after = openFiles();
      assertTrue(
          after - before <= 8,
          "1000 connections went from " + before + " open files to " + after);
    }
  }

  @Test
  void aConnectionClosedWithThingsOpenOnItDoesNotTakeTheProcessWithIt() {
    Path path = people(dir.resolve("early-close.zu1"));
    try (Database db = Database.open(path)) {
      for (int i = 0; i < 100; i++) {
        Connection conn = db.connect();
        Result result = conn.query("MATCH (p:Person) RETURN p.id, p.name");
        Appender rows = conn.appender("Person");
        conn.close();
        // Whatever these two answer now, they answer it rather than taking
        // the process down, and closing them afterwards is still allowed.
        result.close();
        rows.close();
      }
    }
  }

  @Test
  void aStatementThatFailedWroteNothingAndLeftTheConnectionAlone() {
    Path path = people(dir.resolve("failed.zu1"));
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      assertThrows(ZuException.class, () -> conn.execute("MATCH (p:Person) RETRUN p.id"));
      assertThrows(ZuException.class, () -> conn.execute("RETURN nobody"));
      try (Result result = conn.query("MATCH (p:Person) RETURN p.id")) {
        assertEquals(3L, result.rows());
      }
    }
  }

  @Test
  void aThousandFailedStatementsLeaveNothingBehind() throws IOException {
    Path path = people(dir.resolve("failures.zu1"));
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      for (int i = 0; i < 20; i++) {
        assertThrows(ZuException.class, () -> conn.execute("MATCH (p:Person) RETRUN p.id"));
      }
      long before = openFiles();
      assumeTrue(before > 0, "no way to count open files here");
      for (int i = 0; i < 1000; i++) {
        assertThrows(ZuException.class, () -> conn.execute("MATCH (p:Person) RETRUN p.id"));
      }
      long after = openFiles();
      assertTrue(
          after - before <= 8,
          "1000 failed statements went from " + before + " open files to " + after);
      try (Result result = conn.query("MATCH (p:Person) RETURN p.id")) {
        assertEquals(3L, result.rows());
      }
    }
  }

  @Test
  void theProgramsThatLookLikeMisuseAndAreNot() {
    Path path = people(dir.resolve("fine.zu1"));
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      // Closing twice is not a mistake, and neither is closing something that
      // was already finished.
      Result result = conn.query("MATCH (p:Person) RETURN p.id, p.name");
      result.close();
      result.close();
      assertTrue(result.isClosed());

      try (Appender rows = conn.appender("Person")) {
        assertEquals(0L, rows.finish());
        assertTrue(rows.isFinished());
      }

      // A result with no rows in it is an answer, not a failure, and reading
      // its columns as a borrowed lane is allowed even though there is
      // nothing in the lane.
      try (Result none = conn.query("MATCH (p:Person) WHERE p.id = 99 RETURN p.id")) {
        assertEquals(0L, none.rows());
        assertEquals(0, none.longs(0).remaining());
        assertEquals(0L, none.stream().count());
      }

      // A transaction that did nothing still rolls back.
      conn.begin();
      conn.rollback();

      // And the connection is still the connection afterwards.
      try (Result again = conn.query("MATCH (p:Person) RETURN p.id")) {
        assertEquals(3L, again.rows());
      }
      assertFalse(conn.isClosed());
    }
  }

  /** A two column Person table with three rows in it, at the path given. */
  private static Path people(Path path) {
    try (Loader loader = Loader.create(path)) {
      loader.table("Person", "Knows", 3);
      loader.column("id", 1L, 2L, 3L);
      loader.column("name", "ada", "grace", "lynn");
      loader.finish();
    }
    return path;
  }

  /** Runs a wrong program against a fresh database of three people. */
  private static void scratch(Path where, ThrowingConsumer<Connection> run) throws Throwable {
    Path path = people(where.resolve("scratch.zu1"));
    try (Database db = Database.open(path);
        Connection conn = db.connect()) {
      run.accept(conn);
    }
  }

  /** Runs a wrong program against a result of three people, id then name. */
  private static void read(Path where, ThrowingConsumer<Result> run) throws Throwable {
    scratch(
        where,
        conn -> {
          try (Result result = conn.query("MATCH (p:Person) RETURN p.id AS id, p.name AS name")) {
            run.accept(result);
          }
        });
  }

  /** Runs a wrong program against an appender on a table of three people. */
  private static void append(Path where, ThrowingConsumer<Appender> run) throws Throwable {
    scratch(
        where,
        conn -> {
          try (Appender rows = conn.appender("Person")) {
            run.accept(rows);
          }
        });
  }

  /** A direct buffer of longs, which is the only kind a frame will take. */
  private static LongBuffer direct(long... values) {
    LongBuffer buffer =
        ByteBuffer.allocateDirect(values.length * Long.BYTES)
            .order(java.nio.ByteOrder.nativeOrder())
            .asLongBuffer();
    buffer.put(values).flip();
    return buffer;
  }

  /**
   * How many files this process has open, or -1 where there is no way to ask.
   *
   * <p>Both platforms this runs on keep a directory of the process's own
   * descriptors, which is cheaper to read than a management bean and does not
   * put a module on the path for one number.
   */
  private static long openFiles() throws IOException {
    for (String each : new String[] {"/proc/self/fd", "/dev/fd"}) {
      Path fds = Path.of(each);
      if (Files.isDirectory(fds)) {
        try (Stream<Path> open = Files.list(fds)) {
          return open.count();
        }
      }
    }
    return -1;
  }
}
