package dev.zudb.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The case reader, which is the layer between the YAML subset and the
 * runner.
 *
 * <p>A case says what a statement produces one way or the other: columns
 * and rows, or a GQLSTATUS. Most of what this file tests is the ways a case
 * can say neither, or both, or something that looks like one and is not,
 * because a corpus file is hand written and read by people in nine
 * repositories who did not write it. A case that was quietly dropped for a
 * typo in a key is a suite that runs green with less in it than anybody
 * thinks.
 */
class SuiteTest {

  /**
   * A whole file with body as its list of cases, so that a test about one
   * case does not have to write a header every time.
   */
  private static String suiteAround(String body) {
    return "schema: 4\nsuite: test\ndoc: a suite for the tests\ncases:\n" + body;
  }

  /** The case a body comes to. */
  private static Suite.Case oneCase(String body) {
    Suite suite = Suite.read(suiteAround(body));
    assertEquals(1, suite.cases().size(), "the body writes one case");
    return suite.cases().get(0);
  }

  /** The message a file was refused with. */
  private static String rejected(String text) {
    return assertThrows(CorpusException.class, () -> Suite.read(text),
        () -> "this should have been refused: " + text).getMessage();
  }

  private static String lines(String... written) {
    return String.join("\n", written) + "\n";
  }

  @Test
  @DisplayName("a suite is its header and its cases")
  void aSuiteIsItsHeaderAndItsCases() {
    Suite suite = Suite.read(lines(
        "schema: 4",
        "suite: string",
        "doc: what a string does",
        "cases:",
        "  - name: one",
        "    doc: the first",
        "    query: RETURN 1 AS n",
        "    columns:",
        "      - n",
        "    rows:",
        "      - values:",
        "          - type: INT64",
        "            value: \"1\""));
    assertEquals("string", suite.name());
    assertEquals("what a string does", suite.doc());
    assertNull(suite.load(), "a suite with no `load:` came back with one");
    Suite.Case one = suite.cases().get(0);
    assertEquals("one", one.name());
    assertEquals("RETURN 1 AS n", one.query());
    assertEquals(5, one.line());
    assertEquals(Suite.MAIN, one.on(), "a case that names no connection runs on main");
    assertTrue(one.hasColumns());
    assertEquals(List.of("n"), one.columns());
    assertEquals(List.of(List.of(new Cell.Int(1))), one.rows());
  }

  // The version is checked before anything else, so that a corpus unpacked
  // from an old release says what it is instead of failing somewhere in the
  // middle with a message about a key.
  @Test
  @DisplayName("the schema version is checked before anything else")
  void theSchemaVersionIsCheckedBeforeAnythingElse() {
    record Case(String what, String text, String want) {}
    for (Case c : List.of(
        new Case("a version this runner does not read",
            "schema: 3\nsuite: test\ndoc: d\ncases:\n  - name: one\n",
            "this is schema 3 and the runner reads schema 4"),
        new Case("a version that is not a number",
            "schema: four\nsuite: test\ndoc: d\n",
            "\"four\" is not a schema version"),
        new Case("no version at all",
            "suite: test\ndoc: d\n",
            "the file does not open with `schema:`"),
        new Case("a version that is not one line",
            "schema:\n  - 4\nsuite: test\ndoc: d\n",
            "the file does not open with `schema:`"))) {
      assertEquals(c.want(), rejected(c.text()), c.what());
    }
  }

  @Test
  @DisplayName("a suite says what is wrong with its header")
  void aSuiteSaysWhatIsWrongWithItsHeader() {
    record Case(String what, String text, String want) {}
    for (Case c : List.of(
        new Case("a key a suite has no room for",
            "schema: 4\nsuite: test\ndoc: d\nloadd:\ncases:\n",
            "line 1: a suite has no key \"loadd\""),
        new Case("no name",
            "schema: 4\ndoc: d\ncases:\n",
            "line 1: no `suite:`"),
        new Case("no doc",
            "schema: 4\nsuite: test\ncases:\n",
            "line 1: no `doc:`"),
        new Case("no cases",
            "schema: 4\nsuite: test\ndoc: d\n",
            "a suite with no `cases:`"),
        new Case("cases that are not a sequence",
            "schema: 4\nsuite: test\ndoc: d\ncases: one\n",
            "`cases:` is a sequence"),
        new Case("a `cases:` with nothing under it",
            "schema: 4\nsuite: test\ndoc: d\ncases:\n",
            "`cases:` is a sequence"))) {
      assertEquals(c.want(), rejected(c.text()), c.what());
    }
  }

