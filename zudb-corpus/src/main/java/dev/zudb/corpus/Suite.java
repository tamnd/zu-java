package dev.zudb.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One file of cases, and how a file of them is read.
 *
 * <p>A case is a statement and what running it must produce. That is
 * deliberately the whole of it. Every client in every language can run a
 * statement and look at the rows that come back, so a corpus written in
 * those terms is one every client can run, and a corpus written in terms of
 * a client's own API would be nine corpora.
 *
 * <p>The expectation is either rows or a condition. A case expecting a
 * condition names the GQLSTATUS code, not the message, because the code is
 * the contract and the message is prose that will improve.
 *
 * <p>A statement may take parameters, which is the other direction the same
 * values travel: a case with {@code params:} writes a value in the encoding,
 * hands it to this client's own binding call, and asserts what came back. A
 * client that decodes a date correctly and encodes it a day early passes
 * every case that has no parameters in it.
 *
 * <p>A case may say which connection each of its statements runs on, which
 * is how a case about a transaction is written: a transaction is only
 * observable from outside it, so a case that has to say what a commit means
 * needs a second connection to say it to. A case that says nothing runs
 * everything on one connection called {@value #MAIN}, which is every case
 * but a handful.
 *
 * @param name the suite's name, which is also its file's name without the
 *     extension
 * @param doc what the suite is about
 * @param load the data every case in the suite runs against, or null for a
 *     suite whose cases need none
 * @param cases the cases, in the order the file writes them
 */
public record Suite(String name, String doc, Load load, List<Case> cases) {

  /**
   * The schema version a file declares.
   *
   * <p>It exists so that a corpus unpacked from an old release tells a new
   * runner what it is instead of failing in the middle.
   */
  public static final int SCHEMA = 4;

  /**
   * The connection a statement runs on when the case does not name one.
   */
  public static final String MAIN = "main";

  private static final String[] SUITE_KEYS = {"schema", "suite", "doc", "load", "cases"};
  private static final String[] CASE_KEYS = {"name", "doc", "setup", "on", "params", "query",
      "columns", "rows", "raises", "arrow"};
  private static final String[] LOAD_KEYS = {"nodes", "edges", "count", "columns", "pairs"};

  /**
   * A statement run before the one under test, and the connection it runs
   * on.
   *
   * @param on the connection this statement runs on, which is {@value #MAIN}
   *     unless the step names another
   * @param query the statement, written the way the case wrote it
   */
  public record Step(String on, String query) {}

  /**
   * One parameter a case binds: the name a statement writes after the
   * {@code $}, and the value.
   *
   * @param name the name without the {@code $}, since that is what binding
   *     it takes
   * @param value the value, read the way every other value in the corpus is
   *     read
   */
  public record Param(String name, Cell value) {}

  /**
   * One statement and what it owes.
   *
   * <p>{@code columns} and {@code rows} are set together or neither is, and
   * {@code raises} is set when neither is: a case says what it produces one
   * way or the other. Columns being set and empty is a case of its own,
   * since FINISH is a statement that answers no columns at all.
   *
   * @param name the case's name within its suite, which with the suite name
   *     is what a report is diffed on
   * @param doc why the case is here, which is the part a reader of the
   *     corpus needs and no runner does
   * @param query the statement under test
   * @param line where the case starts in its file, so that a failure names
   *     somewhere to look
   * @param setup the statements run before the one under test, in order
   * @param on the connection the statement under test runs on, which is
   *     {@value #MAIN} unless the case says otherwise
   * @param params what the statement binds, in the order the case wrote them
   * @param hasColumns whether the case said {@code columns:} at all, which
   *     is what tells a case expecting no columns from one expecting a
   *     condition
   * @param columns the column names the statement answers, in order
   * @param rows the rows it answers, one value per column
   * @param raises the GQLSTATUS the statement owes instead of an answer, and
   *     the empty string for a case that answers
   * @param arrow what the same result looks like on the way out through
   *     Arrow, for a case that says, and null for one that does not. Most do
   *     not: the export gives one answer per column type and a handful of
   *     cases pin every one of them, so the rest would be repeating a type
   *     the corpus already covers
   */
  public record Case(String name, String doc, String query, int line, List<Step> setup, String on,
      List<Param> params, boolean hasColumns, List<String> columns, List<List<Cell>> rows,
      String raises, Arrow.Export arrow) {}

  /**
   * One column of a load: a name, the type every value in it has, and the
   * values in row order.
   *
   * @param name the property name the column is loaded under
   * @param type the type every value in the column has, named once here
   *     rather than beside each value
   * @param values the column's values in row order, one per row the load
   *     declares
   */
  public record Column(String name, String type, List<Cell> values) {}

  /**
   * One edge of a load, as the two row numbers it runs between.
   *
   * @param from the row the edge runs from
   * @param to the row it runs to
   */
  public record Pair(int from, int to) {}

  /**
   * One node table, its columns, and the edges between its rows.
   *
   * <p>Everything else in the corpus is an expression, and an expression
   * says what a value means on the way out and nothing about how it got in.
   * A load is the other half, and every runner puts it in through its own
   * bulk load path, which for this client is {@link dev.zudb.Loader}.
   *
   * @param nodes the name of the node table the rows go into
   * @param edges the name of the edge table the pairs go into
   * @param count how many rows the load has, which every column is checked
   *     against so that a short column is a refusal here rather than a
   *     puzzle later
   * @param columns the node table's columns, in the order they are written
   *     and loaded
   * @param pairs the edges, each a from and a to row number within the node
   *     table
   */
  public record Load(String nodes, String edges, int count, List<Column> columns,
      List<Pair> pairs) {}

  /**
   * Every suite in a directory, in the order a sorted listing gives, which
   * is the order the reference runner walks them in.
   *
   * @param directory where the corpus is
   * @return the suites
   * @throws CorpusException if the directory holds no cases, or one of the
   *     files in it is not a suite
   */
  public static List<Suite> readDir(Path directory) {
    List<Path> paths;
    try (Stream<Path> listing = Files.list(directory)) {
      paths = listing
          .filter(path -> path.getFileName().toString().endsWith(".yaml"))
          // Sorted, because a listing's order is the filesystem's and a
          // report that is diffed against another runner's has to walk them
          // the same way.
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList();
    } catch (IOException e) {
      throw Text.refuse("%s: %s", directory, e);
    }
    List<Suite> suites = new ArrayList<>(paths.size());
    for (Path path : paths) {
      String text;
      try {
        text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw Text.refuse("%s: %s", path, e);
      }
      Suite suite;
      try {
        suite = read(text);
      } catch (CorpusException e) {
        throw Text.refuse("%s: %s", path, e.getMessage());
      }
      String stem = path.getFileName().toString();
      stem = stem.substring(0, stem.length() - ".yaml".length());
      if (!suite.name().equals(stem)) {
        throw Text.refuse("%s: the suite calls itself %s and the file calls it %s",
            path, Text.quote(suite.name()), Text.quote(stem));
      }
      suites.add(suite);
    }
    if (suites.isEmpty()) {
      throw Text.refuse("%s: no case files", directory);
    }
    return List.copyOf(suites);
  }

  /**
   * A suite, or the first thing in the file that is not one.
   *
   * @param text the file
   * @return the suite it holds
   * @throws CorpusException if it is not one
   */
  public static Suite read(String text) {
    Node doc = Yaml.parse(text);
    List<String> unknown = doc.unknown(SUITE_KEYS);
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: a suite has no key %s", doc.line(),
          Text.quote(unknown.get(0)));
    }
    Node schemaNode = doc.get("schema");
    String schema = schemaNode == null ? null : schemaNode.text();
    if (schema == null) {
      throw Text.refuse("the file does not open with `schema:`");
    }
    int version;
    try {
      version = Integer.parseInt(schema);
    } catch (NumberFormatException e) {
      throw Text.refuse("%s is not a schema version", Text.quote(schema));
    }
    if (version != SCHEMA) {
      throw Text.refuse("this is schema %d and the runner reads schema %d", version, SCHEMA);
    }

    String name = field(doc, "suite");
    String docText = field(doc, "doc");
    Load load = null;
    Node loadNode = doc.get("load");
    if (loadNode != null) {
      load = readLoad(loadNode);
    }

    Node casesNode = doc.get("cases");
    if (casesNode == null) {
      throw Text.refuse("a suite with no `cases:`");
    }
    List<Node> items = casesNode.seq();
    if (items == null) {
      throw Text.refuse("`cases:` is a sequence");
    }
    if (items.isEmpty()) {
      throw Text.refuse("a suite with no cases in it");
    }
    List<Case> cases = new ArrayList<>(items.size());
    // Names are what a report cites and what a binding's skip list names, so
    // two cases sharing one is a report that says less than it looks like it
    // does.
    Set<String> seen = new HashSet<>();
    for (Node item : items) {
      Case one = readCase(item);
      if (!seen.add(one.name())) {
        throw Text.refuse("two cases are called %s", Text.quote(one.name()));
      }
      cases.add(one);
    }
    return new Suite(name, docText, load, List.copyOf(cases));
  }

  private static String field(Node node, String key) {
    Node value = node.get(key);
    if (value == null) {
      throw Text.refuse("line %d: no `%s:`", node.line(), key);
    }
    String text = value.text();
    if (text == null) {
      throw Text.refuse("line %d: `%s:` is one line of text", node.line(), key);
    }
    return text;
  }

  private static Case readCase(Node node) {
    int at = node.line();
    if (node.map() == null) {
      throw Text.refuse("line %d: a case is a mapping, and this is %s", at, node.what());
    }
    List<String> unknown = node.unknown(CASE_KEYS);
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: a case has no key %s", at, Text.quote(unknown.get(0)));
    }

    String name = field(node, "name");
    if (!dashedWords(name)) {
      throw Text.refuse("line %d: %s is a case name, which is lower case words joined by dashes",
          at, Text.quote(name));
    }
    String doc = field(node, "doc");
    String query = field(node, "query");

    List<Step> setup = new ArrayList<>();
    Node setupNode = node.get("setup");
    if (setupNode != null) {
      List<Node> items = setupNode.seq();
      if (items == null) {
        throw Text.refuse("line %d: `setup:` is a sequence of statements", at);
      }
      for (Node item : items) {
        setup.add(readStep(item));
      }
    }

    String on = MAIN;
    Node onNode = node.get("on");
    if (onNode != null) {
      on = connectionName(onNode);
    }

    List<Param> params = readParams(node);

    Arrow.Export export = null;
    Node arrowNode = node.get("arrow");
    if (arrowNode != null) {
      export = Arrow.parseExport(arrowNode);
    }

    Node raisesNode = node.get("raises");
    Node columnsNode = node.get("columns");
    if (raisesNode != null && columnsNode != null) {
      throw Text.refuse("line %d: a case that raises has no rows, and one that returns rows does "
          + "not raise", at);
    }
    if (raisesNode != null) {
      String code = raisesNode.text();
      if (code == null) {
        throw Text.refuse("line %d: `raises:` is a GQLSTATUS code", at);
      }
      if (!gqlstatusShaped(code)) {
        throw Text.refuse("line %d: %s is not the shape of a GQLSTATUS, which is five characters "
            + "of digits and capitals", raisesNode.line(), Text.quote(code));
      }
      return new Case(name, doc, query, at, List.copyOf(setup), on, params, false, List.of(),
          List.of(), code, export);
    }
    if (columnsNode == null) {
      throw Text.refuse("line %d: a case says what it produces, with `columns:` and `rows:` or "
          + "with `raises:`", at);
    }
    // Empty counts, because FINISH is a query that answers no columns at
    // all, which is not the same as a query whose columns held no rows, and
    // the corpus writes it as a `columns:` with nothing under it.
    List<Node> names = columnsNode.seqOrEmpty();
    if (names == null) {
      throw Text.refuse("line %d: `columns:` is a sequence of names", at);
    }
    List<String> columns = new ArrayList<>(names.size());
    for (Node item : names) {
      String text = item.text();
      if (text == null) {
        throw Text.refuse("line %d: a column name is one word", item.line());
      }
      columns.add(text);
    }
    List<List<Cell>> rows = readRows(node);
    for (List<Cell> row : rows) {
      if (row.size() != columns.size()) {
        throw Text.refuse("line %d: a row of %d against %d columns",
            at, row.size(), columns.size());
      }
    }
    return new Case(name, doc, query, at, List.copyOf(setup), on, params, true,
        List.copyOf(columns), rows, "", export);
  }

  // One setup statement, which is a line of its own or a line and the
  // connection it runs on.
  private static Step readStep(Node node) {
    String text = node.text();
    if (text != null) {
      return new Step(MAIN, text);
    }
    if (node.map() == null) {
      throw Text.refuse("line %d: a setup statement is one line, or `on:` and `query:`, and this "
          + "is %s", node.line(), node.what());
    }
    List<String> unknown = node.unknown("on", "query");
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: a setup statement has no key %s", node.line(),
          Text.quote(unknown.get(0)));
    }
    Node onNode = node.get("on");
    if (onNode == null) {
      throw Text.refuse("line %d: a setup statement written as a mapping names the connection it "
          + "runs on", node.line());
    }
    return new Step(connectionName(onNode), field(node, "query"));
  }

  // The name of a connection, spelled the way a case name is, because a
  // report cites it and a name a reader has to guess at is a report that
  // says less than it looks like it does.
  private static String connectionName(Node node) {
    String name = node.text();
    if (name == null) {
      throw Text.refuse("line %d: `on:` is the name of a connection", node.line());
    }
    if (!dashedWords(name)) {
      throw Text.refuse("line %d: %s is a connection name, which is lower case words joined by "
          + "dashes", node.line(), Text.quote(name));
    }
    return name;
  }

  // The parameters a case binds, which is the value encoding with a name
  // beside it.
  //
  // A name is what the statement spells after the $, so it is checked
  // against what a statement may spell: a case whose name is "n one" is one
  // no client can bind.
  private static List<Param> readParams(Node node) {
    Node paramsNode = node.get("params");
    if (paramsNode == null) {
      return List.of();
    }
    List<Node> items = paramsNode.seq();
    if (items == null) {
      throw Text.refuse("line %d: `params:` is a sequence", paramsNode.line());
    }
    List<Param> out = new ArrayList<>(items.size());
    for (Node item : items) {
      int at = item.line();
      if (item.map() == null) {
        throw Text.refuse("line %d: a parameter is a mapping of `name`, `type` and `value`, and "
            + "this is %s", at, item.what());
      }
      List<String> unknown = item.unknown("name", "type", "value");
      if (!unknown.isEmpty()) {
        throw Text.refuse("line %d: a parameter has no key %s", at, Text.quote(unknown.get(0)));
      }
      String name = field(item, "name");
      if (!wordOrUnderscore(name)) {
        throw Text.refuse("line %d: %s is a parameter name, which is what a statement writes "
            + "after the `$`", at, Text.quote(name));
      }
      for (Param held : out) {
        if (held.name().equals(name)) {
          throw Text.refuse("line %d: two parameters are called %s", at, Text.quote(name));
        }
      }
      out.add(new Param(name, Values.typed(item)));
    }
    return List.copyOf(out);
  }

  private static List<List<Cell>> readRows(Node node) {
    Node rowsNode = node.get("rows");
    if (rowsNode == null) {
      // A statement that returns no rows is a case worth having, and writing
      // it as an absent `rows:` would make it the same shape as one somebody
      // forgot to finish.
      throw Text.refuse("line %d: `columns:` with no `rows:`. A case expecting nothing back "
          + "writes `rows:` with an empty sequence under it.", node.line());
    }
    List<Node> items = rowsNode.seqOrEmpty();
    if (items == null) {
      throw Text.refuse("line %d: `rows:` is a sequence of rows", rowsNode.line());
    }
    List<List<Cell>> out = new ArrayList<>(items.size());
    for (Node item : items) {
      List<String> unknown = item.unknown("values");
      if (!unknown.isEmpty()) {
        throw Text.refuse("line %d: a row has no key %s", item.line(),
            Text.quote(unknown.get(0)));
      }
      Node cellsNode = item.get("values");
      if (cellsNode == null) {
        throw Text.refuse("line %d: a row is a `values:` and the values under it", item.line());
      }
      List<Node> cells = cellsNode.seqOrEmpty();
      if (cells == null) {
        throw Text.refuse("line %d: `values:` is a sequence of values", cellsNode.line());
      }
      List<Cell> row = new ArrayList<>(cells.size());
      for (Node cell : cells) {
        row.add(Values.decode(cell));
      }
      out.add(List.copyOf(row));
    }
    return List.copyOf(out);
  }

  private static Load readLoad(Node node) {
    int at = node.line();
    if (node.map() == null) {
      throw Text.refuse("line %d: a load is a mapping, and this is %s", at, node.what());
    }
    List<String> unknown = node.unknown(LOAD_KEYS);
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: a load has no key %s", at, Text.quote(unknown.get(0)));
    }
    String nodes = tableName(node, "nodes");
    String edges = tableName(node, "edges");
    Node countNode = node.get("count");
    String countText = countNode == null ? null : countNode.text();
    if (countText == null) {
      throw Text.refuse("line %d: a load says how many rows it has, with `count:`", at);
    }
    int count;
    try {
      count = Integer.parseInt(countText);
    } catch (NumberFormatException e) {
      throw Text.refuse("line %d: `count:` is a number of rows", at);
    }
    if (count == 0) {
      throw Text.refuse("line %d: a load of no rows is a load nothing can be read back from", at);
    }

    Node columnsNode = node.get("columns");
    if (columnsNode == null) {
      throw Text.refuse("line %d: a load has `columns:`", at);
    }
    List<Node> items = columnsNode.seq();
    if (items == null) {
      throw Text.refuse("line %d: `columns:` is a sequence", at);
    }
    List<Column> columns = new ArrayList<>(items.size());
    Set<String> seen = new HashSet<>();
    for (Node item : items) {
      Column column = readColumn(item, count);
      if (!seen.add(column.name())) {
        throw Text.refuse("line %d: two columns are called %s", at, Text.quote(column.name()));
      }
      columns.add(column);
    }
    if (columns.isEmpty()) {
      throw Text.refuse("line %d: a load with no columns holds no values", at);
    }

    List<Pair> pairs = new ArrayList<>();
    Node pairsNode = node.get("pairs");
    if (pairsNode != null) {
      List<Node> written = pairsNode.seqOrEmpty();
      if (written == null) {
        throw Text.refuse("line %d: `pairs:` is a sequence of edges", at);
      }
      for (Node item : written) {
        pairs.add(readEdge(item, count));
      }
    }
    return new Load(nodes, edges, count, List.copyOf(columns), List.copyOf(pairs));
  }

  private static String tableName(Node node, String key) {
    String text = field(node, key);
    if (!wordOrUnderscore(text)) {
      throw Text.refuse("line %d: %s is not a table name", node.line(), Text.quote(text));
    }
    return text;
  }

  private static Column readColumn(Node node, int count) {
    int at = node.line();
    List<String> unknown = node.unknown("name", "type", "values");
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: a column has no key %s", at, Text.quote(unknown.get(0)));
    }
    String name = tableName(node, "name");
    String ty = field(node, "type");
    if (Values.form(ty) == null) {
      throw Text.refuse("line %d: %s is not a type this encoding knows", at, ty);
    }
    Node valuesNode = node.get("values");
    List<Node> items = valuesNode == null ? null : valuesNode.seq();
    if (items == null) {
      throw Text.refuse("line %d: a column holds `values:` in row order", at);
    }
    if (items.size() != count) {
      throw Text.refuse("line %d: column %s holds %d values against the %d rows the load "
          + "declares", at, Text.quote(name), items.size(), count);
    }
    List<Cell> values = new ArrayList<>(items.size());
    for (Node item : items) {
      values.add(Values.payload(ty, item));
    }
    return new Column(name, ty, List.copyOf(values));
  }

  private static Pair readEdge(Node node, int count) {
    int at = node.line();
    List<String> unknown = node.unknown("from", "to");
    if (!unknown.isEmpty()) {
      throw Text.refuse("line %d: an edge has no key %s", at, Text.quote(unknown.get(0)));
    }
    int[] ends = new int[2];
    String[] keys = {"from", "to"};
    for (int i = 0; i < keys.length; i++) {
      Node value = node.get(keys[i]);
      String text = value == null ? null : value.text();
      if (text == null) {
        throw Text.refuse("line %d: an edge has a `%s:` row number", at, keys[i]);
      }
      int end;
      try {
        end = Integer.parseInt(text);
      } catch (NumberFormatException e) {
        throw Text.refuse("line %d: `%s:` is a row number", at, keys[i]);
      }
      if (end < 0 || end >= count) {
        throw Text.refuse("line %d: `%s: %d` against a table of %d rows, which are numbered 0 "
            + "to %d", at, keys[i], end, count, count - 1);
      }
      ends[i] = end;
    }
    return new Pair(ends[0], ends[1]);
  }

  // Whether text is lower case ASCII words joined by dashes, which is how a
  // case and a connection are named.
  static boolean dashedWords(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
        continue;
      }
      return false;
    }
    return true;
  }

  // Whether text is ASCII letters, digits and underscores, which is what a
  // statement may write after a $ and what a table may be called.
  static boolean wordOrUnderscore(String text) {
    if (text.isEmpty()) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
        continue;
      }
      return false;
    }
    return true;
  }

  // Whether a code is the shape of a GQLSTATUS, which is five characters of
  // digits and capitals. The shape and not the list: a corpus that had to be
  // told about every code the standard defines would be one nobody could add
  // a case to.
  private static boolean gqlstatusShaped(String code) {
    if (code.length() != 5) {
      return false;
    }
    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')) {
        continue;
      }
      return false;
    }
    return true;
  }
}
