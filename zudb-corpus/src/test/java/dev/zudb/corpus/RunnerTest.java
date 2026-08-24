package dev.zudb.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zudb.Connection;
import dev.zudb.ZuException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner, on cases written here rather than on the corpus.
 *
 * <p>The corpus itself is run by the test beside this one, which needs the
 * case files and is skipped without them. This file is the other half: a
 * handful of cases written to make the runner do each of the things it
 * does, including the ones the corpus has no case for because the corpus is
 * a corpus of correct expectations. A case that wants the wrong number of
 * rows has to be reported and not merely fail, and the only way to have one
 * is to write one.
 *
 * <p>The detail strings are checked in full, because they are what a report
 * says and the report is diffed against the reference runner's.
 */
final class RunnerTest {

  @TempDir Path work;

  private int round;

  /**
   * A directory nothing has run in yet.
   *
   * <p>One per run rather than one per test, because a case that fails
   * leaves its database behind on purpose and the next run of a case with
   * the same name would find the file already there. The tests here run
   * several suites of a case called `one` and most of them fail, which is
   * the whole point of them.
   */
  private Path fresh() {
    try {
      return Files.createDirectory(work.resolve("run-" + round++));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Runs a suite written inline and gives back what each case came to. The
   * databases go under the test's own directory, which JUnit removes.
   */
  private List<Runner.Ran> ranHere(String text) {
    return Runner.run(List.of(Suite.read(text)), fresh()).ran();
  }

  /** A suite of one case, with body under {@code cases:}. */
  private static String caseText(String body) {
    return "schema: 4\nsuite: inline\ndoc: cases written for the runner's own tests\ncases:\n"
        + body;
  }

  /** The one case a suite of one came to. */
  private Runner.Ran only(String body) {
    List<Runner.Ran> all = ranHere(caseText(body));
    assertEquals(1, all.size(), all.size() + " cases ran, and the suite writes one");
    return all.get(0);
  }

  @Test
  void aCaseThatSaysWhatItProducesPasses() {
    Runner.Ran got = only("""
          - name: a-statement-returns-what-it-names
            doc: d
            query: UNWIND [1, 2] AS n RETURN n, n * 2 AS twice
            columns:
              - n
              - twice
            rows:
              - values:
                  - type: INT64
                    value: "1"
                  - type: INT64
                    value: "2"
              - values:
                  - type: INT64
                    value: "2"
                  - type: INT64
                    value: "4"
        """);
    assertEquals(Runner.Outcome.PASSED, got.outcome(),
        "came to " + got.outcome().mark() + ": " + got.detail());
    assertEquals("", got.detail(), "a case that passed carries a detail");
    assertEquals("inline", got.suite());
    assertEquals("a-statement-returns-what-it-names", got.name());
  }

  @Test
  void aCaseThatWantsAConditionPassesOnThatCode() {
    Runner.Ran got = only("""
          - name: an-empty-statement-is-a-condition
            doc: d
            query: ""
            raises: "42001"
        """);
    assertEquals(Runner.Outcome.PASSED, got.outcome(),
        "came to " + got.outcome().mark() + ": " + got.detail());
  }

  /**
   * The failures, which are what the runner is for and what the corpus has
   * no examples of.
   */
  @Test
  void everyWayACaseCanFailIsReportedInFull() {
    record Wrong(String what, String body, String want) {}
    for (Wrong one : List.of(
        new Wrong("the wrong columns", """
              - name: one
                doc: d
                query: RETURN 1 AS n
                columns:
                  - m
                rows:
                  - values:
                      - type: INT64
                        value: "1"
            """, "columns [\"n\"] where the case wants [\"m\"]"),
        new Wrong("the wrong value", """
              - name: one
                doc: d
                query: RETURN 1 AS n
                columns:
                  - n
                rows:
                  - values:
                      - type: INT64
                        value: "2"
            """, "row 1 column n is INT64 \"1\" where the case wants INT64 \"2\""),
        new Wrong("the wrong type in the right column", """
              - name: one
                doc: d
                query: RETURN 1 AS n
                columns:
                  - n
                rows:
                  - values:
                      - type: STRING
                        value: '1'
            """, "row 1 column n is INT64 \"1\" where the case wants STRING \"1\""),
        new Wrong("too few rows", """
              - name: one
                doc: d
                query: UNWIND [1] AS n RETURN n
                columns:
                  - n
                rows:
                  - values:
                      - type: INT64
                        value: "1"
                  - values:
                      - type: INT64
                        value: "2"
            """, "1 rows where the case wants 2"),
        new Wrong("too many rows", """
              - name: one
                doc: d
                query: UNWIND [1, 2] AS n RETURN n
                columns:
                  - n
                rows:
                  - values:
                      - type: INT64
                        value: "1"
            """, "2 rows where the case wants 1"),
        new Wrong("rows where the case wants a condition", """
              - name: one
                doc: d
                query: RETURN 1 AS n
                raises: "22003"
            """, "returned rows where the case wants 22003"),
        new Wrong("a condition where the case wants another one", """
              - name: one
                doc: d
                query: ""
                raises: "22003"
            """, "raised 42001 where the case wants 22003"),
        new Wrong("a setup that will not run", """
              - name: one
                doc: d
                setup:
                  - INSERT (:nowhere)
                query: RETURN 1 AS n
                columns:
                  - n
                rows:
                  - values:
                      - type: INT64
                        value: "1"
            """, "setup 1"))) {
      // A setup that will not run is a failure or a case ahead of the
      // engine depending on what the engine says about the statement, and
      // either way it is never a pass. Everything else here is a failure
      // and nothing else.
      boolean wantsFailed = !one.want().startsWith("setup");
      Runner.Ran got = only(one.body());
      assertNotEquals(Runner.Outcome.PASSED, got.outcome(), one.what() + " passed");
      if (wantsFailed) {
        assertEquals(Runner.Outcome.FAILED, got.outcome(),
            one.what() + " came to " + got.outcome().mark() + ": " + got.detail());
      }
      assertTrue(got.detail().startsWith(one.want()), one.what() + " was reported as\n  "
          + Text.quote(got.detail()) + "\nand it should open with\n  " + Text.quote(one.want()));
    }
  }

  /**
   * A case the engine has not caught up to is unsupported and not a
   * failure, which is what lets the corpus be the contract and the engine
   * catch up to it. The two classes that say so are 42 and 0A.
   */
  @Test
  void aCaseAheadOfTheEngineIsUnsupportedAndNotAFailure() {
    Runner.Ran got = only("""
          - name: one
            doc: d
            query: SELECT 1
            columns:
              - n
            rows:
        """);
    assertEquals(Runner.Outcome.UNSUPPORTED, got.outcome(),
        "came to " + got.outcome().mark() + ": " + got.detail());
    assertTrue(got.detail().startsWith("42001"),
        "the detail is " + Text.quote(got.detail()) + ", and it should open with the code");
  }

  /**
   * Two connections over one file share the write side, so each sees what
   * the other has committed, which is what a case about a session means.
   */
  @Test
  void aCaseMayNameTheConnectionEachStatementRunsOn() {
    Runner.Ran got = only("""
          - name: a-second-connection-sees-what-the-first-committed
            doc: d
            setup:
              - on: writer
                query: INSERT (:person {name: 'a'})
            on: reader
            query: MATCH (p:person) RETURN count(*) AS c
            columns:
              - c
            rows:
              - values:
                  - type: INT64
                    value: "1"
        """);
    assertNotEquals(Runner.Outcome.FAILED, got.outcome(),
        "came to " + got.outcome().mark() + ": " + got.detail());
  }

  @Test
  void aCaseMayBindParameters() {
    Runner.Ran got = only("""
          - name: a-parameter-is-bound-by-name
            doc: d
            query: RETURN $n AS n
            params:
              - name: n
                type: INT64
                value: "7"
            columns:
              - n
            rows:
              - values:
                  - type: INT64
                    value: "7"
        """);
    assertEquals(Runner.Outcome.PASSED, got.outcome(),
        "came to " + got.outcome().mark() + ": " + got.detail());
  }

  /**
   * The load is the other half of the corpus: an expression says what a
   * value means on the way out and nothing about how it got in.
   */
  @Test
  void aSuiteWithALoadPutsItInThroughTheLoader() {
    List<Runner.Ran> all = ranHere("""
        schema: 4
        suite: inline
        doc: d
        load:
          nodes: person
          edges: knows
          count: 3
          columns:
            - name: name
              type: STRING
              values:
                - a
                - b
                - c
          pairs:
            - from: 0
              to: 1
            - from: 1
              to: 2
        cases:
          - name: the-rows-that-went-in-come-back
            doc: d
            query: MATCH (p:person) RETURN count(*) AS c
            columns:
              - c
            rows:
              - values:
                  - type: INT64
                    value: "3"
          - name: the-edges-that-went-in-come-back
            doc: d
            query: MATCH (:person)-[:knows]->(:person) RETURN count(*) AS c
            columns:
              - c
            rows:
              - values:
                  - type: INT64
                    value: "2"
        """);
    for (Runner.Ran ran : all) {
      assertNotEquals(Runner.Outcome.FAILED, ran.outcome(),
          ran.name() + " came to " + ran.outcome().mark() + ": " + ran.detail());
    }
    // Each case of the suite gets its own copy of the load, so the second
    // one does not see what the first one did.
    assertEquals(2, all.size(), all.size() + " cases ran");
  }

  /**
   * A failure leaves its database behind, which is the one thing somebody
   * reading the report will want to open. Everything else goes as it
   * finishes, because fourteen hundred cases are fourteen hundred files.
   */
  @Test
  void onlyAFailedCaseLeavesItsDatabaseBehind() {
    Path directory = fresh();
    Runner.run(List.of(Suite.read(caseText("""
          - name: one-that-passes
            doc: d
            query: RETURN 1 AS n
            columns:
              - n
            rows:
              - values:
                  - type: INT64
                    value: "1"
          - name: one-that-fails
            doc: d
            query: RETURN 1 AS n
            columns:
              - n
            rows:
              - values:
                  - type: INT64
                    value: "2"
        """))), directory);
    assertFalse(Files.exists(Runner.casePath(directory, "inline", "one-that-passes")),
        "a case that passed left its database behind");
    assertTrue(Files.exists(Runner.casePath(directory, "inline", "one-that-fails")),
        "a case that failed left nothing to open");
  }

  @Test
  void aReportSaysWhatEachCaseDidAndWhatTheRunCameTo() {
    Runner.Report report = new Runner.Report(List.of(
        new Runner.Ran("string", "one", 12, Runner.Outcome.PASSED, ""),
        new Runner.Ran("string", "two", 20, Runner.Outcome.FAILED,
            "1 rows where the case wants 2"),
        new Runner.Ran("select", "three", 38, Runner.Outcome.UNSUPPORTED, "42001: no")));
    List<String> want = List.of(
        "string/one line 12 ok",
        "string/two line 20 FAILED: 1 rows where the case wants 2",
        "select/three line 38 unsupported: 42001: no");
    for (int i = 0; i < want.size(); i++) {
      assertEquals(want.get(i), report.ran().get(i).toString(), "line " + i);
    }
    assertEquals("3 cases, 1 passed, 1 failed, 1 unsupported", report.summary());
    assertEquals(1, report.count(Runner.Outcome.PASSED));
    assertEquals(1, report.count(Runner.Outcome.FAILED));
    assertEquals(1, report.count(Runner.Outcome.UNSUPPORTED));
    assertEquals("0 cases, 0 passed, 0 failed, 0 unsupported",
        new Runner.Report(List.of()).summary());
  }

  /**
   * compare reports the first difference rather than all of them, because
   * the first is nearly always the cause of the rest, and the order the
   * checks run in is the reference runner's.
   */
  @Test
  void compareReportsTheFirstDifferenceAndNothingAfterIt() {
    record Case(String what, List<String> wantColumns, List<List<Cell>> wantRows,
        List<String> gotColumns, List<List<Cell>> gotRows, String detail) {}
    List<String> n = List.of("n");
    List<List<Cell>> one = List.of(List.of(new Cell.Int(1)));
    for (Case c : List.of(
        new Case("nothing wrong", n, one, n, one, ""),
        new Case("the columns before the rows, since a wrong column makes every row wrong",
            n, one, List.of("m"), List.of(List.of(new Cell.Int(2))),
            "columns [\"m\"] where the case wants [\"n\"]"),
        new Case("the first row that differs and not the second",
            n, List.of(List.of(new Cell.Int(1)), List.of(new Cell.Int(2))),
            n, List.of(List.of(new Cell.Int(9)), List.of(new Cell.Int(8))),
            "row 1 column n is INT64 \"9\" where the case wants INT64 \"1\""),
        new Case("a value before a count, since a row that differs says more than a total",
            n, List.of(List.of(new Cell.Int(1)), List.of(new Cell.Int(2))),
            n, List.of(List.of(new Cell.Int(9))),
            "row 1 column n is INT64 \"9\" where the case wants INT64 \"1\""),
        new Case("the count when every row that is there matches",
            n, List.of(List.of(new Cell.Int(1)), List.of(new Cell.Int(2))), n, one,
            "1 rows where the case wants 2"),
        new Case("no columns against no columns, which FINISH answers",
            List.of(), List.of(), List.of(), List.of(), ""),
        new Case("no columns against some, which is still a difference",
            List.of(), List.of(), n, List.of(),
            "columns [\"n\"] where the case wants []"))) {
      assertEquals(c.detail(),
          Runner.compare(c.wantColumns(), c.wantRows(), c.gotColumns(), c.gotRows()), c.what());
    }
  }

  /**
   * The report prints the message rather than the exception, because a
   * class name in front of every failing line would differ from the report
   * this one is diffed against.
   */
  @Test
  void theReportPrintsWhatTheEngineSaidAndNotWhatAJavaProgramWouldLog() {
    ZuException engine = assertThrows(ZuException.class, () -> {
      try (Connection conn = Connection.memory()) {
        conn.execute("");
      }
    });
    assertEquals("42001", Runner.statusCode(engine));
    assertTrue(Runner.errorText(engine).startsWith("42001:"),
        "an engine failure reads " + Text.quote(Runner.errorText(engine)));
    assertFalse(Runner.errorText(engine).startsWith("zu: "),
        "the report opens a line with what another client would log");
    assertFalse(Runner.errorText(engine).contains("ZuException"),
        "the report opens a line with a class name");
    assertTrue(Runner.unsupported(engine),
        "42001 is a case ahead of the engine and not a failure");

    // The two classes that say a case is ahead of the engine, and nothing
    // else.
    record Code(String code, boolean ahead) {}
    for (Code c : List.of(
        new Code("42001", true),
        new Code("42002", true),
        new Code("0A000", true),
        new Code("22003", false),
        new Code("22G03", false),
        new Code("00000", false),
        new Code("", false))) {
      assertEquals(c.ahead(), Runner.unsupported(c.code()), Text.quote(c.code()));
    }

    // The reader's own failures and the export's, which go into the same
    // report and have no GQLSTATUS at all.
    assertEquals("line 4: no `doc:`",
        Runner.errorText(new CorpusException("line 4: no `doc:`")));
    assertEquals("no export", Runner.errorText(new ArrowException("no export")));
    assertEquals("something else", Runner.errorText(new IllegalStateException("something else")));
    assertEquals("", Runner.statusCode(new IllegalStateException("something else")));
  }

  @Test
  void namesWritesAColumnListTheWayTheReferenceRunnerDoes() {
    assertEquals("[]", Runner.names(List.of()));
    assertEquals("[\"n\"]", Runner.names(List.of("n")));
    assertEquals("[\"n\", \"twice\"]", Runner.names(List.of("n", "twice")));
  }
}