  // A name is what a report cites and what a binding's skip list names, so
  // two cases sharing one is a report that says less than it looks like it
  // does.
  @Test
  @DisplayName("two cases may not share a name")
  void twoCasesMayNotShareAName() {
    String body = lines(
        "  - name: one",
        "    doc: d",
        "    query: RETURN 1",
        "    raises: \"42001\"",
        "  - name: one",
        "    doc: d",
        "    query: RETURN 2",
        "    raises: \"42001\"");
    assertEquals("two cases are called \"one\"", rejected(suiteAround(body)));
  }

  @Test
  @DisplayName("a case name is lower case words joined by dashes")
  void aCaseNameIsLowerCaseWordsJoinedByDashes() {
    for (String name : List.of("One", "a name", "a_name", "a.name", "\"\"")) {
      String body = "  - name: " + name + "\n    doc: d\n    query: RETURN 1\n";
      assertTrue(rejected(suiteAround(body))
              .contains("is a case name, which is lower case words joined by dashes"),
          () -> name + " should have been refused for its spelling");
    }
    // A `name:` with nothing after it is a key with nothing under it rather
    // than an empty name, so it is refused for its shape and not for what it
    // spells. The two messages say different things and this is the one that
    // helps.
    assertEquals("line 5: `name:` is one line of text",
        rejected(suiteAround("  - name:\n    doc: d\n    query: RETURN 1\n")));
    // Digits and dashes are in, since a case is named after what it does and
    // some of those have numbers in them.
    Suite.Case one = oneCase("  - name: a-3-hop-walk\n    doc: d\n    query: RETURN 1\n"
        + "    raises: \"42001\"\n");
    assertEquals("a-3-hop-walk", one.name());
  }

  // A case says what it produces one way or the other, and the reader
  // refuses both ways at once and neither way at all.
  @Test
  @DisplayName("a case says what it produces exactly one way")
  void aCaseSaysWhatItProducesExactlyOneWay() {
    String both = lines(
        "  - name: one",
        "    doc: d",
        "    query: RETURN 1",
        "    raises: \"42001\"",
        "    columns:",
        "      - n");
    assertEquals("line 5: a case that raises has no rows, and one that returns rows does not raise",
        rejected(suiteAround(both)), "a case saying both");

    String neither = "  - name: one\n    doc: d\n    query: RETURN 1\n";
    assertEquals("line 5: a case says what it produces, with `columns:` and `rows:` or with "
        + "`raises:`", rejected(suiteAround(neither)), "a case saying neither");

    // Columns with no rows is the one that looks finished and is not, so the
    // message says how to write the case that was meant.
    String noRows = "  - name: one\n    doc: d\n    query: RETURN 1\n    columns:\n      - n\n";
    assertEquals("line 5: `columns:` with no `rows:`. A case expecting nothing back writes `rows:` "
        + "with an empty sequence under it.", rejected(suiteAround(noRows)));
  }

  // FINISH answers no columns at all, which is not the same as a query whose
  // columns held no rows, so an empty `columns:` is a case and not an
  // omission.
  @Test
  @DisplayName("no columns and no rows are two different expectations")
  void noColumnsAndNoRowsAreTwoDifferentExpectations() {
    Suite.Case empty = oneCase("  - name: one\n    doc: d\n    query: FINISH\n"
        + "    columns:\n    rows:\n");
    assertTrue(empty.hasColumns(),
        "a `columns:` with nothing under it read as a case with no columns key");
    assertEquals(List.of(), empty.columns());
    assertEquals(List.of(), empty.rows());

    Suite.Case noRows = oneCase("  - name: one\n    doc: d\n    query: RETURN 1 AS n\n"
        + "    columns:\n      - n\n    rows:\n");
    assertEquals(List.of("n"), noRows.columns());
    assertEquals(List.of(), noRows.rows());
  }

  @Test
  @DisplayName("a row holds one value per column")
  void aRowHoldsOneValuePerColumn() {
    String body = lines(
        "  - name: one",
        "    doc: d",
        "    query: RETURN 1 AS a, 2 AS b",
        "    columns:",
        "      - a",
        "      - b",
        "    rows:",
        "      - values:",
        "          - type: INT8",
        "            value: 1");
    assertEquals("line 5: a row of 1 against 2 columns", rejected(suiteAround(body)));
  }

  @Test
  @DisplayName("a raises is the shape of a GQLSTATUS")
  void aRaisesIsTheShapeOfAGqlstatus() {
    assertEquals("42001",
        oneCase("  - name: one\n    doc: d\n    query: RETURN\n    raises: \"42001\"\n").raises());
    // The shape and not the list, because a corpus that had to be told about
    // every code the standard defines is one nobody could add a case to.
    assertEquals("22G0Z",
        oneCase("  - name: one\n    doc: d\n    query: RETURN\n    raises: \"22G0Z\"\n").raises());
    for (String code : List.of("4200", "420011", "42a01", "42-01", "")) {
      String body = "  - name: one\n    doc: d\n    query: RETURN\n    raises: \"" + code + "\"\n";
      assertEquals("line 8: \"" + code + "\" is not the shape of a GQLSTATUS, which is five "
          + "characters of digits and capitals", rejected(suiteAround(body)));
    }
  }

  // A setup statement is one line, or a line and the connection it runs on,
  // which is what a case testing two sessions against one file needs.
  @Test
  @DisplayName("setup is a line or a line and a connection")
  void setupIsALineOrALineAndAConnection() {
    Suite.Case one = oneCase(lines(
        "  - name: one",
        "    doc: d",
        "    setup:",
        "      - INSERT (:person {name: 'a'})",
        "      - on: other",
        "        query: INSERT (:person {name: 'b'})",
        "    on: other",
        "    query: MATCH (p:person) RETURN count(*) AS c",
        "    raises: \"42001\""));
    assertEquals(2, one.setup().size());
    assertEquals(new Suite.Step(Suite.MAIN, "INSERT (:person {name: 'a'})"), one.setup().get(0));
    assertEquals("other", one.setup().get(1).on());
    assertEquals("other", one.on());

    record Case(String what, String body, String want) {}
    for (Case c : List.of(
        new Case("setup that is not a sequence",
            "  - name: one\n    doc: d\n    setup: INSERT (:p)\n    query: RETURN 1\n",
            "line 5: `setup:` is a sequence of statements"),
        new Case("a step written as a mapping with no connection",
            "  - name: one\n    doc: d\n    setup:\n      - query: INSERT (:p)\n"
                + "    query: RETURN 1\n",
            "line 8: a setup statement written as a mapping names the connection it runs on"),
        new Case("a step with a key it has no room for",
            "  - name: one\n    doc: d\n    setup:\n      - on: other\n        params: x\n"
                + "    query: RETURN 1\n",
            "line 8: a setup statement has no key \"params\""),
        new Case("a connection name that is not one",
            "  - name: one\n    doc: d\n    on: Other\n    query: RETURN 1\n",
            "line 7: \"Other\" is a connection name, which is lower case words joined by dashes"))) {
      assertEquals(c.want(), rejected(suiteAround(c.body())), c.what());
    }
  }

  // A parameter is the value encoding with a name beside it, and the name is
  // checked against what a statement may write after the $.
  @Test
  @DisplayName("a parameter is a value with a name")
  void aParameterIsAValueWithAName() {
    Suite.Case one = oneCase(lines(
        "  - name: one",
        "    doc: d",
        "    query: RETURN $n AS n",
        "    params:",
        "      - name: n",
        "        type: INT64",
        "        value: \"1\"",
        "      - name: nothing",
        "        type: NULL",
        "    raises: \"42001\""));
    assertEquals(2, one.params().size());
    assertEquals(new Suite.Param("n", new Cell.Int(1)), one.params().get(0));
    assertEquals(new Suite.Param("nothing", Cell.NULL), one.params().get(1));

    record Case(String what, String body, String want) {}
    for (Case c : List.of(
        new Case("params that are not a sequence",
            "  - name: one\n    doc: d\n    query: RETURN $n\n    params: n\n",
            "line 8: `params:` is a sequence"),
        new Case("a parameter that is not a mapping",
            "  - name: one\n    doc: d\n    query: RETURN $n\n    params:\n      - n\n",
            "line 9: a parameter is a mapping of `name`, `type` and `value`, and this is a scalar"),
        new Case("a parameter with a key it has no room for",
            "  - name: one\n    doc: d\n    query: RETURN $n\n    params:\n      - name: n\n"
                + "        type: NULL\n        on: other\n",
            "line 9: a parameter has no key \"on\""),
        new Case("a name no statement can write",
            "  - name: one\n    doc: d\n    query: RETURN $n\n    params:\n      - name: n one\n"
                + "        type: NULL\n",
            "line 9: \"n one\" is a parameter name, which is what a statement writes after the `$`"),
        new Case("two parameters with one name",
            "  - name: one\n    doc: d\n    query: RETURN $n\n    params:\n      - name: n\n"
                + "        type: NULL\n      - name: n\n        type: NULL\n",
            "line 11: two parameters are called \"n\""))) {
      assertEquals(c.want(), rejected(suiteAround(c.body())), c.what());
    }
  }

  // A load is the other half of the corpus: everything else is an
  // expression, which says what a value means on the way out and nothing
  // about how it got in.
  @Test
  @DisplayName("a load is a table, its columns and the edges between its rows")
  void aLoadIsATableItsColumnsAndTheEdgesBetweenItsRows() {
    Suite suite = Suite.read(lines(
        "schema: 4",
        "suite: test",
        "doc: d",
        "load:",
        "  nodes: person",
        "  edges: knows",
        "  count: 3",
        "  columns:",
        "    - name: name",
        "      type: STRING",
        "      values:",
        "        - a",
        "        - b",
        "        - c",
        "    - name: age",
        "      type: INT64",
        "      values:",
        "        - \"1\"",
        "        - \"2\"",
        "        - \"3\"",
        "  pairs:",
        "    - from: 0",
        "      to: 1",
        "    - from: 1",
        "      to: 2",
        "cases:",
        "  - name: one",
        "    doc: d",
        "    query: MATCH (p:person) RETURN count(*) AS c",
        "    raises: \"42001\""));
    Suite.Load load = suite.load();
    assertNotNull(load, "the suite came back with no load");
    assertEquals("person", load.nodes());
    assertEquals("knows", load.edges());
    assertEquals(3, load.count());
    assertEquals(2, load.columns().size());
    assertEquals("INT64", load.columns().get(1).type());
    assertEquals(new Cell.Int(2), load.columns().get(1).values().get(1));
    assertEquals(List.of(new Suite.Pair(0, 1), new Suite.Pair(1, 2)), load.pairs());
  }

  @Test
  @DisplayName("a load says what is wrong with it")
  void aLoadSaysWhatIsWrongWithIt() {
    // A load with the given body, and a case after it so that the file gets
    // as far as the load before it runs out of suite.
    String column = "  columns:\n    - name: name\n      type: STRING\n      values:\n        - a\n";
    record Case(String what, String body, String want) {}
    for (Case c : List.of(
        new Case("a key a load has no room for",
            "  nodes: person\n  edges: knows\n  count: 1\n  rows:\n" + column,
            "line 5: a load has no key \"rows\""),
        new Case("a table name that is not one",
            "  nodes: a person\n  edges: knows\n  count: 1\n" + column,
            "line 5: \"a person\" is not a table name"),
        new Case("no count",
            "  nodes: person\n  edges: knows\n" + column,
            "line 5: a load says how many rows it has, with `count:`"),
        new Case("a count that is not a number",
            "  nodes: person\n  edges: knows\n  count: three\n" + column,
            "line 5: `count:` is a number of rows"),
        new Case("a count of nothing",
            "  nodes: person\n  edges: knows\n  count: 0\n" + column,
            "line 5: a load of no rows is a load nothing can be read back from"),
        new Case("no columns",
            "  nodes: person\n  edges: knows\n  count: 1\n",
            "line 5: a load has `columns:`"),
        new Case("a column short of the rows the load declares",
            "  nodes: person\n  edges: knows\n  count: 2\n" + column,
            "line 9: column \"name\" holds 1 values against the 2 rows the load declares"),
        new Case("two columns with one name",
            "  nodes: person\n  edges: knows\n  count: 1\n" + column
                + "    - name: name\n      type: STRING\n      values:\n        - b\n",
            "line 5: two columns are called \"name\""),
        new Case("a column type nothing knows",
            "  nodes: person\n  edges: knows\n  count: 1\n"
                + "  columns:\n    - name: name\n      type: TEXT\n      values:\n        - a\n",
            "line 9: TEXT is not a type this encoding knows"),
        new Case("an edge whose row is not in the table",
            "  nodes: person\n  edges: knows\n  count: 2\n"
                + "  columns:\n    - name: name\n      type: STRING\n      values:\n        - a\n"
                + "        - b\n  pairs:\n    - from: 0\n      to: 2\n",
            "line 15: `to: 2` against a table of 2 rows, which are numbered 0 to 1"),
        new Case("an edge with a key it has no room for",
            "  nodes: person\n  edges: knows\n  count: 1\n" + column
                + "  pairs:\n    - from: 0\n      to: 0\n      kind: friend\n",
            "line 14: an edge has no key \"kind\""),
        new Case("an edge missing an end",
            "  nodes: person\n  edges: knows\n  count: 1\n" + column
                + "  pairs:\n    - from: 0\n",
            "line 14: an edge has a `to:` row number"))) {
      String text = "schema: 4\nsuite: test\ndoc: d\nload:\n" + c.body()
          + "cases:\n  - name: one\n    doc: d\n    query: RETURN 1\n    raises: \"42001\"\n";
      assertEquals(c.want(), rejected(text), c.what());
    }
  }

  // readDir walks a directory in sorted order, because a listing's order is
  // the filesystem's and a report diffed against another runner's has to
  // walk them the same way.
  @Test
  @DisplayName("readDir walks the files in order and checks their names")
  void readDirWalksTheFilesInOrderAndChecksTheirNames(@TempDir Path dir, @TempDir Path bare)
      throws IOException {
    write(dir, "zebra.yaml", "zebra");
    write(dir, "alpha.yaml", "alpha");
    // Not a case file, and not read.
    Files.write(dir.resolve("README.md"), "hello".getBytes(StandardCharsets.UTF_8));

    List<Suite> suites = Suite.readDir(dir);
    assertEquals(List.of("alpha", "zebra"), suites.stream().map(Suite::name).toList());

    // A suite whose name and file disagree, which is a file somebody copied
    // and half renamed, and which would otherwise run under a name no report
    // could be diffed on.
    write(dir, "beta.yaml", "gamma");
    String why = assertThrows(CorpusException.class, () -> Suite.readDir(dir),
        "a suite named apart from its file was read").getMessage();
    assertTrue(why.contains("the suite calls itself \"gamma\" and the file calls it \"beta\""),
        () -> "refused with " + why);

    // And a directory with nothing in it, which is almost always a path that
    // was wrong rather than a corpus that is empty.
    String empty = assertThrows(CorpusException.class, () -> Suite.readDir(bare),
        "an empty directory read as a corpus").getMessage();
    assertTrue(empty.endsWith("no case files"), () -> "refused with " + empty);
  }

  private static void write(Path dir, String name, String suite) throws IOException {
    String text = "schema: 4\nsuite: " + suite + "\ndoc: d\ncases:\n  - name: one\n    doc: d\n"
        + "    query: RETURN 1\n    raises: \"42001\"\n";
    Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("a name is checked against what writes it")
  void aNameIsCheckedAgainstWhatWritesIt() {
    record Case(String text, boolean dashed, boolean word) {}
    for (Case c : List.of(
        new Case("one", true, true),
        new Case("a-name", true, false),
        new Case("a-3-hop", true, false),
        new Case("a_name", false, true),
        new Case("Name", false, true),
        new Case("n1", true, true),
        new Case("a name", false, false),
        new Case("", false, false),
        new Case("héllo", false, false))) {
      assertEquals(c.dashed(), Suite.dashedWords(c.text()),
          () -> "dashedWords of " + c.text());
      assertEquals(c.word(), Suite.wordOrUnderscore(c.text()),
          () -> "wordOrUnderscore of " + c.text());
    }
  }
}
